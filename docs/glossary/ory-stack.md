# Ory Stack

[日本語版はこちら](ory-stack.ja.md)

---

## What is it?

The Ory Stack is an open-source identity infrastructure built in Go, consisting of four independent but complementary services: **Kratos** (identity/user management), **Hydra** (OAuth2/OIDC server), **Oathkeeper** (identity-aware reverse proxy), and **Keto** (authorization/permissions). Together, they form a modular alternative to monolithic identity platforms like [Keycloak](keycloak.md) or [ZITADEL](zitadel.md).

Think of it like building a house with specialty contractors instead of hiring one general contractor. One contractor handles plumbing (Kratos -- user accounts), another handles electrical (Hydra -- OAuth2), another handles security systems (Oathkeeper -- access proxy), and another handles the rules about who can enter which room (Keto -- permissions). Each contractor is excellent at their specialty, but you need to coordinate them yourself.

This modular approach is Ory's defining characteristic. You can use just Kratos for user management without Hydra. You can use Hydra as an OAuth2 server without Kratos. Each service has its own database, its own API, and its own deployment lifecycle.

---

## Why does it matter?

Ory matters in the volta-auth-proxy context as a **competitor** and as an example of an alternative architectural philosophy.

The identity management world has two camps:

1. **Monolithic platforms**: Keycloak, ZITADEL, Auth0 -- one big system that does everything
2. **Modular toolkits**: Ory Stack -- separate services you compose together

volta-auth-proxy takes a third approach: a **single focused service** that does authentication and basic authorization, with everything else delegated to your existing infrastructure.

Understanding Ory helps you understand the trade-offs:

| Approach | Pros | Cons |
|----------|------|------|
| Monolithic (Keycloak) | Everything integrated, one deployment | Complex, resource-heavy, hard to customize |
| Modular (Ory) | Pick what you need, replace what you do not | Coordination overhead, multiple deployments |
| Focused (volta) | Simple, lightweight, full control | You build more yourself, fewer features out of the box |

---

## How does it work?

### The four services

#### Kratos -- Identity Management

Kratos handles user registration, login, password recovery, profile management, and MFA. It does NOT handle OAuth2 or token issuance -- that is Hydra's job.

```
  Browser ──► Kratos ──► Self-service flows (login, register, recover)
                    │
                    └──► Identity store (Postgres)
```

Key design decision: Kratos provides **no UI**. It exposes APIs that return JSON describing what fields to show. You build the UI yourself. This gives you total control over the look and feel, but means more work.

#### Hydra -- OAuth2/OIDC Server

Hydra is a certified OAuth 2.0 and OpenID Connect server. It handles token issuance, consent flows, and client management. It does NOT manage users -- it delegates login and consent to external services (like Kratos).

```
  Client ──► Hydra ──► Login Provider (Kratos or your app)
                  │
                  └──► Consent Provider (your app)
                  │
                  └──► Token issuance (JWT/opaque)
```

#### Oathkeeper -- Identity-Aware Proxy

Oathkeeper sits in front of your services and enforces access rules. It is similar to volta-auth-proxy's [ForwardAuth](forwardauth.md) pattern but with its own rule configuration system.

```
  Browser ──► Oathkeeper ──► Check rules ──► Your Service
                    │
                    └──► Authenticate (via Kratos session, JWT, etc.)
                    └──► Authorize (check permissions)
                    └──► Mutate (add headers, transform tokens)
```

#### Keto -- Permission/Authorization Service

Keto implements Google's Zanzibar permission model -- a graph-based authorization system that checks relationships like "Is alice a member of organization acme?"

```
  Your App ──► Keto: "Can alice view document-42?"
                │
                └──► Check relation tuples:
                     alice is member of engineering
                     engineering has view access to document-42
                     → allow
```

### Ory Stack vs competitors

| Aspect | Ory Stack | [Keycloak](keycloak.md) | [ZITADEL](zitadel.md) | volta-auth-proxy |
|--------|-----------|---------|---------|-----------------|
| Architecture | 4 microservices | Monolith | Monolith (event-sourced) | Single service |
| Language | Go | Java | Go | Java |
| Deployment complexity | High (4 services) | Medium (1 service) | Medium (1 service) | Low (1 service) |
| UI provided | None (headless) | Built-in (FreeMarker) | Built-in (Console) | jte templates |
| OAuth2/OIDC | Hydra (certified) | Built-in | Built-in | Direct to Google |
| User management | Kratos | Built-in | Built-in | Database + admin API |
| Authorization model | Keto (Zanzibar) | Realm roles | Organizations | [jCasbin](jcasbin.md) (Phase 4) |
| Total memory | ~500MB-1GB (all 4) | ~512MB-2GB | ~200-500MB | ~30-50MB |
| Philosophy | Modular microservices | Enterprise monolith | Cloud-native monolith | Composable library |

---

## How does volta-auth-proxy use it?

volta-auth-proxy does **not** use the Ory Stack. They are competitors with fundamentally different philosophies.

### Why volta exists when Ory exists

Ory is impressive engineering, but volta rejects several of its premises:

1. **Four services is too many**: Managing Kratos + Hydra + Oathkeeper + Keto means four Docker containers, four databases (or shared), four sets of config, four upgrade cycles, four sets of logs to monitor. volta is one JAR.

2. **Headless UI means more work**: Kratos provides no login UI. You must build it yourself using their self-service flow API. volta provides jte templates that work out of the box and that you can customize directly.

3. **Go sidecar pattern does not fit**: Ory's services run as separate Go processes. For a Java shop, this means managing Go binaries alongside your Java application, debugging across language boundaries, and learning Ory's configuration format. volta is Java all the way through.

4. **Zanzibar is overkill for most SaaS**: Keto implements Google's Zanzibar permission model, which is designed for Google-scale relationship-based authorization. Most SaaS applications need RBAC with tenants, which [jCasbin](jcasbin.md) handles with a fraction of the complexity.

### When to choose Ory over volta

- You need a certified OAuth2/OIDC server (Hydra)
- You want Google Zanzibar-style authorization
- You already run a microservices architecture and adding four more services is normal
- You need headless auth (no server-rendered UI, everything is API-driven)
- You have a Go-oriented team

### When to choose volta over Ory

- You want one service, not four
- You want server-rendered login pages (jte templates)
- You prefer Java and want to debug everything in your IDE
- You need minimal resource usage
- RBAC with tenants is sufficient for your authorization needs
- You want to understand and control every line of auth code

---

## Common mistakes and attacks

### Mistake 1: Deploying all four services when you only need one

Many teams deploy the entire Ory Stack when they only need Kratos (user management) or Hydra (OAuth2). Each service is independent -- use only what you need.

### Mistake 2: Underestimating the coordination complexity

The four services need to talk to each other. Kratos needs to know about Hydra for OAuth2 flows. Oathkeeper needs to know about both. Configuration errors between services can create subtle security holes where authentication passes but authorization is skipped.

### Mistake 3: Not building a UI layer

Kratos is headless -- it provides no login/registration UI. Teams that do not plan for this spend weeks building forms, error handling, MFA flows, and password recovery UIs that Keycloak or volta provide out of the box.

### Mistake 4: Ignoring the migration complexity

Each Ory service has its own database schema and migration system. Upgrading four services in lockstep, handling schema migrations for four databases, and rolling back if one fails is operationally complex.

### Attack: Service-to-service impersonation

If the internal network between Ory services is not secured, an attacker who compromises one service could impersonate another. For example, faking a Kratos login response to Hydra. Use mTLS or a service mesh for inter-service communication.

---

## Further reading

- [Ory documentation](https://www.ory.sh/docs/) -- Official docs for all four services.
- [Ory Kratos](https://www.ory.sh/docs/kratos/) -- Identity management.
- [Ory Hydra](https://www.ory.sh/docs/hydra/) -- OAuth2/OIDC server.
- [Ory Oathkeeper](https://www.ory.sh/docs/oathkeeper/) -- Identity-aware proxy.
- [Ory Keto](https://www.ory.sh/docs/keto/) -- Authorization (Zanzibar model).
- [zitadel.md](zitadel.md) -- Monolithic Go competitor.
- [keycloak.md](keycloak.md) -- Monolithic Java competitor.
- [auth0.md](auth0.md) -- Managed SaaS alternative.
- [forwardauth.md](forwardauth.md) -- The proxy pattern Oathkeeper and volta both use.
