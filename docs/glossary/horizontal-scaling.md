# Horizontal Scaling

[日本語版はこちら](horizontal-scaling.ja.md)

---

## What is it?

Horizontal scaling means adding more server instances to handle more traffic, instead of making one server bigger. You go from one copy of your application to two, then four, then ten -- each handling a portion of the total requests.

Think of it like a grocery store. Vertical scaling is making one cashier work faster (better hardware). Horizontal scaling is opening more checkout lanes (more instances). There is a limit to how fast one cashier can scan items, but you can always open another lane. The challenge is coordinating: making sure customers do not accidentally go to a "closed" lane, and that the inventory system stays consistent across all lanes.

Horizontal scaling is how modern web applications handle millions of users. Instead of buying one massive, expensive server, you run many smaller, cheaper servers.

---

## Why does it matter?

- **Vertical scaling has a ceiling.** The biggest server money can buy still has a maximum CPU and RAM. Horizontal scaling has no theoretical limit.
- **Horizontal scaling enables redundancy.** If one instance dies, the others keep serving traffic. This is the foundation of [high availability](high-availability.md).
- **Cost efficiency.** Three small servers are often cheaper than one large one with equivalent total capacity.
- **Zero-downtime deployments.** You can update instances one at a time (rolling deploy) without taking the whole system offline.
- **Geographic distribution.** Instances can run in different data centers closer to users.

---

## How does it work?

### Vertical vs horizontal scaling

```
  Vertical Scaling:                Horizontal Scaling:
  (bigger server)                  (more servers)

  Before:        After:            Before:         After:
  ┌──────┐     ┌──────────┐       ┌──────┐      ┌──────┐ ┌──────┐
  │ 2 CPU│     │  8 CPU   │       │ 2 CPU│      │ 2 CPU│ │ 2 CPU│
  │ 4 GB │     │  32 GB   │       │ 4 GB │      │ 4 GB │ │ 4 GB │
  │      │     │          │       │      │      │      │ │      │
  └──────┘     └──────────┘       └──────┘      └──────┘ └──────┘
  1 server      1 bigger server    1 server       2 servers
  Cost: $$$     Cost: $$$$$$       Cost: $$$      Cost: $$$$$$
                                                  (but more flexible)
```

### Requirements for horizontal scaling

To run multiple instances of the same application, you need:

| Requirement | Why | Solution |
|------------|-----|---------|
| **Stateless application** | Instances cannot store state locally | Store state in shared services |
| **Shared database** | All instances read/write the same data | PostgreSQL (already shared) |
| **Shared session store** | Session on instance 1 must be readable by instance 2 | [Redis](redis.md) |
| **Load balancer** | Distribute requests across instances | [Traefik](load-balancer.md) |
| **Shared cache** | Rate limiting must count across all instances | [Redis](redis.md) |

### The scaling flow

```
  Single instance (Phase 1):

  All traffic → ┌──────────┐ → PostgreSQL
                │ volta (1) │
                └──────────┘

  Horizontally scaled (Phase 2):

                ┌──────────────┐
  All traffic → │Load Balancer │
                └──┬───┬───┬──┘
                   │   │   │
                ┌──┴┐ ┌┴──┐ ┌┴──┐
                │ v1│ │ v2│ │ v3│  ← identical instances
                └─┬─┘ └─┬─┘ └─┬─┘
                  │     │     │
                  ▼     ▼     ▼
               ┌──────────────────┐
               │    PostgreSQL    │ ← shared database
               └──────────────────┘
               ┌──────────────────┐
               │      Redis       │ ← shared sessions + cache
               └──────────────────┘
```

### What "stateless" means

An application is stateless if any instance can handle any request without needing data that only lives in that specific instance's memory.

```
  STATEFUL (cannot scale horizontally):
  ┌──────────┐
  │ Instance 1│
  │           │
  │ Session A │ ← only instance 1 knows about session A
  │ Session B │
  └──────────┘

  Request for session A hits instance 2 → FAIL (session not found)

  STATELESS (can scale horizontally):
  ┌──────────┐  ┌──────────┐
  │ Instance 1│  │ Instance 2│
  │           │  │           │
  │ (no local │  │ (no local │
  │  sessions)│  │  sessions)│
  └─────┬─────┘  └─────┬─────┘
        │              │
        ▼              ▼
  ┌──────────────────────┐
  │  Redis (all sessions)│
  └──────────────────────┘

  Request for session A hits instance 2 → checks Redis → FOUND
```

---

## How does volta-auth-proxy use it?

### Phase 1: Single instance (current)

volta currently runs as a [single process](single-process.md). Session state lives in PostgreSQL with a [Caffeine](caffeine-cache.md) local cache. This is simpler to operate and sufficient for the current scale.

### Phase 2: Horizontal scaling (planned)

Phase 2 adds the infrastructure needed to run multiple volta instances:

```
  Phase 2 architecture:

  ┌──────────────────────────────────────────┐
  │              Internet                     │
  └─────────────────┬────────────────────────┘
                    │
                    ▼
  ┌──────────────────────────────────────────┐
  │         Traefik (Load Balancer)          │
  │   Round-robin across volta instances     │
  └────┬──────────┬──────────┬──────────────┘
       │          │          │
       ▼          ▼          ▼
  ┌────────┐ ┌────────┐ ┌────────┐
  │volta-1 │ │volta-2 │ │volta-3 │
  │        │ │        │ │        │
  │Caffeine│ │Caffeine│ │Caffeine│  ← L1 cache (local)
  └───┬────┘ └───┬────┘ └───┬────┘
      │          │          │
      ▼          ▼          ▼
  ┌──────────────────────────────────┐
  │           Redis                   │  ← L2 cache (shared)
  │   Sessions, rate limits           │
  └──────────────────────────────────┘
      │          │          │
      ▼          ▼          ▼
  ┌──────────────────────────────────┐
  │         PostgreSQL                │  ← Source of truth
  │   Users, tenants, memberships     │
  └──────────────────────────────────┘
```

### What changes from Phase 1 to Phase 2

| Component | Phase 1 | Phase 2 |
|-----------|---------|---------|
| Instances | 1 | 2+ |
| Session cache | Caffeine only | Caffeine + Redis |
| Rate limiting | Caffeine counters | Redis counters |
| Session invalidation | Local cache clear | Redis Pub/Sub broadcast |
| Load balancing | Not needed | Traefik round-robin |
| Deployment | Stop-start | Rolling update |

### Scaling decision criteria

volta should scale horizontally when:
- Single-instance response times degrade under load
- Uptime requirements demand [high availability](high-availability.md) (no single point of failure)
- Geographic distribution is needed (multi-region deployment)

---

## Common mistakes and attacks

### Mistake 1: Scaling before removing state

If your application stores sessions in local memory (Caffeine only) and you add a second instance, half your users will randomly get "session not found" errors. Remove local state dependencies first.

### Mistake 2: Forgetting about database connections

Each volta instance opens its own [connection pool](connection-pool.md). Three instances with 10 connections each means 30 connections to PostgreSQL. Make sure PostgreSQL can handle the total.

### Mistake 3: Uneven load distribution

A misconfigured load balancer might send 90% of traffic to one instance and 10% to another. Use proper health checks and load balancing algorithms.

### Mistake 4: Not testing with multiple instances locally

"Works with one instance" does not mean "works with three instances." Test horizontal scaling in staging with multiple instances and realistic traffic patterns.

### Mistake 5: Scaling as a substitute for optimization

If one instance handles 100 requests/second and you need 200, check if optimization could get you to 200 on one instance before adding complexity. Horizontal scaling adds operational overhead.

---

## Further reading

- [redis.md](redis.md) -- The shared data store that enables horizontal scaling.
- [load-balancer.md](load-balancer.md) -- Distributing traffic across instances.
- [high-availability.md](high-availability.md) -- Horizontal scaling enables fault tolerance.
- [single-process.md](single-process.md) -- volta's Phase 1 single-instance model.
- [what-is-scalability.md](what-is-scalability.md) -- Broader scalability concepts.
