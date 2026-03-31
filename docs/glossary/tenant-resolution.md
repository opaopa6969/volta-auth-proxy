# Tenant Resolution

[日本語版はこちら](tenant-resolution.ja.md)

---

## What is it?

Tenant resolution is the process of figuring out which tenant (workspace, organization) a request belongs to. When a user hits your API, you need to answer: "Which tenant's data should this request access?" The answer determines everything -- which data they see, which permissions apply, which billing account is charged.

Think of it like a hotel receptionist determining which room you belong to. They might check your reservation name, your room key, or your ID -- different signals that all point to the same room.

---

## Why does it matter?

If tenant resolution is wrong, the consequences are severe. A user could see another tenant's data, modify another tenant's settings, or get billed to the wrong account. Every request must resolve to exactly one tenant with 100% certainty.

---

## A simple example

Common tenant resolution strategies:

| Strategy | How it works | Example |
|----------|-------------|---------|
| **Subdomain** | Parse tenant from URL | `acme.myapp.com` -> tenant "acme" |
| **Path prefix** | Tenant ID in URL path | `/tenants/abc-123/members` -> tenant "abc-123" |
| **JWT claim** | Read tenant from token | `volta_tid: "abc-123"` in JWT |
| **Request header** | Custom header | `X-Tenant-ID: abc-123` |
| **User lookup** | Look up the user's tenant in DB | User 42 belongs to tenant "abc-123" |

Each approach has tradeoffs around simplicity, security, and flexibility.

---

## In volta-auth-proxy

volta uses a **multi-level resolution** strategy during login. When a new user logs in, volta determines their tenant through a priority chain:

### Resolution priority (at login time)

1. **Invitation code** (highest priority): If the login URL contains an invite code, the user joins that invitation's tenant.
2. **Existing membership**: If the user already belongs to tenant(s), use the first one found.
3. **Auto-create personal tenant** (lowest priority): If the user has no tenants at all, create a personal workspace.

```java
private static TenantRecord resolveTenant(SqlStore store, UserRecord user, String inviteCode) {
    if (inviteCode != null) {
        // Priority 1: Invitation determines tenant
        InvitationRecord invitation = store.findInvitationByCode(inviteCode).orElseThrow();
        return store.findTenantById(invitation.tenantId()).orElseThrow();
    }
    List<TenantRecord> tenants = store.findTenantsByUser(user.id());
    if (tenants.isEmpty()) {
        // Priority 3: Create personal tenant
        return store.createPersonalTenant(user);
    }
    // Priority 2: Use first existing tenant
    return tenants.getFirst();
}
```

### Resolution for API requests (after login)

Once logged in, tenant resolution is straightforward: the session stores `tenant_id`, and the JWT contains `volta_tid`. For API calls, the tenant ID in the URL path must match the JWT's `volta_tid` -- enforced by `enforceTenantMatch()`.

---

## See also

- [cross-tenant-access.md](cross-tenant-access.md) -- What happens if resolution is bypassed
- [row-level-security.md](row-level-security.md) -- How queries enforce the resolved tenant
