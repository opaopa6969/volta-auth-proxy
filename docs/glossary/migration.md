# Migration (Database Migration)

[日本語版はこちら](migration.ja.md)

---

## What is it?

A database migration is a versioned, incremental change to your database schema. Instead of modifying the database by hand with SQL commands, you write migration files that describe each change, number them in order, and let a migration tool apply them automatically.

Think of it like a recipe book for your database. Each recipe (migration file) says: "On step 3, add a 'phone' column to the 'users' table." The migration tool reads the book from where it left off, applies the new steps, and remembers which step it is on. If you set up a new database, it starts from step 1 and runs through every recipe in order, ending up with the exact same structure as your production database.

This is different from just running `ALTER TABLE` by hand, because migrations are version-controlled, repeatable, and testable. Every developer and every environment gets the exact same database structure.

---

## Why does it matter?

- **Without migrations, schema changes are chaos.** "Did you add that column to production?" "I think so, let me check..." This conversation should never happen.
- **Migrations enable team collaboration.** Multiple developers can make schema changes without stepping on each other.
- **Migrations enable safe deployments.** The [CI/CD](ci-cd.md) pipeline can run migrations before the new code starts, ensuring the schema matches the code.
- **Migrations are your database's git history.** You can see exactly what changed, when, and why.
- **Rollbacks are possible.** If a migration causes problems, you know exactly what changed and can write a reverse migration.

---

## How does it work?

### Migration file naming

[Flyway](flyway.md) (the migration tool volta uses) expects files named with a version prefix:

```
  src/main/resources/db/migration/
  ├── V1__create_users_table.sql
  ├── V2__create_tenants_table.sql
  ├── V3__create_memberships_table.sql
  ├── V4__create_sessions_table.sql
  ├── V5__create_invitations_table.sql
  ├── V6__add_audit_log.sql
  └── V7__add_session_index.sql

  Format: V{number}__{description}.sql
          │          │
          │          └─ Human-readable description
          └─ Version number (determines order)
```

### How Flyway applies migrations

```
  ┌──────────────────────────────────────────────┐
  │  Flyway migration process                    │
  │                                               │
  │  1. Read flyway_schema_history table          │
  │     (tracks which migrations have been run)   │
  │                                               │
  │  2. Compare against migration files on disk   │
  │                                               │
  │  3. Apply any new migrations in order         │
  └──────────────────────────────────────────────┘

  flyway_schema_history table:
  ┌─────────┬───────────────────────┬─────────┐
  │ version │ description           │ success │
  ├─────────┼───────────────────────┼─────────┤
  │ 1       │ create_users_table    │ true    │
  │ 2       │ create_tenants_table  │ true    │
  │ 3       │ create_memberships    │ true    │
  │ 4       │ create_sessions       │ true    │
  └─────────┴───────────────────────┴─────────┘

  New file on disk: V5__create_invitations.sql
  → Flyway sees V5 is not in the history
  → Runs V5
  → Records V5 in the history table
```

### Example migration files

```sql
-- V1__create_users_table.sql
CREATE TABLE users (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email      VARCHAR(255) NOT NULL UNIQUE,
    name       VARCHAR(255),
    google_sub VARCHAR(255) UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);
```

```sql
-- V5__create_invitations_table.sql
CREATE TABLE invitations (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL REFERENCES tenants(id),
    email      VARCHAR(255) NOT NULL,
    role       VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN','MEMBER','VIEWER')),
    status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    invited_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL
);
```

### Migration lifecycle

```
  Developer writes V7__add_phone_to_users.sql
       │
       ▼
  Tests locally (Flyway runs on app startup)
       │
       ▼
  Commits to git + pushes
       │
       ▼
  CI/CD pipeline runs tests (Flyway runs in test DB)
       │
       ▼
  Deploy to staging (Flyway runs on staging DB)
       │
       ▼
  Deploy to production (Flyway runs on production DB)
       │
       ▼
  V7 is now applied everywhere, identically
```

---

## How does volta-auth-proxy use it?

### Flyway integration

volta runs Flyway migrations automatically at application startup, before Javalin starts accepting HTTP requests:

```java
// Simplified startup sequence
public static void main(String[] args) {
    // 1. Run migrations FIRST
    Flyway flyway = Flyway.configure()
        .dataSource(databaseUrl, dbUser, dbPassword)
        .load();
    flyway.migrate();

    // 2. Then start the web server
    Javalin app = Javalin.create();
    // ... register routes ...
    app.start(8080);
}
```

This means:
- A fresh deployment automatically creates all tables
- An upgrade automatically applies only the new migrations
- The app never starts with a mismatched schema

### volta's migration files

volta's migrations build the complete [schema](schema.md) for multi-tenant authentication:

```
  V1  → users, tenants, memberships (core identity)
  V2  → sessions (login tracking)
  V3  → invitations (tenant onboarding)
  V4  → rate_limits (abuse prevention)
  V5  → audit_log (security tracking)
  ...
```

### Migration rules volta follows

1. **Never modify an existing migration.** Once V3 is deployed, its contents are frozen. Make changes in V4+.
2. **Always add, never delete columns in production.** Old code might still reference them during rolling deploys.
3. **Use `IF NOT EXISTS` when safe.** Prevents failures if the migration is accidentally run twice.
4. **Test migrations against a copy of production data.** A migration might work on an empty database but fail on real data with constraints.

---

## Common mistakes and attacks

### Mistake 1: Editing a deployed migration

If you change V3 after it has been applied, Flyway detects a checksum mismatch and refuses to start. This is by design -- it protects you from inconsistent schemas.

### Mistake 2: Forgetting to add a migration for schema changes

Changing your Java code to use a new column but forgetting to write the migration means: works on your laptop (where you ran `ALTER TABLE` by hand), breaks in production.

### Mistake 3: Long-running migrations without planning

`ALTER TABLE users ADD COLUMN phone VARCHAR(255)` on a table with 10 million rows locks the table. In production, this means no logins for the duration. Use strategies like adding nullable columns first, then backfilling.

### Mistake 4: Destructive migrations without backup

`DROP TABLE sessions` in a migration permanently deletes all active sessions. Always back up before running destructive migrations in production.

### Mistake 5: Skipping version numbers

Going from V3 to V5 (skipping V4) works technically but confuses future developers. Keep versions sequential.

---

## Further reading

- [flyway.md](flyway.md) -- The migration tool volta uses.
- [schema.md](schema.md) -- The database structure that migrations build.
- [database.md](database.md) -- The PostgreSQL database migrations run against.
- [deployment.md](deployment.md) -- How migrations fit into the deployment process.
- [ci-cd.md](ci-cd.md) -- Running migrations in the automated pipeline.
