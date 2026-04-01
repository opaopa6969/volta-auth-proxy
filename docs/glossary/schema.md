# Schema

[日本語版はこちら](schema.ja.md)

---

## What is it?

A schema is the structure or blueprint of a database -- it defines what tables exist, what columns each table has, what type of data each column holds, and how tables relate to each other. The schema does not contain any actual data; it describes the shape of the container that data goes into.

Think of it like a filing cabinet with labeled drawers and folders. Before you put any documents in, someone has to decide: "This drawer is for employee records, this drawer is for invoices. Each employee record has a name (text), a hire date (date), and a salary (number)." That organization plan is the schema. The actual employee records are the data.

In a relational database like PostgreSQL, the schema is defined using SQL statements like `CREATE TABLE`. volta-auth-proxy's schema has 9 tables that store everything about users, tenants, sessions, roles, and invitations.

---

## Why does it matter?

- **The schema is the contract.** Your application code assumes specific tables and columns exist. If the schema changes without the code changing, things break.
- **Data integrity depends on the schema.** Constraints like `NOT NULL`, `UNIQUE`, and `FOREIGN KEY` prevent bad data from entering the database.
- **Performance depends on the schema.** Indexes, column types, and table relationships determine how fast queries run.
- **Security starts at the schema.** [Row-level security](row-level-security.md), tenant isolation, and access patterns are shaped by schema design.
- **Schema changes are risky.** Renaming a column or changing a type can break every query that references it.

---

## How does it work?

### A schema defines structure

```sql
-- This is a schema definition (simplified)
CREATE TABLE users (
    id          UUID PRIMARY KEY,
    email       VARCHAR(255) NOT NULL UNIQUE,
    name        VARCHAR(255),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE tenants (
    id          UUID PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    subdomain   VARCHAR(63) NOT NULL UNIQUE,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### Schema vs data

```
  Schema (structure):              Data (content):
  ┌──────────────────────┐        ┌──────────────────────────────┐
  │ TABLE: users         │        │ id: a1b2c3                   │
  │ ├─ id: UUID (PK)    │        │ email: alice@example.com     │
  │ ├─ email: VARCHAR    │        │ name: Alice                  │
  │ ├─ name: VARCHAR     │        │ created_at: 2025-01-15       │
  │ └─ created_at: TS    │        ├──────────────────────────────┤
  │                      │        │ id: d4e5f6                   │
  │                      │        │ email: bob@example.com       │
  │                      │        │ name: Bob                    │
  │                      │        │ created_at: 2025-02-20       │
  └──────────────────────┘        └──────────────────────────────┘
    Defines the shape                Contains actual records
    Changes via migration            Changes via INSERT/UPDATE
```

### Key schema concepts

| Concept | What it does | Example |
|---------|-------------|---------|
| **Table** | A collection of related records | `users`, `tenants`, `sessions` |
| **Column** | A single field in a record | `email`, `created_at` |
| **Primary Key (PK)** | Uniquely identifies each row | `users.id` |
| **Foreign Key (FK)** | Links one table to another | `memberships.user_id → users.id` |
| **Index** | Speeds up lookups on a column | Index on `users.email` |
| **Constraint** | Rules data must follow | `NOT NULL`, `UNIQUE`, `CHECK` |
| **Type** | What kind of data a column holds | `UUID`, `VARCHAR`, `TIMESTAMP` |

### Table relationships

```
  ┌──────────┐     ┌──────────────┐     ┌──────────┐
  │  users   │     │ memberships  │     │ tenants  │
  │          │     │              │     │          │
  │ id (PK)  │◀────│ user_id (FK) │     │ id (PK)  │
  │ email    │     │ tenant_id(FK)│────▶│ name     │
  │ name     │     │ role         │     │ subdomain│
  └──────────┘     └──────────────┘     └──────────┘

  A user has many memberships.
  A tenant has many memberships.
  A membership connects one user to one tenant with a role.
  This is a "many-to-many" relationship via a join table.
```

---

## How does volta-auth-proxy use it?

### volta's 9-table schema

volta-auth-proxy uses 9 PostgreSQL tables:

```
  ┌─────────────────────────────────────────────────────┐
  │                volta schema                          │
  │                                                      │
  │  ┌──────────┐  ┌──────────────┐  ┌──────────┐      │
  │  │  users   │──│ memberships  │──│ tenants  │      │
  │  └──────────┘  └──────────────┘  └──────────┘      │
  │       │                                │             │
  │       │         ┌──────────────┐       │             │
  │       └─────────│  sessions    │       │             │
  │                 └──────────────┘       │             │
  │                                        │             │
  │  ┌──────────────┐  ┌──────────────┐   │             │
  │  │ invitations  │──│ (tenant FK)  │───┘             │
  │  └──────────────┘  └──────────────┘                 │
  │                                                      │
  │  + rate_limits, audit_log, schema_version, etc.     │
  └─────────────────────────────────────────────────────┘
```

### Key tables and their purpose

| Table | Purpose | Key columns |
|-------|---------|-------------|
| `users` | Every authenticated user | `id`, `email`, `name`, `google_sub` |
| `tenants` | Each tenant/organization | `id`, `name`, `subdomain` |
| `memberships` | User-tenant-role mapping | `user_id`, `tenant_id`, `role` |
| `sessions` | Active login sessions | `id`, `user_id`, `token_hash`, `expires_at` |
| `invitations` | Pending invites to join a tenant | `id`, `tenant_id`, `email`, `role`, `status` |

### Schema and RBAC

The [role](role.md) column in the memberships table enforces volta's RBAC hierarchy:

```sql
-- The role column uses a CHECK constraint
CREATE TABLE memberships (
    user_id    UUID REFERENCES users(id),
    tenant_id  UUID REFERENCES tenants(id),
    role       VARCHAR(20) CHECK (role IN ('OWNER','ADMIN','MEMBER','VIEWER')),
    PRIMARY KEY (user_id, tenant_id)
);
```

### Schema evolution via migrations

The schema is never modified by hand. All changes go through [Flyway migrations](migration.md):

```
  V1__init.sql           → Creates initial tables
  V2__add_invitations.sql → Adds invitations table
  V3__add_audit_log.sql   → Adds audit log table
  ...
```

---

## Common mistakes and attacks

### Mistake 1: Modifying schema by hand in production

Never run `ALTER TABLE` directly on the production database. Use [Flyway migrations](migration.md) so changes are versioned, tested, and reproducible.

### Mistake 2: Missing indexes

Without an index on `sessions.token_hash`, every ForwardAuth check does a full table scan. This works with 100 sessions but collapses with 100,000.

### Mistake 3: No foreign key constraints

Without `FOREIGN KEY` constraints, you can have memberships pointing to deleted users or nonexistent tenants. The schema should enforce referential integrity.

### Mistake 4: Using the wrong column type

Storing UUIDs as `VARCHAR(36)` instead of `UUID` wastes space and makes comparisons slower. Using `TEXT` when `VARCHAR(255)` would enforce a reasonable limit is another common issue.

### Mistake 5: Schema drift between environments

If development and production have different schemas because someone ran a manual `ALTER TABLE` on one environment, debugging becomes a nightmare. Flyway prevents this.

---

## Further reading

- [migration.md](migration.md) -- How schema changes are applied safely.
- [database.md](database.md) -- The PostgreSQL database that hosts the schema.
- [sql.md](sql.md) -- The language used to define and query the schema.
- [flyway.md](flyway.md) -- The migration tool volta uses.
- [row-level-security.md](row-level-security.md) -- Schema-level tenant isolation.
