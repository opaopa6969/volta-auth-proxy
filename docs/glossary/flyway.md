# Flyway

[日本語版はこちら](flyway.ja.md)

---

## What is it?

Flyway is a database migration tool that tracks and applies changes to your database schema in a controlled, versioned, repeatable way.

Think of it like a recipe book for your database. Imagine you are building a house. First you lay the foundation (V1), then you add walls (V2), then plumbing (V3), then electrical (V4). You cannot add plumbing before the walls exist. And if you need to build the same house again, you follow the same steps in the same order. Flyway is the recipe book that ensures your database is built step by step, in the right order, every time.

---

## Why does it matter?

Without a migration tool, database changes are chaos:

```
  Without Flyway:
  ┌──────────────────────────────────────────┐
  │ Developer A: "I added a users table"      │
  │ Developer B: "I added a column to users"  │
  │ Production:  "What? I have neither."      │
  │ Staging:     "I have the table but not    │
  │              the column"                   │
  │ Everyone:    "Let me send you my SQL file" │
  └──────────────────────────────────────────┘

  With Flyway:
  ┌──────────────────────────────────────────┐
  │ V1__init.sql      → Creates base tables  │
  │ V2__add_column.sql → Adds the column     │
  │ Every environment runs these in order.    │
  │ Flyway tracks what has been applied.      │
  │ Nobody sends SQL files over Slack.        │
  └──────────────────────────────────────────┘
```

### The V1__ naming convention

Flyway migration files follow a specific naming pattern:

```
V{version}__{description}.sql

Examples:
V1__init.sql
V2__oidc_flows.sql
V3__csrf_token.sql
V4__phase2_phase4_foundations.sql
```

Breaking it down:

- `V` -- means this is a versioned migration
- `1` -- the version number (Flyway runs these in order: 1, 2, 3...)
- `__` -- two underscores (this is required, Flyway uses it as a separator)
- `init` -- a human-readable description
- `.sql` -- it is a SQL file

Flyway maintains a table in your database called `flyway_schema_history` that records which migrations have been applied:

```
| version | description              | installed_on        | success |
|---------|--------------------------|---------------------|---------|
| 1       | init                     | 2026-03-01 10:00:00 | true    |
| 2       | oidc flows               | 2026-03-01 10:00:01 | true    |
| 3       | csrf token               | 2026-03-05 14:30:00 | true    |
```

When Flyway runs, it checks this table and only applies migrations that have not been applied yet. This means:

- Running Flyway twice is safe (it skips already-applied migrations)
- Every environment gets the same schema (dev, staging, production)
- Schema changes are version-controlled alongside your code

---

## How volta uses Flyway

volta-auth-proxy auto-migrates on startup. When you start volta, before it begins handling requests, Flyway runs and applies any pending migrations. This means:

1. You deploy a new version of volta
2. volta starts up
3. Flyway checks: "Are there new migration files? Has the database seen them?"
4. Flyway applies any new migrations
5. volta starts handling requests

You never manually run SQL against the database. You never worry about whether the database schema matches the code. It just works.

### volta's migration files

```
src/main/resources/db/migration/
├── V1__init.sql                         ← Base tables (users, tenants, etc.)
├── V2__oidc_flows.sql                   ← OIDC state tracking
├── V3__csrf_token.sql                   ← CSRF protection
├── V4__phase2_phase4_foundations.sql     ← M2M, webhooks, IdP config
├── V5__phase2_phase4_features.sql       ← MFA, SCIM, billing
├── V6__outbox_delivery_retry.sql        ← Webhook retry tracking
├── V7__mfa_unique_constraint.sql        ← MFA data integrity
├── V8__outbox_claim_lock.sql            ← Webhook worker locking
├── V9__sessions_mfa_verified.sql        ← MFA session state
├── V10__phase2_user_identities_backfill.sql  ← User identity backfill
└── V11__idp_x509_cert.sql              ← SAML certificate storage
```

Each file adds something new to the database schema. They are applied in version order. V1 creates the base tables that everything else builds on. V11 adds SAML certificate storage that was needed later.

### Example: V1__init.sql (simplified)

```sql
CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    slug TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email TEXT NOT NULL UNIQUE,
    display_name TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tenant_members (
    tenant_id UUID REFERENCES tenants(id),
    user_id UUID REFERENCES users(id),
    role TEXT NOT NULL DEFAULT 'MEMBER',
    PRIMARY KEY (tenant_id, user_id)
);

CREATE TABLE sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id),
    tenant_id UUID REFERENCES tenants(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT false
);
```

This is the foundation. Every subsequent migration builds on top of these tables.

---

## Why auto-migration matters for self-hosting

For a [self-hosted](self-hosting.md) product, auto-migration is critical. Without it:

```
  Manual migration hell:
  1. Download new volta version
  2. Read release notes: "Run these 3 SQL files"
  3. Connect to production database
  4. Run SQL files in the right order
  5. Hope you did not miss one
  6. Start the new version
  7. Something breaks because you ran V5 before V4

  volta's auto-migration:
  1. Download new volta version
  2. Start it
  3. Done
```

This is part of volta's philosophy of keeping [configuration](config-hell.md) and operations simple.

---

## Further reading

- [Flyway Documentation](https://flywaydb.org/documentation/) -- Official Flyway documentation.
- [config-hell.md](config-hell.md) -- Why volta automates away operational complexity.
- [self-hosting.md](self-hosting.md) -- How auto-migration makes self-hosting practical.
- [hikaricp.md](hikaricp.md) -- The connection pool that Flyway uses to connect to the database.
