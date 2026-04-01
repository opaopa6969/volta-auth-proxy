# Minimum Viable Architecture

[日本語版はこちら](minimum-viable-architecture.ja.md)

---

Everyone has heard of MVP -- Minimum Viable Product. Build the smallest product that customers will actually use, learn from them, and iterate. This concept transformed product development.

But what about architecture? Most teams default to complex architectures because they are afraid of "painting themselves into a corner." They add message queues, distributed caches, separate microservices, and container orchestration on day one -- just in case. This essay argues for MVA: Minimum Viable Architecture. The smallest set of components that delivers a working, production-quality system.

---

## volta's Phase 1 architecture: Gateway + Postgres

volta's production architecture in Phase 1 is two components:

```
  ┌──────────────────────────────────────────────┐
  │                                                │
  │  ┌──────────────┐     ┌──────────────┐       │
  │  │ volta-auth-  │────►│ PostgreSQL   │       │
  │  │ proxy        │     │              │       │
  │  │              │◄────│              │       │
  │  └──────────────┘     └──────────────┘       │
  │                                                │
  │  That's it.                                    │
  │  Two boxes. One arrow.                         │
  │                                                │
  └──────────────────────────────────────────────┘
```

No Redis. No Kafka. No Elasticsearch. No message queue. No distributed cache. No separate microservices. No Kubernetes. Just one application process talking to one database.

This is not a demo setup. This is production architecture. Real users authenticate through this. Real sessions are managed by this. Real JWTs are signed by this. And it handles it all with ~30MB of RAM and ~200ms startup time.

---

## Why 2 components is better than 5

### The "every component is a failure point" principle

Let's compare architectures:

```
  Architecture A: volta (2 components)
  ┌──────┐     ┌──────────┐
  │ volta │────►│ Postgres │
  └──────┘     └──────────┘
  Failure points: 2
  Network connections: 1
  Things to monitor: 2
  Things to back up: 1

  Architecture B: Ory Stack (5 components)
  ┌────────────┐  ┌────────┐  ┌───────┐  ┌──────┐  ┌──────────┐
  │ Oathkeeper  │  │ Kratos │  │ Hydra │  │ Keto │  │ Postgres │
  └──────┬─────┘  └───┬────┘  └───┬───┘  └──┬───┘  └────┬─────┘
         │            │           │          │           │
         └────────────┴───────────┴──────────┴───────────┘
  Failure points: 5
  Network connections: 4+ (each service talks to Postgres and maybe each other)
  Things to monitor: 5
  Things to back up: 1 (but 4 services depending on it)

  Architecture C: Keycloak + ecosystem (3+ components)
  ┌──────────┐  ┌───────────┐  ┌──────────┐
  │ Keycloak  │  │ Infinispan│  │ Postgres │
  └────┬─────┘  └─────┬─────┘  └────┬─────┘
       │              │              │
       └──────────────┴──────────────┘
  Failure points: 3
  Network connections: 2+
  Things to monitor: 3
  Things to back up: 1+
```

Each additional component multiplies:
- **Deployment complexity** -- more containers, more configs, more port mappings
- **Monitoring burden** -- more health checks, more dashboards, more alerts
- **Debug difficulty** -- "which service is causing the 500 error?"
- **Upgrade risk** -- version compatibility between services
- **Resource usage** -- each service uses CPU, RAM, and disk

### The "can I run it on a $10 VPS?" test

volta passes this test. A $10/month VPS (1 CPU, 1GB RAM) comfortably runs volta (~30MB) + PostgreSQL (~50MB) with room to spare. The entire auth infrastructure for your SaaS costs less than a coffee.

Try running the Ory stack on a $10 VPS. Or Keycloak. You will need at least a $40-80/month server, and that is before your actual application.

This is not about being cheap. It is about efficiency. If your auth system needs $80/month of infrastructure, your overall hosting cost grows proportionally. volta's minimal footprint means auth does not dominate your infrastructure budget.

---

## What MVA is NOT

MVA is not:
- **Cutting corners.** volta's auth is production-grade: PKCE, RS256, encrypted key storage, CSRF protection, rate limiting, audit logging. Minimal architecture does not mean minimal security.
- **Refusing to scale.** volta's architecture supports growth through PostgreSQL vertical scaling, Redis sessions (Phase 2), and horizontal volta instances behind a load balancer. The architecture is designed to grow -- it just does not start grown.
- **Ignoring the future.** volta defines interfaces (`SessionStore`, `AuditSink`, `NotificationService`) that allow plugging in additional components when needed. The architecture is prepared for expansion, just not burdened by it.

MVA is:
- Starting with the fewest components that deliver production quality
- Adding components only when real need is demonstrated
- Treating simplicity as a feature, not a limitation

---

## The "what could go wrong" exercise

A useful exercise: for each component you want to add, ask "what happens when this component fails?"

```
  Component: Redis (for sessions)
  What if Redis fails?
  - All sessions are lost
  - All users must log in again
  - Possible data inconsistency between Redis and Postgres

  Without Redis (volta Phase 1):
  What if Postgres fails?
  - Sessions are lost (same database as everything else)
  - volta cannot start
  - BUT: Postgres failure means everything is down anyway

  Adding Redis didn't make the system MORE reliable.
  It added a SECOND failure mode.
```

This is not to say Redis is bad. Redis is excellent for what it does. But adding it to the architecture has costs, and those costs should be justified by real need (measured throughput limits), not hypothetical future load.

---

## When to add components

The Phase 2-4 roadmap tells you when to add components:

| Component | When to add | Signal that it's needed |
|-----------|------------|------------------------|
| Redis | Phase 2 | Session queries are >50% of Postgres load |
| Kafka | Phase 2+ | Audit events need real-time streaming to external systems |
| Elasticsearch | Phase 3+ | You need full-text search across millions of audit events |
| SMTP server | Phase 3 | Customers want email notifications |
| Second volta instance | When needed | Request latency increases under load |

The key phrase is "signal that it's needed." Not "we think we might need it someday." Not "best practices say we should have it." Not "our architect drew it on the whiteboard." A real, measurable signal.

---

## The philosophical point

Minimum viable architecture is an act of discipline. It is resisting the urge to build for hypothetical problems. It is trusting that a well-designed foundation (interfaces, clean data model, versioned APIs) allows you to add components later without rework.

It is saying: "I do not know what we will need in 6 months. I do know what we need today. Let's build today's system today, and tomorrow's system tomorrow."

volta's architecture embodies this: two components today, with a clear path to more when the need is proven. And the remarkable thing is, for most SaaS products, today's architecture is enough for a very long time.

---

## Further reading

- [external-dependency.md](external-dependency.md) -- Why fewer dependencies is better.
- [phase-based-development.md](phase-based-development.md) -- When to add what.
- [build-only-what-you-need.md](build-only-what-you-need.md) -- The YAGNI philosophy.
- [tradeoff.md](tradeoff.md) -- Simplicity vs capability trade-offs.
- [database.md](database.md) -- PostgreSQL as the single dependency.
