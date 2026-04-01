# volta-auth-proxy DSL Overview

[English](dsl-overview.md) | [日本語](dsl-overview.ja.md)

> volta DSL is the **single source of truth** for auth behavior.
> Implementation is a driver. DSL is the specification.

---

## What is the volta DSL?

volta-auth-proxy defines all authentication behavior in 4 YAML files instead of scattering logic across code, config, and documentation.

```
DSL (specification)  →  Java code (implementation)
                     →  Tests (auto-generated from DSL)
                     →  Mermaid diagrams (documentation)
                     →  Policy engine driver (Phase 4)
```

The DSL was designed through [DGE](../dge/sessions/) sessions (106 gaps found and resolved) and refined through 3 rounds of tribunal reviews (final score: 10/10).

---

## DSL Files

| File | Purpose | Version |
|------|---------|---------|
| [`dsl/auth-machine.yaml`](../dsl/auth-machine.yaml) | State machine — 8 states, all transitions, guards, actions, errors | v3.2 |
| [`dsl/protocol.yaml`](../dsl/protocol.yaml) | App contract — [ForwardAuth](glossary/forwardauth.md) headers, [JWT](glossary/jwt.md) spec, [API](glossary/api.md) endpoints, data models | v2 |
| [`dsl/policy.yaml`](../dsl/policy.yaml) | Authorization — [role](glossary/role.md) hierarchy, permissions, constraints, [tenant](glossary/tenant.md) isolation, [rate limiting](glossary/rate-limiting.md), [CSRF](glossary/csrf.md), audit | v1 |
| [`dsl/errors.yaml`](../dsl/errors.yaml) | Error registry — all error codes, messages (en/ja), recovery actions. Single source of truth | v2 |

### Phase 2-4 extensions

| File | Purpose |
|------|---------|
| [`dsl/auth-machine-phase2-4.yaml`](../dsl/auth-machine-phase2-4.yaml) | Additional states: [MFA](glossary/mfa.md), [SAML](glossary/sso.md), M2M, Webhooks |
| [`dsl/volta-config.schema.yaml`](../dsl/volta-config.schema.yaml) | JSON Schema for volta-config.yaml validation |

---

## State Machine (auth-machine.yaml)

8 states define every possible user condition:

```mermaid
stateDiagram-v2
    [*] --> UNAUTHENTICATED
    UNAUTHENTICATED --> AUTH_PENDING : Login
    AUTH_PENDING --> INVITE_CONSENT : Callback + invite
    AUTH_PENDING --> AUTHENTICATED : Callback + 1 tenant
    AUTH_PENDING --> TENANT_SELECT : Callback + multi tenant
    AUTH_PENDING --> NO_TENANT : Callback + no tenant
    INVITE_CONSENT --> AUTHENTICATED : Accept
    TENANT_SELECT --> AUTHENTICATED : Select
    NO_TENANT --> INVITE_CONSENT : Receive invite
    AUTHENTICATED --> TENANT_SUSPENDED : Tenant suspended
    TENANT_SUSPENDED --> TENANT_SELECT : Switch
    AUTHENTICATED --> UNAUTHENTICATED : Logout
```

### Guard expressions (CEL-like syntax)

```yaml
guard: "session.valid && tenant.active && membership.role in ['ADMIN', 'OWNER']"
```

Operators: `&&`, `||`, `!`, `==`, `!=`, `>`, `<`, `>=`, `<=`, `in`
Template expressions: `"{request.return_to || config.default_app_url}"` (`||` = coalesce)

### Transition priority

Same-trigger transitions are evaluated by `priority` (lowest number first):

```yaml
callback_error:          { priority: 1 }  # Check errors first
callback_state_invalid:  { priority: 2 }
callback_nonce_invalid:  { priority: 3 }
callback_email_unverified: { priority: 4 }
callback_success:        { priority: 5 }  # Success last
```

### Global transitions

Logout and session timeout apply to all authenticated states:

```yaml
global_transitions:
  logout:
    from_except: [UNAUTHENTICATED, AUTH_PENDING]
    next: UNAUTHENTICATED
  session_timeout:
    from_except: [UNAUTHENTICATED]
    next: UNAUTHENTICATED
```

### Invariants

Formal properties guaranteed by the DSL:

```yaml
invariants:
  - no_deadlock: "Every non-terminal state has at least one reachable outgoing transition"
  - reachable_auth: "From any state, there exists a path to AUTHENTICATED or UNAUTHENTICATED"
  - logout_always_possible: "From any authenticated state, logout is reachable"
  - no_undefined_refs: "Every 'next' value references a defined state"
  - error_codes_defined: "Every error code used exists in errors.yaml"
```

---

## Protocol (protocol.yaml)

Defines the contract between volta-auth-proxy and downstream [Apps](glossary/downstream-app.md):

### ForwardAuth headers

```
X-Volta-User-Id:      uuid
X-Volta-Email:        email
X-Volta-Tenant-Id:    uuid
X-Volta-Tenant-Slug:  string
X-Volta-Roles:        comma-separated
X-Volta-Display-Name: string (optional)
X-Volta-JWT:          signed RS256 JWT
X-Volta-App-Id:       string (optional)
```

### JWT claims

```json
{
  "iss": "volta-auth",
  "aud": ["volta-apps"],
  "sub": "user-uuid",
  "volta_v": 1,
  "volta_tid": "tenant-uuid",
  "volta_tname": "ACME Corp",
  "volta_tslug": "acme",
  "volta_roles": ["ADMIN"]
}
```

### Internal API

17 endpoints for app delegation. See [protocol.yaml](../dsl/protocol.yaml) for full spec.

---

## Policy (policy.yaml)

### Role hierarchy

```
OWNER > ADMIN > MEMBER > VIEWER
```

### Constraints

```yaml
constraints:
  - last_owner: "A tenant MUST have at least one OWNER"
  - promote_limit: "Cannot promote above own role"
  - max_tenants: 10 per user
  - max_members: 50 per tenant
  - concurrent_sessions: 5 per user
```

### Tenant isolation

```yaml
tenant_isolation:
  - path_jwt_match: "API path {tenantId} MUST equal JWT volta_tid"
  - session_tenant_bound: "Session bound to exactly one tenant"
  - member_visibility: "Can only see members of own tenants"
```

---

## Policy Engine Driver Strategy

volta DSL is always the **master**. The evaluation engine is a **driver** — swappable via [Interface](glossary/interface-extension-point.md):

```java
interface PolicyEvaluator {
    boolean evaluate(PolicyRequest request);
}
```

| Phase | Driver | Type | Dependencies |
|-------|--------|------|-------------|
| **Phase 1-3** | `JavaPolicyEvaluator` | Pure Java, direct evaluation | None |
| **Phase 4 Option A** | `CasbinPolicyEvaluator` | [jCasbin](https://github.com/casbin/jcasbin) | Pure Java, Maven Central |
| **Phase 4 Option B** | `CedarPolicyEvaluator` | [Cedar Java](https://github.com/cedar-policy/cedar-java) | JNI (Rust native) |
| **Phase 4 Option C** | `OpaPolicyEvaluator` | [OPA](https://www.openpolicyagent.org/) sidecar | Separate process (Go) |

### DSL → Driver conversion

```
volta policy.yaml
    ↓
    ├── Phase 1-3: Java code (if/switch in AuthService.java)
    ├── Phase 4A:  → model.conf + policy.csv → jCasbin
    ├── Phase 4B:  → Cedar policy language → cedar-java (JNI)
    └── Phase 4C:  → Rego → OPA server (HTTP)
```

### Driver comparison

| | jCasbin | Cedar Java | OPA Sidecar |
|---|---|---|---|
| **Pure Java** | Yes | No (JNI) | No (HTTP) |
| **Extra infra** | None | None | OPA process |
| **Performance** | Microseconds | Microseconds | 1-5ms (HTTP) |
| **RBAC** | Yes | Yes | Yes |
| **ABAC** | Yes | Yes | Yes |
| **Deny rules** | Yes | Yes (first-class) | Yes |
| **Dynamic reload** | Yes (DB adapter) | No | Yes (bundle) |
| **volta philosophy match** | Best | Good | Least |
| **Maturity** | CNCF Incubating | AWS production | CNCF Graduated |

**Recommended for Phase 4: jCasbin** — Pure Java, no extra infrastructure, aligns with "tight coupling" philosophy.

---

## DSL Validator

A [validator specification](dsl-validator-spec.md) defines 60+ checks across 5 categories:

1. **Structural** — per-file schema validation
2. **Cross-file references** — errors.yaml as single source of truth
3. **State machine invariants** — reachability, deadlock, priority
4. **Guard expressions** — variable resolution, syntax validation
5. **Completeness** — audit events, context usage, error code coverage

---

## Full flow diagrams

All state transitions are visualized with mermaid in [ui-flow.md](../dge/specs/ui-flow.md):

- [User state model](../dge/specs/ui-flow.md#user-state-model)
- [Invite → first login](../dge/specs/ui-flow.md#flow-1-invite-link---first-login)
- [ForwardAuth flow](../dge/specs/ui-flow.md#flow-2-returning-user---session-valid)
- [Full screen transition map](../dge/specs/ui-flow.md#full-screen-transition-map)
- [Error recovery](../dge/specs/ui-flow.md#error-recovery-flow)
