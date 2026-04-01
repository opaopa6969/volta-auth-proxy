# Membership

[日本語版はこちら](membership.ja.md)

---

## What is it?

A membership is the relationship between a user and a [tenant](tenant.md) (workspace). It records that a specific person belongs to a specific organization, what [role](role.md) they have, and whether they are currently active. A user can have memberships in multiple tenants, but each membership is independent -- you might be an ADMIN in one workspace and a VIEWER in another.

Think of it like club memberships in real life. You can be a member of a gym, a book club, and a professional association all at the same time. Each membership has its own status (active/inactive), its own level (standard/premium), and is managed independently. Getting kicked out of the gym does not affect your book club membership.

In volta-auth-proxy, memberships are stored in the `tenant_members` table with fields for `user_id`, `tenant_id`, `role`, and `is_active`. The `MembershipRecord` Java record represents this relationship in code.

---

## Why does it matter?

Memberships are the foundation of multi-tenant authorization. Without them:

- **No tenant isolation**: You cannot determine which users belong to which workspaces
- **No role assignment**: Roles exist in the abstract, but nobody has one
- **No access control**: ForwardAuth cannot check `allowed_roles` if users have no roles
- **No invitation flow**: Invitations create memberships -- without the concept, the flow has no endpoint
- **No audit trail**: "User X did Y in Tenant Z" requires knowing that X is a member of Z

---

## How does it work?

### The membership data model

```
  ┌─────────────────────────────────────────────────────┐
  │ tenant_members table                                │
  │                                                     │
  │ id:         uuid (primary key)                      │
  │ user_id:    uuid → users.id                         │
  │ tenant_id:  uuid → tenants.id                       │
  │ role:       enum [OWNER, ADMIN, MEMBER, VIEWER]     │
  │ is_active:  boolean                                 │
  │ joined_at:  timestamp                               │
  │ invited_by: uuid → users.id (nullable)              │
  │                                                     │
  │ UNIQUE constraint: (user_id, tenant_id)             │
  │ A user can only have ONE membership per tenant.     │
  └─────────────────────────────────────────────────────┘
```

### Many-to-many relationship

```
  Users                  Memberships              Tenants
  ┌──────────┐          ┌──────────────┐         ┌──────────┐
  │ Alice    │─────────►│ ADMIN        │◄────────│ Acme     │
  │          │          └──────────────┘         │          │
  │          │─────────►│ VIEWER       │◄────────│ Side LLC │
  └──────────┘          └──────────────┘         └──────────┘
  ┌──────────┐          ┌──────────────┐
  │ Bob      │─────────►│ OWNER        │◄────────┐
  │          │          └──────────────┘         │ Acme
  │          │─────────►│ MEMBER       │◄────────┘
  └──────────┘          └──────────────┘         ┌──────────┐
                        │ MEMBER       │◄────────│ OpenOrg  │
                        └──────────────┘         └──────────┘
```

### Membership lifecycle

```
  ┌──────────────┐    Invitation     ┌──────────────┐
  │ No membership│ ─────────────────►│ Active member│
  │              │    accepted        │ (is_active=  │
  └──────────────┘                   │  true)       │
                                     └──────┬───────┘
                                            │
                              ┌──────────────┤
                              │              │
                         Role change    Removed by admin
                              │              │
                              ▼              ▼
                     ┌──────────────┐ ┌──────────────┐
                     │ Active member│ │ Deactivated  │
                     │ (new role)   │ │ (is_active=  │
                     └──────────────┘ │  false)       │
                                      └──────────────┘
```

---

## How does volta-auth-proxy use it?

### MembershipRecord in Models.java

```java
record MembershipRecord(
    UUID id,
    UUID userId,
    UUID tenantId,
    String role,
    boolean active
) {}
```

### Creating memberships via invitation

Memberships are NOT created directly. They are created when a user accepts an [invitation](invitation-flow.md):

```yaml
# auth-machine.yaml — INVITE_CONSENT state
accept:
  trigger: "POST /invite/{code}/accept"
  guard: "invite.valid && !invite.expired && !invite.used && invite.email_match"
  actions:
    - { type: guard_check, check: csrf_token_valid }
    - { type: side_effect, action: create_membership }   # ← membership created here
    - { type: side_effect, action: consume_invitation }
    - { type: side_effect, action: set_session_tenant }
    - { type: audit, event: INVITATION_ACCEPTED }
    - { type: audit, event: TENANT_JOINED }
  next: AUTHENTICATED
```

### Membership checks in guards

The state machine uses `membership.*` context variables:

```yaml
# Does the user have a membership in the target tenant?
guard: "membership.exists && membership.active && tenant.active"

# Is the user an ADMIN or higher?
guard: "membership.role in ['ADMIN', 'OWNER']"
```

### Session-tenant binding

A [session](session.md) is bound to exactly one tenant at a time. When a user switches tenants, the session's `tenant_id` is updated (actually a new session is created):

```yaml
# policy.yaml
- id: session_tenant_bound
  rule: "A session is bound to exactly one tenant at a time"
  enforcement: "session.tenant_id is set. Changing tenant creates new session"
```

### Membership in ForwardAuth response

When ForwardAuth succeeds, the user's role from their membership is included in the response headers:

```yaml
# protocol.yaml
X-Volta-Roles:
  type: csv
  source: membership.roles
  example: "ADMIN,MEMBER"
```

### Constraints on memberships

```yaml
# policy.yaml constraints
- id: last_owner
  rule: "A tenant MUST have at least one OWNER at all times"
  error: "LAST_OWNER_CANNOT_CHANGE"

- id: max_tenants
  rule: "A user can belong to at most MAX_TENANTS_PER_USER tenants"
  default: 10
  error: "MAX_TENANTS_REACHED"

- id: max_members
  rule: "A tenant can have at most tenant.max_members members"
  default: 50
  error: "MAX_MEMBERS_REACHED"
```

### Membership CRUD via Internal API

```yaml
# protocol.yaml
GET    /tenants/{tenantId}/members           # List (MEMBER+)
GET    /tenants/{tenantId}/members/{id}      # Get one (MEMBER+)
PATCH  /tenants/{tenantId}/members/{id}      # Change role (ADMIN+)
DELETE /tenants/{tenantId}/members/{id}      # Remove/deactivate (ADMIN+)
```

### Soft delete

When a member is removed, `is_active` is set to `false`. The record is not deleted:

```
  Before removal:  { user_id: alice, tenant_id: acme, role: MEMBER, is_active: true }
  After removal:   { user_id: alice, tenant_id: acme, role: MEMBER, is_active: false }
  Also:            All sessions for alice in acme are invalidated
```

This preserves audit history and allows potential reactivation.

---

## Common mistakes and attacks

### Mistake 1: No unique constraint on (user_id, tenant_id)

Without this constraint, a user could have multiple memberships in the same tenant with different roles. This creates ambiguity: which role applies? volta enforces uniqueness at the database level.

### Mistake 2: Hard deleting memberships

Deleting the row loses the record of who was a member and when. volta soft-deletes by setting `is_active = false`.

### Mistake 3: Not invalidating sessions on removal

If you remove a member but their session still has the old `tenant_id`, they can continue accessing the tenant until the session expires. volta invalidates all sessions for the user in that tenant immediately upon removal.

### Attack: Membership enumeration

An attacker might try to list members of tenants they do not belong to. volta's tenant isolation [enforcement](enforcement.md) ensures that `GET /tenants/{tenantId}/members` only works when the JWT's `volta_tid` matches `{tenantId}`.

### Attack: Role manipulation

An attacker might try to change their own role by calling `PATCH /members/{self}` with `role: OWNER`. volta's [hierarchy](hierarchy.md) constraint prevents promoting above your own role, and OWNER transfer is a separate privileged endpoint.

---

## Further reading

- [tenant.md](tenant.md) -- The organizations that memberships belong to.
- [role.md](role.md) -- The roles assigned via memberships.
- [hierarchy.md](hierarchy.md) -- How roles are ordered.
- [invitation-flow.md](invitation-flow.md) -- How memberships are created.
- [rbac.md](rbac.md) -- How memberships enable role-based access control.
- [session.md](session.md) -- How sessions are bound to memberships.
- [audit-log.md](audit-log.md) -- Membership changes logged for audit.
- [crud.md](crud.md) -- CRUD operations on memberships.
