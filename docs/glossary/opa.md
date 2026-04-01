# OPA (Open Policy Agent)

[日本語版はこちら](opa.ja.md)

---

## What is it?

Open Policy Agent (OPA, pronounced "oh-pa") is a general-purpose policy engine from the Cloud Native Computing Foundation (CNCF). It lets you write authorization rules in a dedicated language called **Rego** and evaluate them against structured data. OPA runs as a standalone service (or sidecar) and exposes an HTTP API -- your application sends a JSON request describing "who wants to do what," and OPA responds with "allow" or "deny."

Think of OPA like a judge in a courtroom. Your application (the prosecutor) presents the facts of a case: "User alice wants to DELETE resource /api/users/42 in tenant acme." The judge (OPA) consults the law book (Rego policies) and renders a verdict: allowed or denied. The key distinction is that the judge is a separate person who does not work for the prosecutor -- OPA is a separate process from your application.

This is fundamentally different from an in-process library like [jCasbin](jcasbin.md), where the authorization logic runs inside your application. OPA is always a network call away.

---

## Why does it matter?

Authorization decisions happen everywhere -- in APIs, Kubernetes admission control, CI/CD pipelines, database queries, infrastructure provisioning. Without a unified policy engine, each system implements its own authorization logic in its own language, and there is no single place to audit "who can do what."

OPA provides a single policy language (Rego) and a single evaluation engine for all of these contexts. Write your policies once, enforce them everywhere. This is powerful for organizations with many services and strict compliance requirements.

OPA is a CNCF **graduated** project (the highest maturity level, alongside Kubernetes, Prometheus, and Envoy). This signals broad industry adoption and long-term stability.

However, OPA's power comes with complexity. Rego is a declarative logic programming language that looks nothing like Java, Python, or JavaScript. The learning curve is steep, and debugging Rego policies can be challenging.

---

## How does it work?

### Architecture: the sidecar pattern

```
  ┌─────────────────┐         ┌──────────────┐
  │  Your App        │  HTTP   │     OPA      │
  │                  │────────►│              │
  │  "Can alice      │         │  Evaluates   │
  │   DELETE          │         │  Rego policy │
  │   /api/users/42?" │         │              │
  │                  │◄────────│  { "allow":  │
  │                  │         │    true }     │
  └─────────────────┘         └──────────────┘
```

OPA runs as a separate process (often as a sidecar container in Kubernetes). Your application sends HTTP POST requests to OPA's REST API with the input data, and OPA evaluates policies against that input.

### Rego language

Rego is OPA's policy language. It is declarative -- you describe what should be true, not how to compute it.

```rego
package volta.authz

default allow = false

# Admins can do anything
allow {
    input.user.role == "admin"
}

# Users can read their own tenant's resources
allow {
    input.action == "GET"
    input.user.tenant_id == input.resource.tenant_id
}

# Tenant owners can manage users in their tenant
allow {
    input.action in ["GET", "POST", "DELETE"]
    input.resource.path == "/api/users"
    input.user.role == "owner"
    input.user.tenant_id == input.resource.tenant_id
}
```

### Input and data

OPA separates **input** (the specific request being evaluated) from **data** (reference information like role assignments, resource metadata):

| Concept | Description | Example |
|---------|-------------|---------|
| **Input** | The request to evaluate. Provided per-request via HTTP. | `{"user": {"email": "alice@acme.com", "role": "admin"}, "action": "DELETE", "resource": {"path": "/api/users/42"}}` |
| **Data** | Reference data loaded in advance. Can come from bundles, files, or APIs. | Role definitions, tenant configurations, resource hierarchies |
| **Policy** | The Rego rules that define authorization logic. Loaded at startup or via bundles. | The `.rego` files above |

### Bundle system

OPA can load policies and data from **bundles** -- tar.gz archives served by an HTTP server. This allows centralized policy management:

```
  ┌──────────────────┐      ┌────────────────┐
  │  Policy Server    │      │      OPA       │
  │  (HTTP, S3, etc.) │◄─────│  Polls for     │
  │                   │      │  new bundles   │
  │  bundle.tar.gz    │─────►│  every 30s     │
  │   ├── policy.rego │      │                │
  │   └── data.json   │      │  Updates live  │
  └──────────────────┘      └────────────────┘
```

### OPA vs jCasbin vs Cedar

| Feature | OPA | [jCasbin](jcasbin.md) | Cedar (AWS) |
|---------|-----|---------|-------|
| Deployment | Sidecar / daemon | In-process library | Library or service |
| Language | Rego (declarative, logic-based) | PERM model + CSV/DB | Cedar (typed, structured) |
| Written in | Go | Java | Rust |
| Latency | 1-5ms (HTTP call) | Sub-millisecond | Sub-millisecond |
| CNCF status | Graduated | Sandbox (Casbin) | N/A |
| Scope | General-purpose (authz, K8s, CI/CD) | Authorization only | Authorization only |
| Learning curve | High (Rego) | Low (model DSL) | Medium |
| Ecosystem | Very large (K8s, Envoy, Terraform, etc.) | Moderate | Growing (AWS ecosystem) |
| Debugging | OPA REPL, tracing, playground | Simple enforce() call | Cedar CLI |

---

## How does volta-auth-proxy use it?

OPA is evaluated as an **alternative policy engine** for volta-auth-proxy's Phase 4. While [jCasbin](jcasbin.md) is the recommended option, OPA is documented for organizations that already use OPA elsewhere in their infrastructure and want to keep a single policy engine.

### Why jCasbin is preferred over OPA for volta

volta's core philosophy is simplicity and control:

1. **No network hop**: jCasbin runs in-process. OPA requires an HTTP call for every authorization decision.
2. **No new language**: jCasbin's PERM model is simple configuration. Rego is a full programming language with its own learning curve.
3. **No sidecar management**: jCasbin is a Maven dependency. OPA is a separate container to deploy, monitor, and maintain.
4. **Debugging**: jCasbin can be stepped through in IntelliJ. OPA requires separate tooling.

### When OPA makes sense with volta

- Your organization already runs OPA for Kubernetes admission control
- You want a single policy engine for your entire infrastructure (not just volta)
- Your policies are complex enough to benefit from Rego's expressiveness
- You have a dedicated platform team to manage OPA

### Integration pattern (if chosen)

```
  Browser ──► Traefik ──► volta-auth-proxy
                               │
                               │  1. Authenticate (session/JWT)
                               │  2. Build authorization context
                               │
                               ▼
                          ┌────────┐
                          │  OPA   │  (sidecar)
                          │        │
                          │  POST  │  /v1/data/volta/authz
                          │  input:│  {user, tenant, resource, action}
                          │        │
                          │  resp: │  {allow: true/false}
                          └────────┘
                               │
                               ▼
                          3. If allowed: 200 + headers
                             If denied: 403
```

---

## Common mistakes and attacks

### Mistake 1: Defaulting to allow

In Rego, if no rule matches, the result is `undefined`. If your application treats `undefined` as "allow," every unhandled case becomes a security hole. Always set `default allow = false`.

### Mistake 2: Not testing policies

Rego policies can have subtle bugs. OPA includes a built-in test framework -- use it. Write test cases for every policy rule, especially edge cases.

### Mistake 3: Ignoring OPA latency

Every authorization check is an HTTP round-trip. At 2ms per call with 10 authorization checks per request, you add 20ms of latency. Consider batching decisions or caching results for short periods.

### Mistake 4: Exposing OPA's API externally

OPA's REST API allows querying any policy with any input. If exposed to the internet, attackers can probe your authorization logic to find gaps. Keep OPA on an internal network.

### Attack: Policy tampering via bundles

If an attacker compromises your bundle server, they can push a policy that allows everything. Sign your bundles and verify signatures in OPA to prevent this.

---

## Further reading

- [OPA official documentation](https://www.openpolicyagent.org/docs/latest/) -- The complete reference.
- [Rego playground](https://play.openpolicyagent.org/) -- Try Rego in the browser.
- [OPA on CNCF](https://www.cncf.io/projects/open-policy-agent/) -- Project status and governance.
- [jcasbin.md](jcasbin.md) -- volta's preferred in-process policy engine.
- [oauth2.md](oauth2.md) -- Authentication vs authorization.
- [forwardauth.md](forwardauth.md) -- How volta integrates with reverse proxies.
