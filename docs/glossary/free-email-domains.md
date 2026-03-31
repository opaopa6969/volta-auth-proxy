# Free Email Domains

[日本語版はこちら](free-email-domains.ja.md)

---

## What is it?

Free email domains are domains like `gmail.com`, `yahoo.com`, `outlook.com`, and `hotmail.com` that anyone can sign up for. The "free email domain problem" arises when a SaaS product tries to automatically group users into tenants based on their email domain.

The idea sounds reasonable: "All users with `@acme.com` emails belong to the Acme tenant." But it falls apart when someone signs up with `@gmail.com` -- you obviously can't put all Gmail users into the same tenant.

---

## Why does it matter?

Many SaaS products use email domain matching for automatic tenant assignment:

```
jane@acme.com   -> auto-join "Acme Corp" tenant    (correct!)
bob@acme.com    -> auto-join "Acme Corp" tenant     (correct!)
alice@gmail.com -> auto-join "Gmail" tenant??        (WRONG!)
```

If you don't handle free email domains, you get:
- **Privacy violation:** Strangers grouped into the same tenant, seeing each other's data
- **Security breach:** An attacker signs up with `@gmail.com` and sees other Gmail users' data
- **Broken UX:** A user joins a giant "Gmail" tenant with thousands of strangers

---

## A simple example

### The problem

```
Tenant auto-join rule: "Same email domain = same tenant"

User 1: alice@acme.com     -> Tenant "Acme Corp"     OK
User 2: bob@acme.com       -> Tenant "Acme Corp"     OK
User 3: charlie@gmail.com  -> Tenant "Gmail"          BAD
User 4: diana@gmail.com    -> Tenant "Gmail"          BAD
  (Charlie and Diana are strangers but now share a tenant!)
```

### The solution

Maintain a blocklist of free email domains. When a user signs up with a free email domain, don't auto-assign them to a shared tenant. Instead, create a personal workspace or require an invitation.

Common free email domains include:
`gmail.com`, `yahoo.com`, `yahoo.co.jp`, `outlook.com`, `hotmail.com`, `icloud.com`, `protonmail.com`, `mail.com`, and hundreds more.

---

## In volta-auth-proxy

volta avoids the free email domain problem entirely through its tenant resolution design:

1. **No email-domain-based tenant matching.** volta never automatically groups users by email domain.
2. **Invitation-based joining.** To join an existing tenant, users must have a valid invitation link.
3. **Personal tenant by default.** If a user has no invitations and no existing memberships, volta creates a personal workspace just for them.

```java
// From resolveTenant():
if (inviteCode != null) {
    // Join the invitation's tenant (explicit, invitation-based)
    return store.findTenantById(invitation.tenantId()).orElseThrow();
}
if (tenants.isEmpty()) {
    // Create personal tenant (never auto-join by email domain)
    return store.createPersonalTenant(user);
}
```

This means `alice@gmail.com` and `bob@gmail.com` each get their own separate personal workspace. They can only share a tenant if one explicitly invites the other. This is the safest approach and eliminates the free email domain problem by design.

---

## See also

- [tenant-resolution.md](tenant-resolution.md) -- How volta determines which tenant a user belongs to
- [cross-tenant-access.md](cross-tenant-access.md) -- Why accidental grouping is dangerous
