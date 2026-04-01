# External vs Internal

[日本語版はこちら](external-vs-internal.ja.md)

---

The words "external" and "internal" appear everywhere in software, and they mean different things in different contexts. But there is a common thread: **external means something outside your control, and internal means something inside your control.** This distinction drives many of volta-auth-proxy's core architectural decisions.

---

## Three meanings of external vs internal

### 1. External server vs internal code

An external server is a separate process -- something you deploy, run, and manage alongside your application. An internal implementation is code that lives inside your application process.

```
  External auth server:
  ┌──────────┐     ┌──────────────┐     ┌──────────────┐
  │ Your App  │────►│ Keycloak     │────►│ PostgreSQL   │
  │           │     │ (separate    │     │              │
  │           │     │  server)     │     │              │
  └──────────┘     └──────────────┘     └──────────────┘
  Three processes. Three things to deploy. Three things that can fail.

  Internal auth code:
  ┌──────────────────────────────┐     ┌──────────────┐
  │ volta-auth-proxy              │────►│ PostgreSQL   │
  │ (auth logic lives INSIDE     │     │              │
  │  this single process)        │     │              │
  └──────────────────────────────┘     └──────────────┘
  Two processes. Simpler. Less that can fail.
```

Keycloak is an external auth server. Your application connects to it via network calls. volta is different: the auth logic is the application. There is no "connecting to" the auth system -- you ARE the auth system.

Why does this matter? Network calls fail. External servers go down. Configuration drift between your app and the auth server causes mysterious bugs. When auth is internal code, these problems disappear.

### 2. External API (public) vs internal API (private network)

An external API is reachable from the public internet. Anyone with the URL can attempt to call it. An internal API is only reachable within your private network.

```
  External API:
  ┌────────────┐
  │  Internet   │──►  https://api.stripe.com/v1/charges
  │  (anyone)   │     (public, anyone can try to call it)
  └────────────┘

  Internal API:
  ┌────────────┐     ┌──────────────┐
  │  Your App   │──►  volta:7070/api/v1/tenants/...
  │  (backend)  │     (private, only your services can reach it)
  └────────────┘     └──────────────┘
  The internet CANNOT reach this.
```

volta's `/auth/*` endpoints are external (browsers must reach them for login). volta's `/api/v1/*` endpoints are internal (only your backend services should reach them). The distinction is critical for security: internal APIs can trust the caller because the caller is already inside your network.

### 3. External dependency vs internal implementation

An external dependency is software you rely on but did not write and do not control. An internal implementation is code you wrote and fully control.

```
  External dependencies of a Keycloak deployment:
  - Keycloak binary (someone else's code)
  - JVM runtime (Oracle/Eclipse's code)
  - Infinispan cache (JBoss's code)
  - PostgreSQL driver (community code)
  - Theme engine (Keycloak's code)

  External dependencies of a volta deployment:
  - PostgreSQL (community code)
  - That's it.
```

Every external dependency is code you cannot debug at 3 AM when it breaks. It is a version you must track. It is an update you must evaluate. It is a security vulnerability you must monitor. volta minimizes external dependencies by keeping as much as possible internal.

---

## Why volta keeps things internal

volta's philosophy can be summarized as: **when you can keep it internal without sacrificing quality, keep it internal.**

| What | External approach | volta's internal approach |
|------|------------------|-------------------------|
| Auth server | Keycloak (external process) | Auth logic in volta process |
| Session storage | Redis cluster (external service) | PostgreSQL table (shared DB) |
| Template engine | FreeMarker in Keycloak (external system's engine) | jte compiled into volta (internal) |
| Key storage | External HSM or vault | Encrypted in PostgreSQL |
| User management | External admin console | Built-in admin UI and API |
| Audit logging | External ELK stack | PostgreSQL table (with optional external sinks) |

The benefits compound:

1. **Fewer moving parts** = fewer failure modes
2. **One process to understand** = faster debugging
3. **One deployment** = simpler ops
4. **Less network traffic** = lower latency
5. **No version mismatches** = no "works on my machine"

---

## When external is the right choice

Internal is not always better. External is the right choice when:

- **Specialization matters.** PostgreSQL is a better database than anything volta could build internally. It is an external dependency worth having.
- **Scale requires distribution.** If you need millions of sessions per second, an external Redis cluster is justified. volta supports this (`SESSION_STORE=redis`).
- **Compliance requires separation.** Some security standards require dedicated audit systems. volta supports external audit sinks (Kafka, Elasticsearch).

The key is intentionality. volta does not avoid external dependencies out of dogma. It avoids them when the internal alternative is simpler, more reliable, and sufficient. It embraces them when the external tool genuinely solves a problem that internal code cannot.

---

## The spectrum in volta

volta's architecture sits on a spectrum from fully internal to optionally external:

```
  Fully internal (always):
  ├── Auth logic (OIDC flow, session validation, JWT issuance)
  ├── ForwardAuth handler
  ├── Login/consent UI (jte templates)
  ├── Role-based access control
  └── Invitation system

  Internal by default, externally upgradeable:
  ├── Session storage: PostgreSQL → Redis
  ├── Audit logging: PostgreSQL → Kafka / Elasticsearch
  └── Notifications: none → SMTP / SendGrid

  Always external:
  └── PostgreSQL (the one dependency volta embraces)
```

This is the design principle: start internal, go external only when the need is proven.

---

## Further reading

- [external-dependency.md](external-dependency.md) -- Deep dive into external dependencies.
- [internal-api.md](internal-api.md) -- volta's internal API design.
- [self-hosting.md](self-hosting.md) -- Why keeping things internal makes self-hosting easier.
- [interface-extension-point.md](interface-extension-point.md) -- How volta enables the internal-to-external upgrade path.
