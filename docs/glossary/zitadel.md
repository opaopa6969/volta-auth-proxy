# ZITADEL

[日本語版はこちら](zitadel.ja.md)

---

## What is it?

ZITADEL is an open-source identity management platform written in Go. It provides authentication, authorization, and user management as a turnkey solution -- similar to what [Auth0](auth0.md) or [Keycloak](keycloak.md) offer, but with a modern architecture built from the ground up for cloud-native, multi-tenant environments.

Think of ZITADEL like a large apartment management company. They handle everything: tenant applications, key distribution, building access rules, visitor management, and billing per unit. You do not build any of that yourself -- you just rent the service. The apartment company has a web portal where tenants can manage their own settings, invite roommates, and set up guest access.

ZITADEL is particularly notable for its built-in multi-tenancy. While Keycloak requires you to create "realms" and manually manage isolation, ZITADEL was designed with organizations (tenants) as a first-class concept from day one.

---

## Why does it matter?

ZITADEL matters in the volta-auth-proxy context as a **competitor**. Both solve the same problem: multi-tenant identity for SaaS. Understanding ZITADEL helps you understand why volta exists and what trade-offs each approach makes.

The identity management landscape has several tiers:

| Tier | Examples | Trade-off |
|------|----------|-----------|
| Managed SaaS | [Auth0](auth0.md), [Okta](okta.md) | Easy but expensive, vendor lock-in, data leaves your servers |
| Self-hosted (heavy) | [Keycloak](keycloak.md), ZITADEL | Full control but complex, high resource usage |
| Self-hosted (light) | volta-auth-proxy | Full control, minimal resources, but you build more yourself |
| DIY | Hand-rolled auth | Maximum control but maximum risk |

ZITADEL sits in the "self-hosted heavy" category. It is a complete platform with its own database, its own UI, its own API, and its own opinions about how everything should work.

---

## How does it work?

### Architecture

ZITADEL uses an event-sourcing architecture with CQRS (Command Query Responsibility Segregation):

```
  ┌─────────────────────────────────────────┐
  │              ZITADEL                     │
  │                                          │
  │  ┌──────────┐    ┌──────────────────┐   │
  │  │  Console  │    │  Management API  │   │
  │  │  (Web UI) │    │  (gRPC / REST)   │   │
  │  └──────────┘    └──────────────────┘   │
  │         │                │               │
  │         ▼                ▼               │
  │  ┌──────────────────────────────────┐   │
  │  │        Core Engine                │   │
  │  │  Event Sourcing + CQRS            │   │
  │  │  Projections for read models      │   │
  │  └──────────────────────────────────┘   │
  │         │                               │
  │         ▼                               │
  │  ┌──────────────┐                      │
  │  │  CockroachDB  │  (or PostgreSQL)    │
  │  │  (event store) │                     │
  │  └──────────────┘                      │
  └─────────────────────────────────────────┘
```

### Key features

| Feature | Description |
|---------|-------------|
| **Multi-tenancy** | Built-in organizations with isolated users, roles, and settings |
| **OIDC/OAuth2** | Full [OIDC](oidc.md) provider with discovery, PKCE, etc. |
| **SAML** | SAML 2.0 Identity Provider |
| **Passwordless** | WebAuthn / FIDO2 support |
| **MFA** | TOTP, WebAuthn, SMS, Email |
| **Branding** | Per-organization login page customization |
| **Actions** | Server-side scripts (like Auth0 Actions) for custom logic |
| **Machine users** | API keys for service-to-service auth |
| **Audit log** | Built-in event log of all identity operations |

### ZITADEL vs Keycloak vs Auth0 vs volta

| Aspect | ZITADEL | [Keycloak](keycloak.md) | [Auth0](auth0.md) | volta-auth-proxy |
|--------|---------|---------|-------|-----------------|
| Language | Go | Java | N/A (SaaS) | Java |
| Deployment | Binary / Docker | Docker / K8s | Cloud only | Docker |
| Multi-tenancy | Native (organizations) | Realms (manual) | Organizations (paid) | Native (tenants table) |
| Database | CockroachDB / Postgres | Postgres / MySQL / etc. | Managed | Postgres |
| Memory usage | ~200-500MB | ~512MB-2GB | N/A | ~30-50MB |
| Startup time | ~5-10s | ~30-60s | N/A | ~200ms |
| UI customization | Branding per org | FreeMarker themes | Universal Login | jte templates (full control) |
| Philosophy | Platform (does everything) | Platform (does everything) | Platform (does everything) | Library (you compose) |
| Learning curve | Moderate | High | Low (SaaS) | Low (code-first) |

---

## How does volta-auth-proxy use it?

volta-auth-proxy does **not** use ZITADEL. They are competitors solving the same problem with different philosophies.

### Why volta exists when ZITADEL exists

ZITADEL is excellent software, but it embodies a philosophy that volta deliberately rejects:

1. **Platform vs library**: ZITADEL is a platform you deploy and configure. volta is a library you compose into your stack. volta believes you should understand every line of your auth code.

2. **Resource usage**: ZITADEL needs CockroachDB (or Postgres) plus a Go binary that uses 200-500MB RAM. volta needs a single JVM (~30MB) and a Postgres database you probably already have.

3. **Complexity budget**: ZITADEL has hundreds of features (SAML, SCIM, machine users, actions, branding, passwordless). volta has a focused feature set for Phase 1-2 and adds capabilities incrementally. Every feature in volta is one you chose to include.

4. **Black box risk**: ZITADEL's event-sourcing architecture with CQRS projections is sophisticated but hard to debug when something goes wrong. volta uses straightforward SQL queries and Javalin handlers you can step through in your IDE.

### When to choose ZITADEL over volta

- You need SAML, SCIM, or passwordless authentication today
- You want a complete platform out of the box, not a composable library
- You have a dedicated DevOps team to manage the ZITADEL deployment
- You do not need to understand or modify the auth internals
- You are comfortable with Go and event-sourcing

### When to choose volta over ZITADEL

- You want to understand every line of your auth code
- You need minimal resource usage (edge/VPS deployments)
- You want to evolve auth incrementally, not adopt a platform
- You prefer Java and want to debug auth in your IDE
- You have been burned by "configure, do not code" platforms before

---

## Common mistakes and attacks

### Mistake 1: Underestimating operational complexity

ZITADEL with CockroachDB requires operational expertise. CockroachDB cluster management, backup strategies, and monitoring are non-trivial. If you do not have a DevOps team, the Postgres-only mode is simpler but gives up CockroachDB's multi-region benefits.

### Mistake 2: Treating ZITADEL as a black box

When ZITADEL's event projections fail or lag, debugging requires understanding its event-sourcing architecture. Teams that treat it as a black box will struggle when issues arise.

### Mistake 3: Over-relying on Actions

ZITADEL Actions (server-side scripts) can become a maintenance burden. Complex business logic in Actions is hard to test, version, and debug. Consider whether that logic belongs in your application instead.

### Mistake 4: Ignoring the migration path

Once you build on ZITADEL's proprietary APIs and data model, migrating away is painful. Ensure your application abstracts the identity layer so you are not permanently locked in.

### Attack: Organization isolation bypass

In any multi-tenant identity system, the biggest risk is cross-tenant data leakage. Verify that ZITADEL's organization isolation works correctly for your use case -- test that users in Organization A cannot access resources in Organization B.

---

## Further reading

- [ZITADEL documentation](https://zitadel.com/docs) -- Official documentation.
- [ZITADEL GitHub](https://github.com/zitadel/zitadel) -- Source code.
- [keycloak.md](keycloak.md) -- Another self-hosted identity platform.
- [auth0.md](auth0.md) -- Managed SaaS alternative.
- [ory-stack.md](ory-stack.md) -- Another open-source Go-based competitor.
- [idp.md](idp.md) -- What an Identity Provider is.
- [oidc.md](oidc.md) -- The protocol ZITADEL implements.
