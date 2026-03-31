# RBAC (Role-Based Access Control)

[日本語版はこちら](rbac.ja.md)

---

## What is it?

Role-Based Access Control (RBAC) is a way to manage permissions by assigning roles to users instead of giving permissions directly. Instead of saying "Taro can edit pages, create users, and view logs," you say "Taro is an ADMIN" and define what ADMINs can do. When you need to change permissions, you change the role definition, not every individual user.

Think of it like job titles at a company. Instead of listing every task each employee can do, you define roles (Manager, Engineer, Intern) and assign permissions to roles. When a new Engineer joins, they automatically get all Engineer permissions. When an Engineer is promoted to Manager, their permissions change automatically.

---

## Why does it matter?

Without RBAC:

- You manage permissions per user per resource -- this does not scale
- New employees get inconsistent permissions depending on who set them up
- Auditing "who can do what" requires checking every user individually
- Removing access when someone leaves requires remembering all their individual permissions

RBAC simplifies all of this. You manage a small number of roles, and users inherit permissions from their role.

---

## How does it work?

### The basic model

```
  ┌─────────────────────────────────────────────────────────┐
  │                       RBAC Model                         │
  │                                                          │
  │  Users ────── have ────── Roles ────── grant ────── Permissions │
  │                                                          │
  │  Taro ──────── ADMIN ──────── can manage members          │
  │                                can invite users           │
  │                                can change settings        │
  │                                                          │
  │  Hanako ────── MEMBER ─────── can view content            │
  │                                can edit own content       │
  │                                                          │
  │  Guest ──────── VIEWER ────── can view content only       │
  └─────────────────────────────────────────────────────────┘
```

### volta's 4-role hierarchy

volta uses a simple, ordered hierarchy of 4 roles:

```
  OWNER > ADMIN > MEMBER > VIEWER

  ┌─────────┐
  │  OWNER  │  Can do everything. Delete tenant, transfer ownership.
  │         │  One tenant must always have at least one OWNER.
  ├─────────┤
  │  ADMIN  │  Can manage people: invite members, change roles (up to ADMIN),
  │         │  remove members, change tenant settings.
  ├─────────┤
  │  MEMBER │  Normal usage. Can use all apps that allow MEMBER role.
  │         │  Cannot manage other users.
  ├─────────┤
  │  VIEWER │  Read-only access. Can view content but not modify it.
  │         │  Enforcement is up to individual apps.
  └─────────┘
```

### Role hierarchy means "higher includes lower"

```
  If an app requires MEMBER role:

  OWNER  → ✓ (OWNER > ADMIN > MEMBER, so OWNER includes MEMBER)
  ADMIN  → ✓ (ADMIN > MEMBER, so ADMIN includes MEMBER)
  MEMBER → ✓ (exact match)
  VIEWER → ✗ (VIEWER < MEMBER)
```

### Tenant-scoped roles

In volta, roles are **per tenant**, not global. A user can have different roles in different tenants:

```
  User: taro@example.com

  ┌─────────────────────────────────┐
  │ Tenant: ACME Corp               │
  │ Role: OWNER                     │
  │                                 │
  │ Taro created ACME. He owns it.  │
  │ Full control over everything.   │
  └─────────────────────────────────┘

  ┌─────────────────────────────────┐
  │ Tenant: Globex Inc              │
  │ Role: MEMBER                    │
  │                                 │
  │ Taro was invited to Globex.     │
  │ He can use apps but cannot      │
  │ manage members.                 │
  └─────────────────────────────────┘

  ┌─────────────────────────────────┐
  │ Tenant: Initech                 │
  │ Role: VIEWER                    │
  │                                 │
  │ Taro has read-only access.      │
  │ He can view but not edit.       │
  └─────────────────────────────────┘
```

This is stored in the `memberships` table:

```sql
-- Taro's memberships
SELECT tenant_id, role FROM memberships WHERE user_id = 'taro-uuid';

-- Result:
-- acme-uuid    | OWNER
-- globex-uuid  | MEMBER
-- initech-uuid | VIEWER
```

---

## How does volta-auth-proxy use RBAC?

### Role in the JWT

When volta issues a JWT, the user's role for the current tenant is included:

```json
{
  "sub": "taro-uuid",
  "volta_tid": "acme-uuid",
  "volta_roles": ["OWNER"],
  "volta_tname": "ACME Corp"
}
```

Apps read `volta_roles` to make authorization decisions:

```java
app.delete("/api/projects/:id", ctx -> {
    VoltaUser user = VoltaAuth.getUser(ctx);
    if (!user.hasRole("ADMIN")) {
        throw new ForbiddenResponse("Only ADMIN or OWNER can delete projects");
    }
    // ... delete the project
});
```

### Role in ForwardAuth

In `volta-config.yaml`, each app specifies which roles can access it:

```yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]

  - id: app-admin
    url: https://admin.example.com
    allowed_roles: [ADMIN, OWNER]
```

ForwardAuth (`/auth/verify`) checks the user's role against the app's `allowed_roles` before allowing access. This means VIEWERs cannot even reach app-admin -- they are blocked at the gateway level.

### Role management

Roles are managed through:

1. **Admin UI:** `/admin/members` -- visual interface for changing roles
2. **Internal API:** `PATCH /api/v1/tenants/{tid}/members/{uid}` -- programmatic role changes
3. **Invitations:** When creating an invitation, the ADMIN specifies what role the invitee will receive

### OWNER protection

volta has a critical safety rule: the **last OWNER of a tenant cannot be demoted or removed**. This prevents the situation where a tenant has no one who can manage it.

```
  Tenant: ACME Corp
  Members:
    Taro (OWNER)  ← last OWNER
    Hanako (ADMIN)

  Taro tries to demote himself to MEMBER:
  → volta: "Cannot demote. You are the last OWNER.
            Transfer ownership to someone else first."

  Hanako tries to remove Taro:
  → volta: "Cannot remove the last OWNER."
```

### Role change rules

| Actor | Can change target to | Constraints |
|-------|---------------------|-------------|
| OWNER | OWNER, ADMIN, MEMBER, VIEWER | Can promote to OWNER (transfers ownership) |
| ADMIN | ADMIN, MEMBER, VIEWER | Cannot promote to OWNER. Cannot demote other ADMINs. |
| MEMBER | (cannot change roles) | |
| VIEWER | (cannot change roles) | |

---

## Common mistakes and attacks

### Mistake 1: Checking roles only in the UI

If the role check only happens in the frontend (e.g., hiding a button), an attacker can call the API directly. Always check roles on the server side.

```
  BAD:  Frontend hides "Delete" button for non-admins.
        API endpoint has no role check.
        → Attacker calls DELETE /api/projects/1 directly.

  GOOD: Frontend hides the button AND
        API endpoint checks user.hasRole("ADMIN").
```

### Mistake 2: Role hierarchy not enforced consistently

If some endpoints check `role == "ADMIN"` (exact match) and others check `role >= "ADMIN"` (hierarchy), an OWNER might be blocked from admin endpoints. Always use hierarchical comparison.

### Mistake 3: Not logging role changes

Role changes are security-sensitive operations. If an attacker promotes themselves to OWNER, you need an audit trail. volta logs every role change to the `audit_logs` table.

### Attack: Privilege escalation

An attacker finds a way to change their own role (e.g., a bug in the role change API that does not verify the actor's permission). Defense: always verify that the actor has the right to perform the role change, not just that the request is authenticated.

### Attack: Confused deputy

An app that runs as ADMIN (via service token) might be tricked into performing actions on behalf of a VIEWER. Defense: when delegating operations, always check the original user's role, not the service token's role.

---

## Further reading

- [tenant.md](tenant.md) -- Tenant isolation and multi-tenancy.
- [forwardauth.md](forwardauth.md) -- How roles are enforced at the gateway.
- [jwt.md](jwt.md) -- How roles appear in JWT claims.
- [NIST RBAC Model](https://csrc.nist.gov/projects/role-based-access-control) -- The formal RBAC specification.
