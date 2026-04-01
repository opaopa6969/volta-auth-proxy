# Tight Coupling vs Loose Coupling

[日本語版はこちら](tight-coupling-vs-loose-coupling.ja.md)

---

## What is it?

Coupling is how much one part of your software depends on another part. Tight coupling means two parts are deeply intertwined -- change one, and you must change the other. Loose coupling means two parts interact through a thin, well-defined boundary -- change one, and the other barely notices.

Think of it like music. A marching band is tightly coupled: every musician must play the same song, at the same tempo, in the same key. If the trumpet section switches to a different song, the whole band falls apart. A jazz ensemble is loosely coupled: the musicians agree on a chord progression, but each one improvises freely within that structure. If the pianist tries something different, the bassist adapts.

Neither is "better." A marching band needs tight coupling to sound unified. A jazz ensemble needs loose coupling to sound creative. The right choice depends on what you are trying to achieve.

---

## Why does it matter?

In software, coupling is one of the most important design decisions you make. Too much coupling and your system is fragile -- every change ripples everywhere. Too little coupling and your system is scattered -- nobody knows where anything is, and you spend all your time wiring things together across network boundaries.

Most software advice says "loose coupling is always better." volta-auth-proxy disagrees -- at least for certain things.

---

## When tight coupling is actually better

volta's configuration is tightly coupled by design. All configuration lives in two places:

```
.env                   → secrets (database URL, OAuth credentials)
volta-config.yaml      → settings (app list, roles)
```

These are not spread across 5 config files, 3 environment variable groups, and a database table. They are aggregated in two files. This is tight coupling between configuration sources and the application.

Why is this good?

```
Loosely coupled config (Keycloak-style):
  realm.json           → realm settings
  standalone.xml       → server settings
  Environment vars     → overrides
  Admin console        → runtime changes
  SPI config           → extension settings

  Question: "Where is the session timeout configured?"
  Answer: "It could be in any of 5 places. Good luck."

Tightly coupled config (volta-style):
  .env                 → secrets
  volta-config.yaml    → everything else

  Question: "Where is the session timeout configured?"
  Answer: "It's in the code. The config files are for things that
           differ between deployments."
```

Tight coupling means one place to look. One place to debug. One place to understand. For configuration, this is a feature, not a flaw.

---

## When loose coupling is better

volta uses loose coupling for its internal architecture -- specifically through Java interfaces:

```java
// The interface (loose coupling boundary)
interface SessionStore {
    void createSession(...);
    Optional<SessionRecord> findSession(...);
    void revokeSession(...);
}

// Phase 1: Postgres implementation
class PostgresSessionStore implements SessionStore { ... }

// Phase 2: Redis implementation (drops in without changing auth logic)
class RedisSessionStore implements SessionStore { ... }
```

The auth logic does not know or care whether sessions are stored in Postgres or Redis. It only talks to the `SessionStore` interface. This is loose coupling -- and it is essential for extensibility.

---

## volta's stance: "tight coupling, no apologies"

volta is honest about its coupling choices:

| Component | Coupling | Why |
|-----------|----------|-----|
| Config aggregation | Tight | One place to look, one place to debug |
| Java + Javalin | Tight | Full stack trace readability |
| OIDC + session + JWT | Tight (one process) | No network hops between auth steps |
| SessionStore interface | Loose | Swap Postgres for Redis without rewriting auth |
| PolicyEvaluator interface | Loose | Swap Java rules for jCasbin in Phase 4 |
| App integration (ForwardAuth) | Loose | Apps are language-agnostic, any framework works |

The pattern: tight coupling where it simplifies debugging and understanding, loose coupling where it enables future extensibility. This is not a contradiction -- it is a conscious design strategy.

---

## The real meaning of "tight coupling, no apologies"

When volta says "tight coupling, no apologies," it means:

1. **We chose a single Java process** instead of microservices. The OIDC handler, session manager, JWT issuer, and ForwardAuth endpoint all live in one codebase, one process, one deployment. This is tight coupling. It means you cannot scale the JWT issuer independently of the session manager. volta does not apologize for this because the benefit -- complete stack traces, simple debugging, sub-millisecond internal calls -- is worth more than independent scaling for the target use case.

2. **We chose aggregated configuration** instead of scattered settings. Everything is in two files. This is tight coupling between the operator and the config format. volta does not apologize because the alternative is config hell.

3. **We chose opinionated defaults** instead of configurable everything. RS256, 8-hour sessions, PKCE required. These are tightly coupled to the codebase. volta does not apologize because these are correct choices that should not need changing.

---

## In volta-auth-proxy

volta deliberately uses tight coupling for configuration, deployment, and process architecture (one process, two config files, opinionated defaults) and loose coupling for internal interfaces (`SessionStore`, `PolicyEvaluator`) that may need alternative implementations in later phases.

---

## Further reading

- [single-process.md](single-process.md) -- The tightly coupled process architecture.
- [config-hell.md](config-hell.md) -- What happens when configuration is too loosely coupled.
- [interface-extension-point.md](interface-extension-point.md) -- Where volta uses loose coupling.
- [microservice.md](microservice.md) -- The loosely coupled alternative volta rejected.
