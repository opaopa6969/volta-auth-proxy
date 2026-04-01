# External Dependency

[日本語版はこちら](external-dependency.ja.md)

---

## What is it?

An external dependency is a piece of software, service, or infrastructure that your system relies on but that you do not control. It is code someone else wrote, a server someone else runs, or a database someone else maintains. When it breaks, you cannot fix it directly -- you can only wait, work around it, or replace it.

Think of it like the water supply to your house. You depend on the city's water system to work. If a pipe bursts three blocks away, your kitchen sink stops working -- and there is nothing you can do except wait for the city to fix it. The water system is an external dependency of your household.

---

## Why does it matter?

Every external dependency is a potential point of failure, a source of breaking changes, and a constraint on your architecture. In the auth world, this is especially critical because authentication is on the critical path -- if auth goes down, your entire application becomes inaccessible.

volta-auth-proxy was designed with a minimal dependency philosophy: **PostgreSQL is the only required external dependency.** Understanding why this matters requires understanding what dependencies cost.

---

## The cost of external dependencies

### Operational cost

Every dependency is something you must:

```
  Per dependency:
  ┌──────────────────────────────────────┐
  │ ✓ Install and configure              │
  │ ✓ Monitor for health                 │
  │ ✓ Keep updated (security patches)    │
  │ ✓ Back up (if stateful)              │
  │ ✓ Troubleshoot when it breaks        │
  │ ✓ Learn its failure modes            │
  │ ✓ Plan capacity                      │
  │ ✓ Handle version upgrades            │
  └──────────────────────────────────────┘

  Dependencies × Cost per dependency = Total operational burden

  Keycloak stack:    Keycloak + Postgres + JVM       = 3 things to manage
  Ory stack:         Oathkeeper + Kratos + Hydra
                     + Keto + Postgres               = 5 things to manage
  ZITADEL stack:     ZITADEL + CockroachDB           = 2 things to manage
  Auth0:             Auth0 (cloud)                   = 1 thing (but no control)

  volta stack:       volta + Postgres                = 2 things to manage
```

### Failure multiplication

Each dependency has its own failure rate. If each component has 99.9% uptime (about 8.7 hours of downtime per year):

```
  1 component:  99.9% uptime
  2 components: 99.9% × 99.9% = 99.8% uptime
  3 components: 99.9% × 99.9% × 99.9% = 99.7% uptime
  5 components: 99.9%^5 = 99.5% uptime  (43.8 hours downtime/year)
```

Every component you add makes the system less reliable, unless you add redundancy (which adds complexity, which adds more components...).

### Upgrade risk

When a dependency releases a new version, you must evaluate whether to upgrade. Breaking changes in a dependency can cascade through your system. The more dependencies you have, the more upgrades you must manage, and the higher the chance that one of them breaks something.

---

## volta's minimal dependency philosophy

volta's production deployment requires exactly two components:

```
  volta's dependencies:
  ┌──────────────────────────────┐
  │ volta-auth-proxy             │  Your auth service (you control this)
  └──────────┬───────────────────┘
             │
  ┌──────────▼───────────────────┐
  │ PostgreSQL                   │  The ONLY external dependency
  └──────────────────────────────┘
```

### Why PostgreSQL and nothing else

PostgreSQL handles:
- **User and tenant data** -- users, tenants, memberships, roles
- **Sessions** -- session creation, validation, expiry (with optional Redis upgrade)
- **Cryptographic keys** -- RSA key pairs stored encrypted at rest
- **Audit logs** -- every auth event (with optional Kafka/Elasticsearch upgrade)
- **Invitations** -- invitation codes, usage tracking, expiry
- **Migrations** -- Flyway handles schema evolution

Other auth systems split these concerns across multiple services:

| Concern | Keycloak | Ory Stack | volta |
|---------|----------|-----------|-------|
| User data | Postgres | Postgres (Kratos) | Postgres |
| Sessions | Infinispan (distributed cache) | Postgres (Kratos) | Postgres |
| Permissions | Keycloak engine | Postgres (Keto) | Postgres |
| Key storage | Keycloak DB | Hydra DB | Postgres |
| Audit | Keycloak events | Custom | Postgres |

volta puts everything in one database because one database is enough. A single PostgreSQL instance handles thousands of concurrent auth requests without difficulty.

### Optional dependencies (by choice, not requirement)

volta supports optional dependencies for teams that need them:

| Optional dependency | Purpose | Default (no dependency) |
|--------------------|---------|----------------------|
| Redis | Session storage for higher throughput | PostgreSQL sessions |
| Kafka | Audit event streaming | Postgres audit table |
| Elasticsearch | Audit search and analytics | Postgres audit table |
| SMTP server | Email notifications | No email (link sharing) |
| SendGrid | Email notifications (managed) | No email (link sharing) |

The key word is **optional**. volta works fully without any of these. They are enhancements, not requirements.

---

## The "every dependency is a promise" principle

When you add a dependency, you are making a promise to your operations team:

- "I promise to keep this updated."
- "I promise to monitor this."
- "I promise to know how to fix it when it breaks at 3 AM."
- "I promise this will not be the thing that takes down production."

volta makes this promise for exactly one external system: PostgreSQL. PostgreSQL is the most battle-tested open-source database in the world. Your team almost certainly already knows it. Its failure modes are well-documented. Its backup and recovery story is mature.

One dependency. One promise. One thing to get right.

---

## Further reading

- [database.md](database.md) -- PostgreSQL as volta's single dependency.
- [self-hosting.md](self-hosting.md) -- How minimal dependencies make self-hosting practical.
- [docker-compose.md](docker-compose.md) -- volta's simple deployment configuration.
