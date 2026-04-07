# Database Migrations

## Naming Convention

**New migrations use timestamp-based versioning:**

```
V20260407130000__description_here.sql
```

Format: `V{YYYYMMDDHHMMSS}__{description}.sql`

This prevents version number collisions when multiple branches add migrations.

**Legacy migrations (V1-V21) use sequential numbering and must not be renamed.**

## Settings

- `outOfOrder=true` — allows timestamp-versioned migrations to run even if
  their version number is higher than a later sequential migration.
- Migration location: `classpath:db/migration`

## Rules

1. Never modify an applied migration (checksum mismatch → startup failure)
2. New migrations: always use timestamp format
3. Each migration must be idempotent where possible (`IF NOT EXISTS`, `IF NOT EXISTS`)
4. Test locally before committing
