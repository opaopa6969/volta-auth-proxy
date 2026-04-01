# Policy Engine

[日本語版はこちら](policy-engine.ja.md)

---

## What is it?

A policy engine is a specialized piece of software that evaluates access control rules. You give it a question -- "Can user X do action Y on resource Z?" -- and it returns a decision: allow or deny. The key idea is that the rules (policies) are separated from the application code. You change who can do what by editing policies, not by rewriting your application.

Think of it like a judge in a courtroom. The judge does not write the laws (policies) and does not arrest people (enforcement). The judge receives a case ("Can this person do this thing?"), consults the law book (policy file), and renders a verdict (allow/deny). The laws can be updated independently of the judge.

volta-auth-proxy currently handles access control with its own [RBAC](rbac.md) logic defined in `dsl/policy.yaml`. Phase 4 plans to integrate jCasbin as a dedicated policy engine for more complex rules like ABAC (Attribute-Based Access Control).

---

## Why does it matter?

Without a policy engine, access control rules are scattered across your codebase as if/else checks:

- **Hard to audit**: To answer "who can delete a tenant?", you must search the entire codebase
- **Hard to change**: Adding a new role means touching dozens of files
- **Inconsistent**: Different endpoints may enforce different rules for the same action
- **No history**: You cannot see when a permission was added or removed
- **Tightly coupled**: The application must restart for policy changes to take effect

With a policy engine:

- **Centralized rules**: All policies in one place
- **Hot reloading**: Change policies without restarting the application
- **Audit trail**: Policy changes are versioned and logged
- **Separation of concerns**: Developers write code, security teams write policies

---

## How does it work?

### The three components

```
  ┌─────────────┐    ┌──────────────────┐    ┌───────────────┐
  │ Enforcement  │    │ Policy Engine    │    │ Policy Store  │
  │ Point (PEP) │───►│ (Decision Point) │◄───│ (Rules)       │
  │              │    │                  │    │               │
  │ "Block or    │    │ "Evaluate rules, │    │ "Who can do   │
  │  allow the   │    │  return allow/   │    │  what to      │
  │  request"    │    │  deny"           │    │  which"       │
  └─────────────┘    └──────────────────┘    └───────────────┘
      (code)              (engine)               (data)
```

### Common policy models

| Model | How it works | Example |
|-------|-------------|---------|
| **RBAC** (Role-Based) | Permissions assigned to roles, roles assigned to users | ADMIN can invite_members |
| **ABAC** (Attribute-Based) | Rules based on any attribute of user, resource, environment | user.department == resource.department |
| **ReBAC** (Relationship-Based) | Rules based on relationships between entities | user is member of org that owns document |
| **ACL** (Access Control List) | Explicit per-resource permission lists | file.acl includes user_id |

### Popular policy engines

```
  ┌────────────────────────────────────────────────────────┐
  │ Engine     │ Language │ Model │ Notes                   │
  │────────────│──────────│───────│─────────────────────────│
  │ jCasbin    │ Java     │ PERM  │ Flexible, multiple      │
  │            │          │       │ models. volta Phase 4.  │
  │────────────│──────────│───────│─────────────────────────│
  │ OPA        │ Go       │ Rego  │ CNCF project. Powerful  │
  │ (Rego)     │          │       │ but steep learning curve│
  │────────────│──────────│───────│─────────────────────────│
  │ Cedar      │ Rust     │ ABAC  │ AWS-backed. Strong type │
  │            │          │       │ system. Newer.          │
  │────────────│──────────│───────│─────────────────────────│
  │ Keycloak   │ Java     │ RBAC  │ Full IdP, heavy. volta  │
  │            │          │       │ deliberately avoids.    │
  └────────────────────────────────────────────────────────┘
```

### jCasbin model (volta Phase 4)

jCasbin uses a PERM (Policy, Effect, Request, Matchers) model:

```
  Model definition (model.conf):
  ┌─────────────────────────────────────────────┐
  │ [request_definition]                        │
  │ r = sub, obj, act                           │
  │                                             │
  │ [policy_definition]                         │
  │ p = sub, obj, act                           │
  │                                             │
  │ [role_definition]                           │
  │ g = _, _                                    │
  │                                             │
  │ [policy_effect]                             │
  │ e = some(where (p.eft == allow))            │
  │                                             │
  │ [matchers]                                  │
  │ m = g(r.sub, p.sub) && r.obj == p.obj       │
  │     && r.act == p.act                       │
  └─────────────────────────────────────────────┘

  Policy file (policy.csv):
  ┌─────────────────────────────────────────────┐
  │ p, ADMIN, /admin/members, read              │
  │ p, ADMIN, /admin/invitations, read          │
  │ p, OWNER, /admin/keys, manage               │
  │ g, alice, ADMIN                             │
  │ g, ADMIN, MEMBER   (role inheritance)       │
  └─────────────────────────────────────────────┘
```

---

## How does volta-auth-proxy use it?

### Current approach: built-in RBAC (Phase 1-3)

volta currently implements policy evaluation directly in Java code, using rules from `dsl/policy.yaml`:

```
  Request arrives
  │
  ├── ForwardAuth (/auth/verify)
  │   │
  │   ├── Read user's role from session/JWT
  │   ├── Read app's allowed_roles from volta-config.yaml
  │   └── Check: user.role in app.allowed_roles?
  │       ├── Yes → 200 + X-Volta-* headers
  │       └── No  → 403 ROLE_INSUFFICIENT
  │
  └── Internal API (/api/v1/*)
      │
      ├── Read user's role from JWT
      └── Check: role >= required_role?
          (Using hierarchy: OWNER > ADMIN > MEMBER > VIEWER)
```

The enforcement logic lives in `AppRegistry.java` (for ForwardAuth app matching) and `AuthService.java` (for API authorization).

### Phase 4: jCasbin integration

```
  Current (Phase 1-3):              Future (Phase 4):
  ┌──────────────────────┐         ┌──────────────────────┐
  │ AuthService.java     │         │ AuthService.java     │
  │                      │         │                      │
  │ if (role >= ADMIN) { │  ───►   │ if (casbin.enforce(  │
  │   allow();           │         │   user, resource,    │
  │ }                    │         │   action)) {         │
  │                      │         │   allow();           │
  └──────────────────────┘         │ }                    │
                                   └──────────────────────┘
  Rules in Java code                Rules in policy files
  Hard to change                    Change without restart
  Only RBAC                         RBAC + ABAC + custom
```

### Why volta does not use a policy engine yet

volta's philosophy is: build only what you need. The current 4-role RBAC hierarchy with per-app `allowed_roles` covers all Phase 1-3 use cases. Adding jCasbin now would add complexity without immediate benefit. Phase 4 introduces it when multi-tenant ABAC policies become necessary (e.g., "users in department X can only access app Y during business hours").

### What policy.yaml defines today

```yaml
# dsl/policy.yaml
roles:
  hierarchy: [OWNER, ADMIN, MEMBER, VIEWER]

permissions:
  OWNER:
    inherits: ADMIN
    can: [delete_tenant, transfer_ownership, manage_signing_keys]
  ADMIN:
    inherits: MEMBER
    can: [invite_members, remove_members, view_audit_logs]
  MEMBER:
    inherits: VIEWER
    can: [use_apps, manage_own_sessions, switch_tenant]
  VIEWER:
    can: [read_only]
```

---

## Common mistakes and attacks

### Mistake 1: Adopting a policy engine too early

Adding OPA or Cedar to a project with 3 roles and 5 endpoints is over-engineering. You spend more time configuring the engine than writing the rules. Start with code-based RBAC, migrate when complexity demands it.

### Mistake 2: Policy engine without enforcement

A policy engine that returns "deny" is useless if the code does not check the result. Every protected endpoint must call the engine and act on its decision.

### Mistake 3: Overly complex policies

A 500-line Rego policy is harder to audit than the spaghetti code it replaced. Keep policies simple, test them thoroughly, and document why each rule exists.

### Attack: Policy bypass

If the policy engine is a separate service, an attacker might try to call the application directly, bypassing the engine. volta prevents this by enforcing policies in the same process (ForwardAuth), not in an external service.

---

## Further reading

- [rbac.md](rbac.md) -- volta's current role-based access control.
- [role.md](role.md) -- The 4 roles in volta's hierarchy.
- [hierarchy.md](hierarchy.md) -- How role inheritance works.
- [enforcement.md](enforcement.md) -- How policies are enforced.
- [dsl.md](dsl.md) -- Where volta's current policies are defined.
- [forwardauth.md](forwardauth.md) -- Where app access policies are enforced.
