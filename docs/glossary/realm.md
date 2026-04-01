# Realm (Keycloak Realm)

[日本語版はこちら](realm.ja.md)

---

## What is it?

A Keycloak Realm is an isolated security domain within a [Keycloak](keycloak.md) server. Each realm has its own set of users, roles, clients (applications), identity providers, login pages, and configuration. Realms do not share data with each other -- a user in Realm A cannot log into Realm B unless they are separately registered there.

Think of it like separate apartment buildings managed by the same property company. Each building has its own set of keys, its own mailboxes, its own lobby security rules, and its own tenant list. Living in Building A gives you no access to Building B, even though the same company manages both.

---

## Why does it matter?

Realms are Keycloak's answer to [multi-tenancy](multi-tenant.md). When teams try to use Keycloak for a multi-tenant SaaS (where each customer organization is a separate tenant), they typically create one realm per tenant. This works for a handful of tenants, but creates significant problems at scale.

Understanding realms explains one of the core reasons volta-auth-proxy was built with a different multi-tenancy model.

---

## How realms work in Keycloak

```
  Keycloak Server
  ┌───────────────────────────────────────────────────────┐
  │                                                       │
  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │
  │  │ Realm: Acme  │  │ Realm: Beta  │  │ Realm: Gamma│  │
  │  │              │  │              │  │             │  │
  │  │ Users:       │  │ Users:       │  │ Users:      │  │
  │  │  - alice     │  │  - bob       │  │  - charlie  │  │
  │  │  - dave      │  │  - eve       │  │  - frank    │  │
  │  │              │  │              │  │             │  │
  │  │ Clients:     │  │ Clients:     │  │ Clients:    │  │
  │  │  - wiki-app  │  │  - wiki-app  │  │  - wiki-app │  │
  │  │  - admin-app │  │  - admin-app │  │             │  │
  │  │              │  │              │  │             │  │
  │  │ Roles:       │  │ Roles:       │  │ Roles:      │  │
  │  │  - admin     │  │  - admin     │  │  - admin    │  │
  │  │  - user      │  │  - user      │  │  - user     │  │
  │  │              │  │              │  │             │  │
  │  │ Theme:       │  │ Theme:       │  │ Theme:      │  │
  │  │  custom-acme │  │  default     │  │  default    │  │
  │  └─────────────┘  └─────────────┘  └─────────────┘  │
  │                                                       │
  │  Each realm is a completely separate world.           │
  │  Nothing is shared.                                   │
  └───────────────────────────────────────────────────────┘
```

Each realm is fully independent. This means:
- Users must be created separately in each realm
- Client applications (OAuth2 clients) must be configured separately in each realm
- Roles, groups, and permissions are defined per realm
- Login pages and themes are configured per realm
- Each realm has its own [realm.json](realm-json.md) configuration (500+ lines each)

---

## Why realms are limited for multi-tenancy

### Problem 1: One user, multiple tenants

In many SaaS platforms, a single person belongs to multiple organizations. Alice might be an ADMIN in Acme Corp and a VIEWER in Beta Inc. With Keycloak realms:

```
  alice@gmail.com wants to be in Acme AND Beta:

  Option A: Create separate accounts
  ┌─────────────┐  ┌─────────────┐
  │ Realm: Acme  │  │ Realm: Beta  │
  │ alice (admin)│  │ alice (viewer)│  ← Two accounts, two passwords,
  └─────────────┘  └─────────────┘    two login sessions. Confusing.

  Option B: Build cross-realm federation
  ┌─────────────┐  ┌─────────────┐
  │ Realm: Acme  │──│ Realm: Beta  │  ← Complex. Not natively supported.
  │ alice (admin)│  │ alice (viewer)│    Requires custom SPI development.
  └─────────────┘  └─────────────┘
```

Neither option is good. SaaS users expect one account, one login, multiple workspaces.

### Problem 2: Scale

At 100+ realms, Keycloak's admin console becomes slow. At 1,000+ realms, management becomes a serious operational challenge. Each realm adds configuration overhead, memory usage, and startup time.

### Problem 3: Realm creation is not self-service

Creating a Keycloak realm is an administrative operation. It typically requires Keycloak admin access. In a SaaS product, you want tenant creation to be self-service -- a user signs up and their organization is created automatically. With Keycloak, you need custom automation to create realms via the Admin API.

### Problem 4: Configuration duplication

Every realm needs its own client configuration, role definitions, identity provider setup, and theme assignment. If you have 100 tenants and want to add a new OAuth2 client to all of them, you must update 100 realm configurations.

---

## volta's tenant model vs Keycloak's realm model

volta takes a fundamentally different approach:

```
  volta data model:
  ┌──────────────────────────────────────────────────┐
  │ PostgreSQL                                        │
  │                                                    │
  │  users              tenants          memberships   │
  │  ┌──────────┐      ┌──────────┐    ┌───────────┐ │
  │  │ alice     │──┐   │ Acme     │    │ alice     │ │
  │  │ bob       │  ├──►│ Beta     │◄───│  → Acme   │ │
  │  │ charlie   │  │   │ Gamma    │    │    (ADMIN) │ │
  │  └──────────┘  │   └──────────┘    │  → Beta   │ │
  │                │                    │    (VIEWER)│ │
  │                │                    │ bob       │ │
  │                └───────────────────►│  → Acme   │ │
  │                                     │    (MEMBER)│ │
  │                                     └───────────┘ │
  └──────────────────────────────────────────────────┘
```

| Aspect | Keycloak Realm | volta Tenant |
|--------|---------------|--------------|
| Storage | Separate configuration domain | Row in `tenants` table |
| User identity | Per-realm (duplicated) | Global (one account, many tenants) |
| Multi-membership | Not native | Core feature (user has role per tenant) |
| Creation | Admin API, complex | API call, self-service |
| Configuration | Full realm.json per tenant | Shared app config, tenant is just data |
| Scale limit | ~100 before pain | Limited only by database rows |
| Cross-tenant | Difficult | Native (user switches tenant in-session) |

### How volta handles what realms handle

| Realm feature | volta equivalent |
|---------------|-----------------|
| User isolation | `tenant_id` column on every query + row-level isolation |
| Role assignment | `memberships` table with `(user_id, tenant_id, role)` |
| Client/app config | `volta-config.yaml` (shared across all tenants) |
| Login theme | Same login pages for all tenants (with tenant branding via config) |
| Identity provider | Shared OIDC provider (Google, etc.) across tenants |

volta's model is simpler because it treats tenants as **data**, not as **configuration domains**. A tenant is a row in a database table, not a 500-line JSON configuration file. This makes tenant creation instant, multi-tenant membership natural, and cross-tenant operations straightforward.

---

## Further reading

- [multi-tenant.md](multi-tenant.md) -- volta's multi-tenancy architecture.
- [realm-json.md](realm-json.md) -- The configuration file realms require.
- [keycloak.md](keycloak.md) -- The broader Keycloak evaluation.
- [tenant.md](tenant.md) -- volta's tenant concept in detail.
- [tenant-resolution.md](tenant-resolution.md) -- How volta determines which tenant a request belongs to.
