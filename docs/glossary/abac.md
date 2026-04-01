# ABAC (Attribute-Based Access Control)

[日本語版はこちら](abac.ja.md)

---

## What is it?

ABAC (Attribute-Based Access Control) is an authorization model that makes access decisions based on attributes -- properties of the user, the resource, the action, and the environment. Instead of simply checking "does this user have the ADMIN role?", ABAC can evaluate complex rules like "does this user belong to the engineering department, is the resource in their tenant, is it business hours, and are they accessing from a corporate IP?"

Think of it like a nightclub with a very detailed bouncer. A simple bouncer just checks if your name is on the guest list (role-based). An ABAC bouncer checks your age, your outfit, the time of night, whether the club is at capacity, whether you have a VIP wristband, and whether the event tonight allows your ticket type. Each attribute adds a dimension to the access decision.

ABAC is more flexible than simple [role](role.md)-based access control (RBAC). Where RBAC gives you coarse-grained control ("admins can do everything"), ABAC gives you fine-grained, context-aware policies.

---

## Why does it matter?

RBAC works for simple scenarios, but real-world access control is rarely simple:

```
  RBAC (simple, but limited):
  ┌─────────────────────────────────────┐
  │  User has role ADMIN?               │
  │    YES → Allow everything           │
  │    NO  → Deny                       │
  └─────────────────────────────────────┘

  ABAC (flexible, context-aware):
  ┌─────────────────────────────────────────────────────┐
  │  User attributes:                                    │
  │    department = "engineering"                         │
  │    clearance  = "confidential"                       │
  │                                                      │
  │  Resource attributes:                                │
  │    tenant_id  = "acme-uuid"                          │
  │    sensitivity = "confidential"                      │
  │                                                      │
  │  Context attributes:                                 │
  │    time       = 14:30 (business hours)               │
  │    ip_range   = "10.0.0.0/8" (corporate)            │
  │                                                      │
  │  Policy: ALLOW if                                    │
  │    user.department == "engineering" AND               │
  │    user.clearance >= resource.sensitivity AND         │
  │    context.time IN business_hours AND                 │
  │    context.ip IN corporate_range                      │
  └─────────────────────────────────────────────────────┘
```

Key benefits:

- **Fine-grained**: Control access at the attribute level, not just the role level
- **Dynamic**: Policies adapt to context (time, location, risk score)
- **Scalable**: Add new attributes without restructuring your role hierarchy
- **Compliant**: Express regulatory requirements as policies (e.g., "PHI only accessible by healthcare staff during shifts")

---

## How does it work?

### ABAC components

```
  ┌──────────────────────────────────────────────────────┐
  │                   ABAC Decision                       │
  │                                                       │
  │  Subject          Action         Resource    Context  │
  │  (Who?)           (What?)        (On what?)  (When?)  │
  │  ┌──────────┐   ┌──────────┐   ┌──────────┐ ┌──────┐│
  │  │ user_id  │   │ read     │   │ tenant_id│ │ time ││
  │  │ roles    │   │ write    │   │ owner_id │ │ ip   ││
  │  │ dept     │   │ delete   │   │ type     │ │ device│
  │  │ clearance│   │ approve  │   │ sensitiv.│ │ risk ││
  │  └──────────┘   └──────────┘   └──────────┘ └──────┘│
  │         │              │              │          │    │
  │         ▼              ▼              ▼          ▼    │
  │  ┌──────────────────────────────────────────────────┐│
  │  │              Policy Engine                        ││
  │  │                                                   ││
  │  │  IF subject.dept == "engineering"                  ││
  │  │  AND action == "read"                             ││
  │  │  AND resource.tenant_id == subject.tenant_id      ││
  │  │  AND context.time IN "09:00-18:00"                ││
  │  │  THEN → ALLOW                                     ││
  │  │  ELSE → DENY                                      ││
  │  └──────────────────────────────────────────────────┘│
  └──────────────────────────────────────────────────────┘
```

### Policy structure

A typical ABAC policy has four parts:

1. **Target**: Which requests does this policy apply to?
2. **Condition**: What attributes must be satisfied?
3. **Effect**: ALLOW or DENY
4. **Obligation**: Side effects (e.g., "log this access")

Example policy in volta's format:

```json
{
  "id": "policy-001",
  "name": "Engineering can read own tenant data",
  "target": {
    "action": ["read"],
    "resource_type": ["tenant_data"]
  },
  "condition": {
    "all": [
      {"subject.department": {"eq": "engineering"}},
      {"resource.tenant_id": {"eq": "${subject.tenant_id}"}},
      {"context.hour": {"between": [9, 18]}}
    ]
  },
  "effect": "ALLOW",
  "obligations": [
    {"type": "audit_log", "level": "info"}
  ]
}
```

### ABAC vs RBAC vs ACL

| Feature | ACL | RBAC | ABAC |
|---------|-----|------|------|
| Granularity | Per-resource | Per-role | Per-attribute |
| Context-aware | No | No | Yes |
| Scalability | Poor (N users x M resources) | Good | Excellent |
| Policy expressiveness | Low | Medium | High |
| Complexity | Low | Medium | High |
| Example | "Alice can read file X" | "Admins can read all files" | "Engineers can read files in their tenant during business hours" |

### Combining RBAC and ABAC

In practice, most systems combine both. volta uses roles as one attribute among many:

```
  ┌─────────────────────────────────────────────┐
  │  volta's hybrid approach:                    │
  │                                              │
  │  1. RBAC layer (fast, coarse):               │
  │     User has role ADMIN? → Allow most ops    │
  │     User has role MEMBER? → Check ABAC       │
  │                                              │
  │  2. ABAC layer (flexible, fine):             │
  │     Check tenant membership                  │
  │     Check resource ownership                 │
  │     Check time/IP/risk context               │
  │     Check custom attributes                  │
  │                                              │
  │  Result: Fast for common cases,              │
  │          precise for edge cases              │
  └─────────────────────────────────────────────┘
```

---

## How does volta-auth-proxy use it?

### The ABAC policy engine (Phase 4)

volta's Phase 4 adds an ABAC policy engine that evaluates policies at the proxy layer, before requests reach your [upstream](upstream.md) application:

```
  Request flow with ABAC:
  ┌──────────┐     ┌──────────────────────────────┐     ┌──────────┐
  │  Client  │────►│  volta-auth-proxy             │────►│ Upstream │
  │          │     │                               │     │  App     │
  │          │     │  1. Authenticate (JWT/Session) │     │          │
  │          │     │  2. Extract attributes          │     │          │
  │          │     │  3. Evaluate ABAC policies      │     │          │
  │          │     │  4. ALLOW → forward             │     │          │
  │          │     │     DENY  → 403 Forbidden       │     │          │
  └──────────┘     └──────────────────────────────────┘     └──────────┘
```

### Attribute sources in volta

| Attribute category | Source | Examples |
|-------------------|--------|----------|
| Subject (user) | [JWT](jwt.md) claims, user profile | `volta_roles`, `department`, `tenant_id` |
| Resource | Request path, [header](header.md)s | `/api/v1/tenants/{tid}/...`, content-type |
| Action | HTTP method | GET=read, POST=create, DELETE=delete |
| Context | Request metadata | Client IP, time, user-agent |

### Policy management API

```
  POST /api/v1/tenants/{tid}/policies
  {
    "name": "Members can only read own tenant",
    "target": {"action": ["read"]},
    "condition": {
      "all": [
        {"subject.volta_roles": {"contains": "MEMBER"}},
        {"resource.tenant_id": {"eq": "${subject.volta_tid}"}}
      ]
    },
    "effect": "ALLOW"
  }
```

### ABAC + existing volta features

ABAC policies can reference data from other volta features:

- **[Roles](role.md)**: `subject.volta_roles contains "ADMIN"`
- **[Tenant](tenant.md)**: `resource.tenant_id == subject.volta_tid`
- **[M2M](m2m.md)**: `subject.volta_client == true` (different rules for machines)
- **[Suspension](suspension.md)**: `subject.active == true` (auto-deny suspended users)

---

## Common mistakes and attacks

### Mistake 1: Overly complex policies

ABAC's power is also its danger. Policies with 10+ conditions are hard to audit and debug. Keep policies simple and compose them.

### Mistake 2: Not testing policy interactions

Two policies can conflict: one allows, another denies. Define a clear conflict resolution strategy (e.g., deny overrides allow).

### Mistake 3: Relying solely on client-side attributes

If attributes come from the client (e.g., a custom header claiming `department=admin`), they can be forged. Attributes should come from trusted sources: the [JWT](jwt.md), the database, or volta's own context.

### Mistake 4: No default deny

If no policy matches a request, what happens? Without a default deny, unmatched requests might slip through. Always configure a default-deny policy.

### Attack: Attribute injection

An attacker crafts a request with fake attributes (e.g., spoofed IP headers) to bypass policies. Defense: volta strips and re-derives attributes from trusted sources only.

### Attack: Policy tampering

If the policy store is not secured, an attacker could modify policies to grant themselves access. Defense: policy changes require ADMIN role, all changes are audit-logged, and policies are versioned.

---

## Further reading

- [NIST SP 800-162](https://csrc.nist.gov/publications/detail/sp/800-162/final) -- Guide to ABAC.
- [role.md](role.md) -- RBAC, which ABAC extends.
- [jwt.md](jwt.md) -- JWT claims as subject attributes.
- [tenant.md](tenant.md) -- Tenant isolation enforced by ABAC policies.
- [m2m.md](m2m.md) -- Machine identity attributes in ABAC.
