# Row-Level Security (RLS)

[日本語版はこちら](row-level-security.ja.md)

---

## What is it?

Row-Level Security (RLS) is a technique for ensuring that users can only see and modify data belonging to their own tenant (workspace, organization). Instead of relying on application code to always add `WHERE tenant_id = ?` to every query, you enforce the rule at the database level so it's impossible to forget.

Think of it like a building where every floor requires a different keycard. Even if someone gets into the building, they can only access their own floor. RLS makes the database work the same way.

---

## Why does it matter?

In a multi-tenant system, the worst possible bug is a **cross-tenant data leak** -- where User A sees User B's private data. This can happen when a single missing `WHERE tenant_id = ?` slips through code review.

Without RLS, you're relying on every developer, on every query, to never forget the filter. With RLS, the database itself enforces the boundary. Even if application code runs `SELECT * FROM documents`, the database automatically filters to only that tenant's rows.

---

## A simple example

### Application-level enforcement (without RLS)

```sql
-- Developer must ALWAYS remember to add tenant_id
SELECT * FROM invoices WHERE tenant_id = '7' AND status = 'unpaid';

-- If they forget:
SELECT * FROM invoices WHERE status = 'unpaid';
-- Returns ALL tenants' unpaid invoices!
```

### Database-level enforcement (Postgres RLS)

```sql
-- Enable RLS on the table
ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;

-- Create a policy
CREATE POLICY tenant_isolation ON invoices
  USING (tenant_id = current_setting('app.tenant_id')::uuid);

-- At the start of each request, set the tenant context
SET app.tenant_id = '7';

-- Now this query is automatically filtered
SELECT * FROM invoices WHERE status = 'unpaid';
-- Only returns tenant 7's unpaid invoices, guaranteed
```

---

## In volta-auth-proxy

volta currently uses **application-level enforcement** rather than Postgres RLS. Every query that touches tenant-scoped data includes an explicit `tenant_id` filter:

```sql
SELECT id, user_id, tenant_id, role, is_active
FROM memberships
WHERE tenant_id = ? AND id = ?
```

Additionally, volta's API layer enforces tenant isolation with `enforceTenantMatch()`, which checks that the `tenantId` in the URL path matches the `volta_tid` in the authenticated user's JWT:

```java
private static void enforceTenantMatch(AuthPrincipal principal, UUID tenantId) {
    if (!principal.tenantId().equals(tenantId)) {
        throw new ApiException(403, "TENANT_ACCESS_DENIED", "Tenant access denied");
    }
}
```

This provides two layers of defense (API path check + query filter) even without Postgres RLS. Adding RLS would create a third layer as a safety net for future development.

---

## See also

- [cross-tenant-access.md](cross-tenant-access.md) -- Why tenant isolation is critical
- [tenant-resolution.md](tenant-resolution.md) -- How volta determines which tenant a request belongs to
