# Tenant Lifecycle

[日本語版はこちら](tenant-lifecycle.ja.md)

---

## What is it?

Tenant lifecycle refers to the stages a tenant (workspace/organization) goes through from creation to deletion. Like an employee who is hired, might go on leave, and eventually departs, a tenant can be active, suspended, or deleted -- and each state change has consequences for the people inside it.

---

## Why does it matter?

Getting the lifecycle wrong can cause serious problems:
- **Create without guardrails:** Spam tenants, resource exhaustion
- **Suspend without communication:** Users locked out with no explanation
- **Delete without data handling:** Permanent data loss, legal liability (GDPR requires data retention periods)

Each transition needs clear rules about what happens to members, sessions, data, and billing.

---

## A simple example

```
[Created] ──── normal usage ────> [Active]
                                     |
                           admin suspends
                                     |
                                     v
                                [Suspended]
                                     |
                         admin reactivates
                         or admin deletes
                           /            \
                          v              v
                     [Active]       [Deleted]
```

### What happens to members when a tenant is suspended?

| Aspect | What should happen |
|--------|--------------------|
| **Login** | Members can log in but can't access the workspace |
| **Sessions** | Existing sessions may remain but access is denied |
| **Data** | Preserved but read-only (or fully blocked) |
| **Billing** | Typically paused or switched to a free plan |
| **Notifications** | Members should receive an explanation |

---

## In volta-auth-proxy

volta supports three lifecycle operations:

### Create

Tenants are created either automatically (personal tenant on first login) or manually via the API. Every tenant gets a `name`, `slug`, and `is_active = true`.

### Suspend

Owners can suspend a tenant via:
```
POST /api/v1/admin/tenants/{tenantId}/suspend
```

This sets `is_active = false` on the tenant record. volta also enqueues a `tenant.suspended` outbox event so downstream services can react:

```java
store.setTenantActive(tenantId, false);
store.enqueueOutboxEvent(tenantId, "tenant.suspended",
    "{\"tenant_id\":\"" + tenantId + "\"}");
```

When a suspended tenant's member tries to access resources, volta returns `TENANT_SUSPENDED` with a user-friendly error page that directs them to switch workspaces or contact support.

### Reactivate

```
POST /api/v1/admin/tenants/{tenantId}/activate
```

Sets `is_active = true`. Members can immediately access the workspace again.

### Delete

volta does not currently implement hard deletion of tenants. This is intentional -- tenant deletion requires careful handling of data retention policies, audit log preservation, and member notification. It's a Phase 2+ feature.

---

## See also

- [cross-tenant-access.md](cross-tenant-access.md) -- Isolation between active tenants
- [tenant-resolution.md](tenant-resolution.md) -- How users end up in a specific tenant
