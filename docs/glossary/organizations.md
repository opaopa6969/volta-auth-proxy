# Organizations (Auth0/Clerk Feature)

[日本語版はこちら](organizations.ja.md)

---

## What is it?

"Organizations" is a feature offered by cloud auth providers like Auth0 and Clerk that lets SaaS applications model B2B customers. When your SaaS product sells to companies (not individual consumers), each company is an "organization" -- with its own members, roles, and settings.

Think of it like a co-working space. The building (Auth0/Clerk) provides the infrastructure: doors, desks, WiFi, meeting rooms. Each company that rents space gets its own "organization" -- a floor or section with its own member list and its own rules about who can access what. The building manages everything, and you pay rent based on how many people use it.

---

## Why does it matter?

B2B SaaS is fundamentally multi-tenant. Slack has workspaces. Notion has team spaces. GitHub has organizations. Every B2B SaaS needs a way to group users into customer entities with separate data, roles, and permissions.

Auth0 and Clerk recognized this pattern and built "Organizations" as a first-class feature. Understanding how they work -- and where they fall short -- explains why volta-auth-proxy built multi-tenancy directly into its data model instead of relying on an external provider's feature.

---

## How Auth0 Organizations work

Auth0 introduced Organizations in 2021 as part of their B2B platform:

```
  Auth0 Organizations model:
  ┌──────────────────────────────────────────────────┐
  │ Auth0 Tenant (your Auth0 account)                │
  │                                                    │
  │  ┌─────────────┐  ┌─────────────┐                │
  │  │ Org: Acme    │  │ Org: Beta    │               │
  │  │              │  │              │                │
  │  │ Members:     │  │ Members:     │                │
  │  │  alice (admin)│  │  alice (viewer)│              │
  │  │  bob (member) │  │  charlie (admin)│             │
  │  │              │  │              │                │
  │  │ Connections: │  │ Connections: │                │
  │  │  Google SSO  │  │  Okta SAML  │                │
  │  │              │  │              │                │
  │  │ Branding:    │  │ Branding:    │                │
  │  │  Acme logo   │  │  Beta logo   │                │
  │  └─────────────┘  └─────────────┘                │
  └──────────────────────────────────────────────────┘
```

Key features:
- Users can be members of multiple organizations
- Each organization can have its own identity provider (e.g., Acme uses Google, Beta uses Okta)
- Organization-specific branding on login pages
- Role assignment is per-organization
- Organization ID appears in JWT tokens

---

## How Clerk Organizations work

Clerk takes a similar approach with a more developer-friendly API:

```javascript
// Clerk: creating an organization
const org = await clerkClient.organizations.createOrganization({
  name: "Acme Corp",
  createdBy: userId
});

// Clerk: inviting a member
await clerkClient.organizations.createOrganizationInvitation({
  organizationId: org.id,
  emailAddress: "bob@acme.com",
  role: "admin"
});
```

Clerk provides React components like `<OrganizationSwitcher />` that handle the UI for switching between organizations.

---

## The problems with provider-managed organizations

### 1. Cost

Auth0 Organizations is a paid feature, available on the B2B plan:

```
  Auth0 pricing (approximate):
  Free tier:    7,500 MAU, no Organizations feature
  Essentials:   Organizations available, ~$35/month base
  Professional: Full Organizations, ~$240/month base
  Enterprise:   Custom pricing

  At 100k MAU: ~$2,400/month
  Organizations feature alone can add significant cost.
```

Clerk has similar per-MAU pricing that scales with your user count.

### 2. Vendor lock-in

Your multi-tenancy model is tied to the provider's API:

```javascript
// Auth0-specific code woven throughout your app:
import { useOrganization } from '@auth0/auth0-react';

function Dashboard() {
  const { organization } = useOrganization();
  // Every component that needs tenant context
  // imports Auth0's SDK
}
```

Switching from Auth0 to another provider means rewriting every component that references organizations. See [vendor-lock-in.md](vendor-lock-in.md).

### 3. Limited control

You cannot modify how organizations work internally. If Auth0's organization model does not match your business model exactly, you have two options: work around it, or live with it. Common limitations:

- Organization hierarchies (parent/child orgs) may not be supported
- Custom metadata per organization is limited
- Organization-level feature flags require external tooling
- Billing per organization requires custom integration

### 4. Data lives elsewhere

Your tenant data -- who belongs to which organization, what role they have, when they joined -- lives in Auth0's cloud. You cannot query it with SQL. You cannot join it with your business data without API calls. You do not have a `tenants` table you can `SELECT * FROM`.

---

## How volta compares

volta's [tenant](tenant.md) concept is the equivalent of Auth0/Clerk Organizations, but built into the database:

| Aspect | Auth0 Organizations | Clerk Organizations | volta Tenants |
|--------|-------------------|-------------------|--------------|
| Where data lives | Auth0 cloud | Clerk cloud | Your PostgreSQL |
| Cost | Per-MAU pricing | Per-MAU pricing | $0 |
| User multi-membership | Yes | Yes | Yes |
| Per-org roles | Yes | Yes | Yes (OWNER/ADMIN/MEMBER/VIEWER) |
| Per-org IdP | Yes | Yes | Shared (Phase 1), per-tenant (Phase 3) |
| Self-service creation | Via API | Via API | Via API + self-service signup |
| Branding per org | Yes (limited) | Yes (limited) | Via template customization |
| SQL queryable | No | No | Yes (`SELECT * FROM tenants`) |
| Invitation system | Yes | Yes | Yes (built-in) |
| Switching between orgs | SDK component | `<OrgSwitcher />` | API call + session update |

### volta's implementation

```sql
-- volta's tenant model is just database tables:

-- Tenants
SELECT id, name, slug FROM tenants;
-- acme-uuid | Acme Corp | acme
-- beta-uuid | Beta Inc  | beta

-- Memberships (user + tenant + role)
SELECT user_id, tenant_id, role FROM memberships;
-- alice-uuid | acme-uuid | ADMIN
-- alice-uuid | beta-uuid | VIEWER
-- bob-uuid   | acme-uuid | MEMBER
```

```java
// volta: tenant context comes from headers, not SDK
app.get("/api/data", ctx -> {
    String tenantId = ctx.header("X-Volta-Tenant-Id");
    String role = ctx.header("X-Volta-Roles");
    // No SDK import. No vendor dependency. Just HTTP headers.
});
```

The difference is philosophical: Auth0/Clerk provide organizations as a **managed service feature**. volta provides tenants as **database rows you own**.

---

## When Auth0/Clerk Organizations make sense

- You want zero infrastructure management for multi-tenancy
- You have budget for per-MAU pricing
- Your organization model is simple (flat, no hierarchies)
- You want pre-built UI components for org switching
- You are OK with the vendor lock-in trade-off

---

## When volta makes more sense

- You want your tenant data in your own database
- You need to join tenant data with business data in SQL
- You want predictable costs ($0 for the auth layer)
- You need full control over the tenant lifecycle
- You want to avoid SDK dependencies in every component

---

## Further reading

- [tenant.md](tenant.md) -- volta's tenant concept.
- [multi-tenant.md](multi-tenant.md) -- Multi-tenancy architecture in volta.
- [auth0.md](auth0.md) -- Auth0 overview and trade-offs.
- [vendor-lock-in.md](vendor-lock-in.md) -- Why managed features create lock-in.
- [invitation-flow.md](invitation-flow.md) -- volta's organization invitation system.
