# Cross-Tenant Access

[日本語版はこちら](cross-tenant-access.ja.md)

---

## What is it?

Cross-tenant access is when a user in Tenant A can see, modify, or even know about data belonging to Tenant B. In a multi-tenant SaaS, this should **never** happen accidentally. It's the multi-tenant equivalent of reading someone else's mail.

---

## Why does it matter?

Cross-tenant data leaks are among the most damaging security incidents for a SaaS company:

- **Legal liability:** Violates data protection laws (GDPR, SOC 2, HIPAA)
- **Customer trust:** One incident can lose every customer who hears about it
- **Competitive exposure:** Company A's strategy visible to Company B (who might be a competitor)

Unlike typical bugs (broken button, wrong color), cross-tenant bugs have existential consequences for a SaaS business.

---

## A simple example

### The attack

```
User belongs to Tenant A (tenant_id = aaa)

1. User calls: GET /api/v1/tenants/bbb/members
                                    ^^^
                     (Tenant B's ID, not their own!)

2. Without protection: Server returns Tenant B's member list
3. With protection:    Server checks JWT's volta_tid != bbb -> 403 DENIED
```

### The subtle version

Even without direct API manipulation, cross-tenant access can happen through:
- **Search results** that include data from other tenants
- **Error messages** that leak tenant names or IDs
- **Shared caches** that serve the wrong tenant's data
- **Export/import** features that don't filter by tenant

---

## In volta-auth-proxy

volta prevents cross-tenant access through **structural enforcement** -- the check is built into the architecture, not left to individual developers:

### Layer 1: enforceTenantMatch()

Every tenant-scoped API endpoint calls this function, which compares the tenant ID from the URL path against the `volta_tid` in the user's JWT:

```java
private static void enforceTenantMatch(AuthPrincipal principal, UUID tenantId) {
    if (principal.serviceToken()) {
        return;  // Service tokens can access any tenant
    }
    if (!principal.tenantId().equals(tenantId)) {
        throw new ApiException(403, "TENANT_ACCESS_DENIED", "Tenant access denied");
    }
}
```

This is called on every tenant endpoint:
```java
app.get("/api/v1/tenants/{tenantId}/members", ctx -> {
    enforceTenantMatch(p, tenantId);  // <-- always here
    // ... rest of handler
});
```

### Layer 2: Query-level filtering

Even after the path check passes, every SQL query includes `WHERE tenant_id = ?` as an explicit filter. This provides defense-in-depth.

### Layer 3: JWT contains the tenant

The user's tenant is embedded in the JWT at login time. There is no way for a client to "switch" their tenant ID without getting a new JWT from the server, which requires a valid session in that tenant.

---

## See also

- [row-level-security.md](row-level-security.md) -- Database-level tenant isolation
- [tenant-resolution.md](tenant-resolution.md) -- How the right tenant is determined
