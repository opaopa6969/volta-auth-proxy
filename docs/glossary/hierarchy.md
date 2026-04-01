# Hierarchy

[日本語版はこちら](hierarchy.ja.md)

---

## What is it?

A hierarchy is a system of ordered levels where higher levels include the capabilities of lower levels. In access control, a role hierarchy means that a user with a higher role automatically has all the permissions of every role below them. You do not need to assign permissions individually -- they are inherited through the hierarchy.

Think of it like military ranks. A General can do everything a Colonel can do, a Colonel can do everything a Captain can do, and so on. You do not give the General a separate list of "Captain permissions" -- they get them automatically by being above Captain in the chain. That is hierarchy-based inheritance.

volta-auth-proxy defines a 4-level role [hierarchy](role.md): OWNER > ADMIN > MEMBER > VIEWER. Each role inherits all permissions of the roles below it.

---

## Why does it matter?

Without hierarchy, you need to explicitly assign every permission to every role:

- **Explosion of assignments**: 4 roles x 20 permissions = 80 individual assignments to maintain
- **Inconsistency risk**: If you add a new permission to MEMBER but forget to add it to ADMIN, ADMINs have fewer permissions than MEMBERs
- **Difficult auditing**: To see what ADMIN can do, you must look at every individual permission assignment

With hierarchy:

- **Inheritance**: Adding a permission to MEMBER automatically gives it to ADMIN and OWNER too
- **Simple mental model**: "ADMIN can do everything MEMBER can, plus more"
- **Easy auditing**: Each role only lists its OWN unique permissions

---

## How does it work?

### Role inheritance

```
  OWNER (highest)
  │
  │ inherits everything from ADMIN, plus:
  │   - delete_tenant
  │   - transfer_ownership
  │   - manage_signing_keys
  │   - change_tenant_slug
  │
  └── ADMIN
      │
      │ inherits everything from MEMBER, plus:
      │   - invite_members
      │   - remove_members
      │   - change_member_role
      │   - view_audit_logs
      │
      └── MEMBER
          │
          │ inherits everything from VIEWER, plus:
          │   - use_apps
          │   - manage_own_sessions
          │   - switch_tenant
          │   - accept_invitation
          │
          └── VIEWER (lowest)
              │
              │ base permissions:
              │   - read_only
              └──
```

### Effective permissions

The effective permissions for a role are its own permissions PLUS all inherited permissions:

```
  ┌─────────┬────────────────────────────────────────────────┐
  │ Role    │ Effective permissions                          │
  │─────────│────────────────────────────────────────────────│
  │ VIEWER  │ read_only                                     │
  │ MEMBER  │ read_only + use_apps + manage_sessions + ...  │
  │ ADMIN   │ all MEMBER perms + invite + remove + audit    │
  │ OWNER   │ all ADMIN perms + delete_tenant + transfer    │
  └─────────┴────────────────────────────────────────────────┘
```

### Hierarchy comparison operator

volta uses `>=` to check hierarchy. "ADMIN+" means "ADMIN or higher":

```
  OWNER  >= ADMIN?   → true  (OWNER is above ADMIN)
  ADMIN  >= ADMIN?   → true  (same level)
  MEMBER >= ADMIN?   → false (MEMBER is below ADMIN)
  VIEWER >= ADMIN?   → false (VIEWER is below ADMIN)
```

---

## How does volta-auth-proxy use it?

### Defined in `dsl/policy.yaml`

```yaml
roles:
  hierarchy:
    - OWNER     # highest
    - ADMIN
    - MEMBER
    - VIEWER    # lowest

permissions:
  OWNER:
    inherits: ADMIN
    can:
      - delete_tenant
      - transfer_ownership
      - manage_signing_keys
  ADMIN:
    inherits: MEMBER
    can:
      - invite_members
      - remove_members
      - change_member_role
      - view_audit_logs
  MEMBER:
    inherits: VIEWER
    can:
      - use_apps
      - manage_own_sessions
      - switch_tenant
  VIEWER:
    can:
      - read_only
```

### ForwardAuth app access

Each app defines `allowed_roles` in `volta-config.yaml`. The hierarchy determines access:

```yaml
apps:
  - id: app-wiki
    allowed_roles: [MEMBER, ADMIN, OWNER]    # MEMBER+
  - id: app-admin
    allowed_roles: [ADMIN, OWNER]            # ADMIN+
  - id: app-billing
    allowed_roles: [OWNER]                   # OWNER only
```

### API endpoint authorization

`protocol.yaml` uses hierarchy notation like "ADMIN+" to mean "ADMIN or any role above ADMIN":

```yaml
- method: GET
  path: /tenants/{tenantId}/members
  auth: "MEMBER+"        # MEMBER, ADMIN, or OWNER

- method: PATCH
  path: /tenants/{tenantId}/members/{memberId}
  auth: "ADMIN+"         # ADMIN or OWNER only

- method: POST
  path: /tenants/{tenantId}/transfer-ownership
  auth: "OWNER"          # OWNER only, no inheritance
```

### Promotion limits

The hierarchy enforces that users cannot promote others above their own level:

```yaml
# policy.yaml constraint
- id: promote_limit
  rule: "A user cannot promote another user above their own role"
  enforcement: "ADMIN can promote to ADMIN max. Only OWNER can promote to OWNER"
```

```
  ADMIN tries to promote MEMBER to OWNER?
  ├── ADMIN < OWNER → DENIED
  │
  ADMIN tries to promote MEMBER to ADMIN?
  ├── ADMIN >= ADMIN → ALLOWED
  │
  OWNER tries to promote MEMBER to OWNER?
  ├── This is "transfer ownership", a special operation
  └── Requires POST /tenants/{id}/transfer-ownership
```

### Guard expressions using hierarchy

```yaml
# auth-machine.yaml
show_members:
  trigger: "GET /admin/members"
  guard: "membership.role in ['ADMIN', 'OWNER']"   # ADMIN+
  next: AUTHENTICATED
```

---

## Common mistakes and attacks

### Mistake 1: Flat role systems

Treating ADMIN and MEMBER as unrelated roles means ADMIN does not automatically get MEMBER permissions. Every new MEMBER permission must be manually added to ADMIN too. Hierarchy eliminates this maintenance burden.

### Mistake 2: Allowing upward promotion

If ADMIN can promote users to OWNER, the hierarchy collapses -- any ADMIN can escalate to full control. volta enforces that you can only promote up to your own level.

### Mistake 3: No "last OWNER" protection

If the last OWNER is demoted to MEMBER, nobody can perform OWNER-level operations like deleting the tenant. volta's `last_owner` constraint prevents this.

### Attack: Privilege escalation via self-promotion

An ADMIN tries to change their own role to OWNER via `PATCH /members/{self}`. volta prevents this because the `promote_limit` constraint blocks promoting above your own role, and OWNER transfer is a separate endpoint requiring existing OWNER authorization.

---

## Further reading

- [role.md](role.md) -- The 4 roles in volta's hierarchy.
- [rbac.md](rbac.md) -- How roles map to permissions.
- [policy-engine.md](policy-engine.md) -- Phase 4 external policy evaluation.
- [enforcement.md](enforcement.md) -- How the hierarchy is enforced at runtime.
- [membership.md](membership.md) -- Where role assignments are stored.
- [invitation-flow.md](invitation-flow.md) -- How roles are assigned during invitation.
