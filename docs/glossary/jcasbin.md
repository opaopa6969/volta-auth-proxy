# jCasbin

[日本語版はこちら](jcasbin.ja.md)

---

## What is it?

jCasbin is a Java authorization library that helps you decide **who can do what**. It is the Java implementation of the Casbin project, a CNCF (Cloud Native Computing Foundation) sandbox project. While authentication answers "who are you?", authorization answers "are you allowed to do this?" jCasbin handles the second question.

Think of it like the rules posted in an apartment building. Authentication is the front door key -- it proves you live here. Authorization is the list of rules: "Residents can use the pool from 6am to 10pm. Only the landlord can access the boiler room. Guests can only enter common areas." jCasbin is the system that checks these rules every time someone tries to open a door.

jCasbin uses a model file (written in a simple configuration language called PERM) to define the authorization logic, and a policy file (or database table) to define the specific rules. Change the model, and you change the entire authorization scheme -- from simple ACL to RBAC to ABAC -- without changing application code.

---

## Why does it matter?

Authorization logic is deceptively complex. What starts as a simple "admin or not" check quickly grows into a tangled web of if-else statements scattered across your codebase. Common evolution:

1. Day 1: `if (user.isAdmin()) { ... }`
2. Month 3: `if (user.isAdmin() || user.isTenantOwner()) { ... }`
3. Month 6: `if (user.isAdmin() || (user.isTenantOwner() && resource.getTenantId().equals(user.getTenantId()))) { ... }`
4. Year 2: Nobody understands the authorization logic anymore

jCasbin solves this by separating the authorization model from the application code. The rules live in a policy store (a file, a database, or an API), and the application simply asks jCasbin: "Can user X do action Y on resource Z?" The answer is yes or no.

For a multi-tenant SaaS like volta-auth-proxy, this separation is critical. Different tenants might need different permission models. One tenant wants simple role-based access. Another wants attribute-based policies. jCasbin supports both with the same engine.

---

## How does it work?

### The PERM model

jCasbin uses a model defined by four elements: **P**olicy, **E**ffect, **R**equest, **M**atcher (PERM).

```ini
# model.conf -- RBAC example
[request_definition]
r = sub, obj, act

[policy_definition]
p = sub, obj, act

[role_definition]
g = _, _

[policy_effect]
e = some(where (p.eft == allow))

[matchers]
m = g(r.sub, p.sub) && r.obj == p.obj && r.act == p.act
```

This reads as: "A request has a subject, object, and action. A policy has the same. There are roles (g). The effect is 'allow if any policy matches'. The matcher checks: does the subject (or their role) match the policy subject, AND does the object match, AND does the action match?"

### Policy examples

```csv
# policy.csv
p, admin, /api/users, GET
p, admin, /api/users, POST
p, admin, /api/users, DELETE
p, editor, /api/articles, GET
p, editor, /api/articles, POST
p, viewer, /api/articles, GET

# Role assignments
g, alice, admin
g, bob, editor
g, charlie, viewer
```

With this policy:
- Alice (admin) can GET, POST, DELETE users and articles
- Bob (editor) can GET and POST articles
- Charlie (viewer) can only GET articles

### Supported models

| Model | Description | Use case |
|-------|-------------|----------|
| **ACL** | Access Control List. Direct user-to-permission mapping. | Simple apps, few users |
| **RBAC** | Role-Based Access Control. Users have roles, roles have permissions. | Most SaaS applications |
| **RBAC with domains** | RBAC where roles are scoped to a domain (tenant). | Multi-tenant SaaS |
| **ABAC** | Attribute-Based Access Control. Policies based on attributes of user, resource, environment. | Complex enterprise rules |
| **RESTful** | Matches HTTP method + path patterns. | API authorization |

### jCasbin vs alternatives

| Feature | jCasbin | [OPA](opa.md) | Cedar (AWS) | Spring Security |
|---------|---------|------|-------|-----------------|
| Language | Java (pure library) | Go (HTTP sidecar) | Rust (library/service) | Java (framework) |
| Policy language | PERM (model) + CSV/DB | Rego | Cedar | SpEL / annotations |
| Deployment | In-process | Sidecar / daemon | In-process or service | In-process |
| Latency | Microseconds | Milliseconds (HTTP call) | Microseconds | Microseconds |
| CNCF status | Sandbox | Graduated | N/A | N/A |
| Multi-tenant | RBAC with domains | Manual | Manual | Manual |
| Learning curve | Low | High (Rego is unique) | Medium | Medium (Spring ecosystem) |
| Persistence | File, DB, API adapters | Bundle / API | File / API | Code-defined |

### Architecture in a Java application

```
  HTTP Request
       │
       ▼
  ┌──────────────┐
  │  Controller   │
  │              │
  │  enforcer    │──► jCasbin Enforcer
  │  .enforce()  │       │
  │              │       ├── Model (PERM rules)
  │              │       └── Policy (from DB/file)
  │              │              │
  │  if allowed: │◄─── true/false
  │    proceed   │
  │  else:       │
  │    403       │
  └──────────────┘
```

---

## How does volta-auth-proxy use it?

jCasbin is volta-auth-proxy's **recommended policy engine for Phase 4**. In earlier phases, volta uses simple role checks hardcoded in Java. Phase 4 introduces a pluggable policy engine, and jCasbin is the primary candidate.

### Why jCasbin over OPA?

| Concern | jCasbin | [OPA](opa.md) |
|---------|---------|------|
| Deployment | In-process (just a JAR) | Separate sidecar process |
| Latency | Sub-millisecond | 1-5ms per HTTP call |
| Language | Java (same as volta) | Go + Rego |
| Debugging | Step through with your IDE | Separate process, separate logs |
| Dependencies | One Maven dependency | Docker container + HTTP client |

volta's philosophy is "understand every line" and "no black boxes." jCasbin fits this perfectly -- it is a pure Java library that you call directly, with no network hop, no separate process, and no unfamiliar query language.

### Planned integration

```java
// Phase 4 -- planned
Enforcer enforcer = new Enforcer("model.conf", new JDBCAdapter(dataSource));

// In the ForwardAuth handler:
String user = session.getUserEmail();
String tenant = session.getTenantId();
String resource = request.getPath();
String action = request.getMethod();

if (enforcer.enforce(user, tenant, resource, action)) {
    // Set headers, return 200
} else {
    // Return 403
}
```

### Multi-tenant authorization

jCasbin's "RBAC with domains" model maps directly to volta's multi-tenant architecture:

```ini
[matchers]
m = g(r.sub, p.sub, r.dom) && r.dom == p.dom && r.obj == p.obj && r.act == p.act
```

```csv
# Alice is admin in tenant-1, but only viewer in tenant-2
p, admin, tenant-1, /api/*, *
p, viewer, tenant-2, /api/articles, GET

g, alice, admin, tenant-1
g, alice, viewer, tenant-2
```

This means the same user can have different roles in different tenants -- exactly what multi-tenant SaaS needs.

---

## Common mistakes and attacks

### Mistake 1: Not reloading policies after changes

If you store policies in a database and update them, the in-memory enforcer does not automatically pick up the changes. You must call `enforcer.loadPolicy()` or use a watcher that triggers reloads. Forgetting this means policy changes have no effect until restart.

### Mistake 2: Using ACL when you need RBAC

Starting with direct user-to-permission mappings seems simpler, but it does not scale. When you have 100 users and 50 permissions, you have 5,000 potential policy rows. Use roles from the beginning.

### Mistake 3: Overly broad matchers

A matcher like `r.obj == p.obj || r.sub == "admin"` might seem convenient, but it bypasses all object-level checks for admins. Be explicit about what each role can access.

### Mistake 4: Not testing the model with edge cases

PERM models can have subtle bugs. Always test with cases like: "What happens if a user has no roles? What if they have conflicting roles? What if the resource does not exist in any policy?"

### Attack: Policy injection

If policy data comes from user input (e.g., a tenant admin can create policies), validate that they cannot escalate their own privileges. A tenant admin should not be able to create a policy granting themselves super-admin access.

---

## Further reading

- [Casbin documentation](https://casbin.org/docs/overview) -- Official docs with examples for all supported models.
- [jCasbin GitHub](https://github.com/casbin/jcasbin) -- The Java implementation.
- [Casbin online editor](https://casbin.org/editor/) -- Test your model and policies interactively.
- [opa.md](opa.md) -- Alternative policy engine (sidecar pattern).
- [oauth2.md](oauth2.md) -- Authentication vs authorization context.
- [sso.md](sso.md) -- How SSO relates to authorization.
