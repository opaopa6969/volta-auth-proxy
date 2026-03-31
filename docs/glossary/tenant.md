# Tenant / Multi-tenancy

[日本語版はこちら](tenant.ja.md)

---

## What is it?

A tenant is a group of users who share a workspace in a SaaS application. If you use Slack, your company's Slack workspace is a tenant. Each tenant has its own data, members, and settings, completely isolated from other tenants. Multi-tenancy means one application serves many tenants simultaneously.

Think of it like an apartment building. Each apartment (tenant) is a private space with its own lock and key. Tenants share the building infrastructure (elevator, plumbing, electricity), but they cannot enter each other's apartments. The building management (volta-auth-proxy) controls who gets keys to which apartments.

---

## Why does it matter?

If you are building a SaaS product, you need multi-tenancy. Without it:

- One customer could see another customer's data
- There is no way to manage billing, plans, or limits per customer
- You cannot offer per-organization settings (like MFA requirements)
- You would need a separate deployment for each customer (expensive, hard to maintain)

Multi-tenancy is the architectural foundation that makes SaaS possible. Getting it wrong leads to data breaches, where Customer A sees Customer B's data -- one of the most damaging incidents a SaaS company can have.

---

## How does it work?

### Data isolation strategies

There are three main approaches to keeping tenant data separate:

```
  Strategy 1: Row-Level Isolation (what volta uses)
  ═════════════════════════════════════════════════

  One database, one schema, all tenants share the same tables.
  Every row has a tenant_id column.

  ┌─────────────────────────────────────────────┐
  │  items table                                 │
  │  ┌────────────┬───────────┬────────────────┐ │
  │  │ tenant_id  │ id        │ data           │ │
  │  ├────────────┼───────────┼────────────────┤ │
  │  │ acme-uuid  │ item-1    │ ACME's data    │ │
  │  │ acme-uuid  │ item-2    │ ACME's data    │ │
  │  │ globex-uuid│ item-3    │ Globex's data  │ │
  │  │ globex-uuid│ item-4    │ Globex's data  │ │
  │  └────────────┴───────────┴────────────────┘ │
  └─────────────────────────────────────────────┘

  Every query MUST include WHERE tenant_id = ?
  If you forget, you leak data across tenants.

  Pros: Simple, efficient, easy to manage
  Cons: One bug = data leak, no physical isolation


  Strategy 2: Schema-Level Isolation
  ═══════════════════════════════════

  One database, separate schema per tenant.

  ┌──────────────────────────────────┐
  │  Database: volta_db               │
  │                                   │
  │  Schema: acme                     │
  │  ┌──────────────────────────┐    │
  │  │ items: ACME's data only  │    │
  │  └──────────────────────────┘    │
  │                                   │
  │  Schema: globex                   │
  │  ┌──────────────────────────┐    │
  │  │ items: Globex's data only│    │
  │  └──────────────────────────┘    │
  └──────────────────────────────────┘

  Pros: Stronger isolation, easier data export/deletion
  Cons: Schema migrations run N times, connection management complex


  Strategy 3: Database-Level Isolation
  ═════════════════════════════════════

  Separate database per tenant.

  ┌─────────────────┐  ┌─────────────────┐
  │ DB: acme_db      │  │ DB: globex_db    │
  │ items: ACME data │  │ items: Globex    │
  └─────────────────┘  └─────────────────┘

  Pros: Strongest isolation, easy per-tenant backup/restore
  Cons: Expensive, hard to manage at scale, cross-tenant queries impossible
```

volta uses **row-level isolation**. This is the most common choice for SaaS applications because it is the simplest and scales well. The trade-off is that application code must always include tenant_id in queries.

### Tenant resolution (how volta determines which tenant)

When a request comes in, volta needs to figure out which tenant the user is accessing. It uses a 4-level priority:

```
  Priority 1: Session cookie (highest priority)
  ─────────────────────────────────────────────
  If the user has an active session with a tenant_id,
  use that tenant. This handles the common case.

  Priority 2: URL subdomain
  ─────────────────────────
  If the URL is wiki.acme.example.com, look up "acme"
  in the tenant_domains table.

  Priority 3: Email domain
  ─────────────────────────
  If the user logs in with taro@acme.com, look up
  "acme.com" in the tenant_domains table.

  Exception: "free email" domains are excluded:
  gmail.com, outlook.com, yahoo.com, yahoo.co.jp,
  hotmail.com, icloud.com, protonmail.com

  Priority 4: Manual selection / invitation
  ──────────────────────────────────────────
  If none of the above match, show the user:
  - An invitation code input screen
  - Or a tenant selection screen (if they belong to multiple)
```

### Multiple tenant membership

volta allows one user to belong to multiple tenants with different roles:

```
  User: taro@example.com

  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
  │ Tenant: ACME     │  │ Tenant: Globex   │  │ Tenant: Initech  │
  │ Role: OWNER      │  │ Role: MEMBER     │  │ Role: VIEWER     │
  └──────────────────┘  └──────────────────┘  └──────────────────┘

  Taro can switch between tenants via:
  - GET /select-tenant (UI)
  - POST /auth/switch-tenant (API)
  - Volta.switchTenant("globex-uuid") (SDK)
```

---

## How does volta-auth-proxy use tenants?

### Tenant data model

```
  tenants table:
  ┌─────────────────────────────────────────────┐
  │  id:       UUID                              │
  │  name:     "ACME Corp"                       │
  │  slug:     "acme" (URL-friendly identifier)  │
  │  plan:     "pro" (for future billing)        │
  │  status:   "active" | "suspended"            │
  └─────────────────────────────────────────────┘

  tenant_domains table:
  ┌─────────────────────────────────────────────┐
  │  tenant_id:  acme-uuid                       │
  │  domain:     "acme.com"                      │
  │  verified:   true                            │
  └─────────────────────────────────────────────┘

  memberships table:
  ┌─────────────────────────────────────────────┐
  │  user_id:    taro-uuid                       │
  │  tenant_id:  acme-uuid                       │
  │  role:       "ADMIN"                         │
  │  active:     true                            │
  │  joined_at:  2026-03-31T09:00:00Z            │
  └─────────────────────────────────────────────┘
```

### Tenant isolation enforcement

volta enforces tenant isolation at multiple levels:

1. **ForwardAuth:** The `X-Volta-Tenant-Id` header is set by volta, not by the client. Apps trust this header.

2. **Internal API:** When an app calls `/api/v1/tenants/{tid}/members`, volta checks that the authenticated user's JWT `volta_tid` matches the `{tid}` in the URL. Cross-tenant access is structurally prevented.

3. **JWT claims:** The `volta_tid` claim in the JWT tells apps which tenant the user is accessing. Apps should use this in all database queries.

4. **Tenant suspension:** If a tenant is suspended, all member access is blocked. Other tenant memberships are unaffected.

### Tenant-scoped configuration

In `volta-config.yaml`, each app specifies which roles can access it:

```yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]

  - id: app-admin
    url: https://admin.example.com
    allowed_roles: [ADMIN, OWNER]  # Only admins and owners
```

This is enforced at the ForwardAuth level. A VIEWER trying to access app-admin gets 403 Forbidden.

---

## Common mistakes and attacks

### Mistake 1: Forgetting tenant_id in queries

The most common and most dangerous bug in multi-tenant applications:

```sql
-- BAD: Returns data from ALL tenants
SELECT * FROM items WHERE id = ?;

-- GOOD: Scoped to one tenant
SELECT * FROM items WHERE id = ? AND tenant_id = ?;
```

One missed `AND tenant_id = ?` clause is a data breach.

### Mistake 2: Trusting client-provided tenant_id

Never let the client choose which tenant they are accessing via request parameters. Tenant ID should come from the authenticated session or JWT, not from a URL parameter or header the client can set.

### Mistake 3: Not handling tenant suspension

If a tenant is suspended (for non-payment, abuse, etc.), all access should stop immediately. volta checks tenant status on every ForwardAuth request.

### Attack: Tenant enumeration

An attacker tries different tenant slugs or IDs to discover which tenants exist. volta mitigates this by returning the same error for "tenant not found" and "access denied."

### Attack: Cross-tenant data access via IDOR

IDOR (Insecure Direct Object Reference): If an app uses sequential IDs (item-1, item-2...), an attacker in Tenant A might guess Tenant B's item IDs and access them. Defense: always include tenant_id in queries, use UUIDs instead of sequential IDs.

---

## Further reading

- [rbac.md](rbac.md) -- Role-based access control within tenants.
- [forwardauth.md](forwardauth.md) -- How tenant identity is passed to apps.
- [jwt.md](jwt.md) -- How tenant ID is embedded in JWTs.
- [OWASP Tenant Isolation Guidance](https://cheatsheetseries.owasp.org/cheatsheets/SaaS_Security_Cheat_Sheet.html) -- Multi-tenant security best practices.
