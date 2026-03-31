# Database Migration

[日本語版はこちら](database-migration.ja.md)

---

## What is it in one sentence?

A database migration is a versioned script that changes your database structure (tables, columns, indexes) in a controlled, repeatable, and trackable way -- like a numbered instruction manual for building furniture, where you must follow each step in order.

---

## The IKEA furniture analogy

Imagine you are building an IKEA bookshelf. The instruction booklet has numbered steps:

- **Step 1:** Attach the side panels to the base
- **Step 2:** Add the first shelf
- **Step 3:** Add the second shelf
- **Step 4:** Attach the back panel

You must do these steps in order. You cannot do Step 3 before Step 1. If you are halfway through and IKEA releases an updated version with a new step ("Step 5: Add a decorative top"), you just continue from where you left off.

Database migrations work the same way:
- **Each migration** = one numbered step (V1, V2, V3...)
- **The bookshelf** = your database structure
- **Following steps in order** = applying migrations in sequence
- **The migration tool** = the system that tracks which steps you have already completed

---

## Why you cannot just edit tables by hand

New engineers often wonder: "Why can't I just open the database and add a column manually?" Here is why:

**Problem 1: Nobody knows what you did**
```
  You:     "I added a 'plan' column to the tenants table on Friday."
  Coworker: "Wait, what? My copy of the database doesn't have that column."
  You:     "Oh, you need to add it manually too."
  Coworker: "What type? What default value? Is it nullable?"
  You:     "Uh... I think it was VARCHAR(20)... or was it VARCHAR(50)?"
```

**Problem 2: You cannot reproduce the database**
If you set up a new development environment, or deploy to production, you need to know exactly what the database should look like. Manual changes are not recorded anywhere.

**Problem 3: You cannot go back**
If your manual change breaks something, how do you undo it? Do you remember exactly what the database looked like before?

**Migrations solve all of this:**
- Every change is written in a file (recorded forever)
- The migration tool applies changes in order (reproducible)
- Everyone gets the same database structure (consistent)
- You can review changes in code review (auditable)

---

## What is Flyway?

Flyway is the migration tool that volta-auth-proxy uses. It is simple:

1. You write SQL files with special names (V1__init.sql, V2__add_plans.sql, etc.)
2. When the app starts, Flyway checks: "Which migrations have I already run?"
3. It runs any new migrations it has not seen yet
4. It records what it ran in a tracking table

The naming convention is important:

```
  V1__init.sql           V = versioned, 1 = version number, init = description
  V2__add_plans.sql      V2 = version 2, add_plans = what it does
  V3__add_mfa_table.sql  V3 = version 3, add_mfa_table = what it does

  Rules:
  - V followed by a number (the version)
  - Two underscores (__) separate the version from the description
  - Description uses underscores instead of spaces
  - Versions must be sequential (V1, V2, V3...)
  - Once a migration has been run, you must NEVER edit it
```

---

## volta's V1__init.sql

volta's first migration creates all the initial tables the application needs. Here is a simplified view:

```sql
-- V1__init.sql: The first migration. Creates all base tables.

-- Users table: stores everyone who has logged in
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(100),
    google_sub VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Tenants table: stores workspaces
CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    slug VARCHAR(50) NOT NULL UNIQUE,
    plan VARCHAR(20) NOT NULL DEFAULT 'FREE',
    max_members INT NOT NULL DEFAULT 50
);

-- Memberships table: connects users to tenants with roles
CREATE TABLE memberships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    role VARCHAR(30) NOT NULL DEFAULT 'MEMBER',
    UNIQUE (user_id, tenant_id)
);

-- Sessions table: tracks who is logged in
CREATE TABLE sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    tenant_id UUID NOT NULL REFERENCES tenants(id)
);
```

When volta starts for the first time and Flyway sees an empty database, it runs V1__init.sql to create all these tables. On the next startup, Flyway says "V1 already ran, nothing to do."

If someone later adds a V2 migration, Flyway will only run V2 on the next startup (it knows V1 is already done).

---

## A simple example

```
  Day 1: Setting up volta for the first time
  ───────────────────────────────────────────
  Database: empty
  Migrations folder: V1__init.sql

  $ ./mvnw spring-boot:run
  Flyway: "Database is empty. Running V1__init.sql..."
  Flyway: "Created users, tenants, memberships, sessions tables."
  Flyway: "Recording: V1 done."

  Day 30: A new feature needs a new table
  ────────────────────────────────────────
  Database: has V1 tables
  Migrations folder: V1__init.sql, V2__add_audit_logs.sql

  $ ./mvnw spring-boot:run
  Flyway: "V1 already done. Checking for new migrations..."
  Flyway: "Found V2__add_audit_logs.sql. Running it..."
  Flyway: "Created audit_logs table."
  Flyway: "Recording: V2 done."

  Day 31: Another developer sets up from scratch
  ───────────────────────────────────────────────
  Database: empty (fresh setup)
  Migrations folder: V1__init.sql, V2__add_audit_logs.sql

  $ ./mvnw spring-boot:run
  Flyway: "Database is empty. Running V1__init.sql..."
  Flyway: "Running V2__add_audit_logs.sql..."
  Flyway: "All caught up. Database is at V2."
```

The new developer gets the exact same database as everyone else, automatically.

---

## Further reading

- [docker-compose.md](docker-compose.md) -- How the PostgreSQL database is started (where migrations run).
- [environment-variable.md](environment-variable.md) -- Database connection settings used by Flyway.
