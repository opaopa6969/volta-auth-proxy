# Architecture

[日本語版はこちら](architecture.ja.md)

---

## What is it?

Architecture is the high-level structure of a software system -- which parts exist, what each part does, and how they connect to each other. It is the blueprint that determines how your application is organized before you write any detailed code.

Think of it like a building floor plan. Before construction starts, an architect decides: how many floors, where the entrance goes, where the plumbing runs, which rooms connect to which hallways. You can rearrange furniture later (refactoring code), but moving a load-bearing wall after the building is up is extremely expensive. Software architecture is the same -- the big decisions you make early shape everything that follows.

Architecture does not describe every line of code. It describes the boxes and arrows: "Traefik talks to volta-auth-proxy, which talks to PostgreSQL." The details inside each box are implementation; the boxes and arrows are architecture.

---

## Why does it matter?

- **Bad architecture is expensive to fix later.** Changing a database choice or splitting a monolith into microservices takes months, not days.
- **Architecture determines scalability.** A single-process architecture cannot handle 10x traffic without a plan to scale.
- **Architecture determines security boundaries.** Where authentication happens, which components trust each other, and what data flows where are all architectural decisions.
- **New team members need architecture to onboard.** Without it, they must read every file to understand how things connect.
- **Architecture constrains and enables features.** A multi-tenant architecture enables tenant isolation; a single-tenant one does not.

---

## How does it work?

### Common architecture patterns

| Pattern | Description | Example |
|---------|-------------|---------|
| **Monolith** | Single deployable unit, all code together | volta-auth-proxy (Phase 1) |
| **Microservices** | Many small services, each with own DB | Netflix, Uber |
| **Modular monolith** | Single deploy, but internally separated modules | Shopify |
| **Serverless** | Functions triggered by events, no server management | AWS Lambda |
| **Event-driven** | Components communicate via events/messages | Kafka-based systems |

### Layers within an application

Most web applications follow a layered architecture internally:

```
  ┌─────────────────────────────────────┐
  │          HTTP Layer                  │  ← Routes, request parsing
  │     (Javalin handlers)              │
  ├─────────────────────────────────────┤
  │        Service Layer                │  ← Business logic
  │   (AuthService, TenantService)      │
  ├─────────────────────────────────────┤
  │       Repository Layer              │  ← Database access
  │   (SQL queries, HikariCP)           │
  ├─────────────────────────────────────┤
  │        Database                     │  ← PostgreSQL
  └─────────────────────────────────────┘
```

Each layer only talks to the layer directly below it. The HTTP layer never touches the database directly -- it calls the service layer, which calls the repository layer.

### Architectural decisions are trade-offs

Every architecture choice has trade-offs. There is no "best" architecture -- only the best architecture for your constraints:

```
  Monolith                          Microservices
  ┌────────────────┐               ┌────┐ ┌────┐ ┌────┐
  │  Everything     │               │Auth│ │User│ │Bill│
  │  in one process │               │    │ │    │ │    │
  └────────────────┘               └────┘ └────┘ └────┘

  + Simple to deploy                + Scale independently
  + Simple to debug                 + Team autonomy
  + No network hops                 + Fault isolation
  - Scales as one unit              - Network complexity
  - One bug can crash all           - Distributed debugging
  - Team bottleneck at scale        - Operational overhead
```

---

## How does volta-auth-proxy use it?

### volta's architecture (Phase 1)

volta-auth-proxy follows a **modular monolith** pattern: a single deployable JAR with clear internal separation.

```
  ┌──────────────────────────────────────────────────────┐
  │                    Internet                          │
  └───────────────────────┬──────────────────────────────┘
                          │
                          ▼
  ┌──────────────────────────────────────────────────────┐
  │                    Traefik                           │
  │            (reverse proxy + TLS)                     │
  │                                                      │
  │   ForwardAuth ──┐        Route to ──┐               │
  └─────────────────┼───────────────────┼───────────────┘
                    │                   │
                    ▼                   ▼
  ┌──────────────────────┐   ┌─────────────────────┐
  │  volta-auth-proxy     │   │  Your App            │
  │                        │   │  (reads X-Volta-*    │
  │  ┌──────────────────┐ │   │   headers)           │
  │  │ OIDC Login Flow  │ │   └─────────────────────┘
  │  │ Session Mgmt     │ │
  │  │ JWT Issuance     │ │
  │  │ Tenant Resolution│ │
  │  │ RBAC Enforcement │ │
  │  │ Invitation Flow  │ │
  │  │ Internal API     │ │
  │  └──────────────────┘ │
  │           │            │
  │           ▼            │
  │  ┌──────────────────┐ │
  │  │   PostgreSQL      │ │
  │  │   (9 tables)      │ │
  │  └──────────────────┘ │
  │                        │
  │  ┌──────────────────┐ │
  │  │ Caffeine Cache   │ │
  │  │ (in-memory)      │ │
  │  └──────────────────┘ │
  └──────────────────────┘
```

### Key architectural decisions in volta

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Framework | Javalin (lightweight) | Full control, no magic, minimal dependencies |
| Auth approach | Self-built, not a library | Understand every line; avoid [config hell](config-hell.md) |
| Template engine | [jte](jte.md) | Type-safe, fast, simple |
| Database | PostgreSQL | Reliable, widely supported |
| Caching | [Caffeine](caffeine-cache.md) | In-process, no external dependency |
| Token format | [JWT RS256](rs256.md) | Asymmetric -- apps verify without the secret |
| Deployment | [Single process](single-process.md) | Simple operations in Phase 1 |
| Multi-tenancy | [Tenant resolution](tenant-resolution.md) via subdomain | Clean isolation at the URL level |

### Phase 2 architecture evolution

```
  Phase 1 (current):              Phase 2 (planned):
  ┌───────────┐                   ┌───────────────┐
  │ volta (1) │                   │ Load Balancer  │
  │           │                   └──┬─────────┬──┘
  │ Caffeine  │                      │         │
  │ (local)   │                   ┌──┴──┐   ┌──┴──┐
  └─────┬─────┘                   │volta│   │volta│
        │                         │  1  │   │  2  │
        ▼                         └──┬──┘   └──┬──┘
  ┌───────────┐                      │         │
  │ Postgres  │                      ▼         ▼
  └───────────┘                   ┌──────┐ ┌──────┐
                                  │Postgres│ │Redis │
                                  └──────┘ └──────┘

  Caffeine → Redis (shared sessions)
  1 instance → N instances (horizontal scaling)
```

---

## Common mistakes and attacks

### Mistake 1: Premature microservices

Splitting into microservices before you need to adds enormous complexity -- network calls, service discovery, distributed tracing, eventual consistency. Start with a monolith, split when the pain is real. volta intentionally starts as a single process.

### Mistake 2: No clear boundaries between layers

When HTTP handlers directly execute SQL queries, you lose the ability to test business logic independently, swap databases, or add caching layers cleanly.

### Mistake 3: Ignoring the data flow

Architecture diagrams that show boxes but not data flow hide the most important security questions: "Where does the user's password travel? Which component holds the private key? Can an app read another tenant's data?"

### Mistake 4: Architecture astronautics

Over-engineering the architecture for problems you do not have yet. A [phase-based approach](phase-based-development.md) -- starting simple and evolving as needed -- is more practical.

---

## Further reading

- [minimum-viable-architecture.md](minimum-viable-architecture.md) -- Start with just enough architecture.
- [single-process.md](single-process.md) -- volta's Phase 1 deployment model.
- [integration.md](integration.md) -- How volta connects with external systems.
- [horizontal-scaling.md](horizontal-scaling.md) -- Phase 2 architectural evolution.
- [phase-based-development.md](phase-based-development.md) -- Why volta evolves architecture over time.
