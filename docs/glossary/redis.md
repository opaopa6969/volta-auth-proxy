# Redis

[日本語版はこちら](redis.ja.md)

---

## What is it?

Redis is an open-source, in-memory data store that can be used as a database, cache, or message broker. It stores data in RAM, making it extremely fast -- far faster than a traditional disk-based database like PostgreSQL.

Think of it like a shared whiteboard in an office. Everyone in the office can read and write on it instantly. If you need to tell all your coworkers something quickly -- like "Meeting Room A is occupied" -- you write it on the whiteboard. Anyone walking by can check it without going through a filing cabinet. PostgreSQL is the filing cabinet (permanent, organized, but slower). Redis is the whiteboard (fast, visible to everyone, but gets erased when you wipe it).

The key difference from application-level caching (like [Caffeine](caffeine-cache.md)) is that Redis runs as a separate process. Multiple application instances can read and write to the same Redis, making it essential for [horizontal scaling](horizontal-scaling.md).

---

## Why does it matter?

- **Shared state across instances.** When you run multiple copies of your application behind a [load balancer](load-balancer.md), they all need to agree on session state. Redis provides this shared memory.
- **Speed.** Redis operations complete in microseconds. Session validation, rate limiting, and cache lookups are nearly instantaneous.
- **Persistence options.** Unlike pure in-memory caches, Redis can optionally persist data to disk, surviving restarts.
- **Data structures.** Redis is not just key-value. It supports strings, hashes, lists, sets, sorted sets, and more -- perfect for things like rate limiting counters and session data.
- **Pub/Sub messaging.** Redis can broadcast messages between application instances (e.g., "session X was revoked, invalidate your local cache").

---

## How does it work?

### Redis architecture

```
  ┌─────────────────────────────────────────┐
  │              Redis Server                │
  │                                          │
  │  ┌──────────────────────────────────┐   │
  │  │           RAM                     │   │
  │  │                                   │   │
  │  │  "session:abc123" → {user, role}  │   │
  │  │  "session:def456" → {user, role}  │   │
  │  │  "rate:192.168.1.1" → 47          │   │
  │  │  "tenant:acme:config" → {...}     │   │
  │  │                                   │   │
  │  └──────────────────────────────────┘   │
  │                                          │
  │  Optional: persist snapshots to disk     │
  └─────────────────────────────────────────┘
        ▲           ▲           ▲
        │           │           │
  ┌─────┴──┐  ┌────┴───┐  ┌───┴────┐
  │ volta  │  │ volta  │  │ volta  │
  │ inst.1 │  │ inst.2 │  │ inst.3 │
  └────────┘  └────────┘  └────────┘
```

### Redis data types

| Type | Use case | Example |
|------|---------|---------|
| **String** | Simple values, counters | `SET rate:1.2.3.4 "47"` |
| **Hash** | Structured objects | `HSET session:abc user_id "u1" role "ADMIN"` |
| **List** | Ordered sequences | Audit log entries |
| **Set** | Unique collections | Active session IDs per user |
| **Sorted Set** | Ranked data | Leaderboards, expiry queues |
| **Key expiry** | Auto-deletion | `EXPIRE session:abc 3600` (1 hour) |

### Basic Redis operations

```
  SET session:abc123 '{"userId":"u1","role":"ADMIN"}' EX 3600
  │                    │                                │
  │                    │                                └─ Expires in 1 hour
  │                    └─ The value (JSON string)
  └─ The key

  GET session:abc123
  → '{"userId":"u1","role":"ADMIN"}'

  DEL session:abc123
  → (key deleted, session invalidated)

  INCR rate:192.168.1.1
  → 48 (atomic increment, perfect for rate limiting)
```

### Caffeine (local) vs Redis (shared)

```
  Caffeine (Phase 1):              Redis (Phase 2):
  ┌────────────┐                   ┌────────────────┐
  │ volta (1)  │                   │    Redis       │
  │            │                   │  (shared)      │
  │ ┌────────┐ │                   └──┬──────┬──────┘
  │ │Caffeine│ │                      │      │
  │ │ cache  │ │                   ┌──┴──┐ ┌─┴───┐
  │ └────────┘ │                   │volta│ │volta│
  └────────────┘                   │  1  │ │  2  │
                                   └─────┘ └─────┘
  Only 1 instance can see          All instances see
  the cached data.                 the same data.

  Session cached in inst.1?        Session cached in Redis?
  inst.2 doesn't know about it.    All instances can read it.
```

---

## How does volta-auth-proxy use it?

### Phase 1: No Redis (current)

In Phase 1, volta runs as a [single process](single-process.md). Sessions are stored in PostgreSQL and cached in [Caffeine](caffeine-cache.md). There is no need for Redis because there is only one instance.

### Phase 2: Redis for shared sessions

When volta scales to multiple instances, the in-memory Caffeine cache becomes a problem. If user A logs in on instance 1, instance 2 does not have that session in its local cache. Redis solves this:

```
  Phase 2 session flow:

  1. User logs in → volta instance 1 creates session
  2. Session stored in PostgreSQL (permanent)
  3. Session cached in Redis (fast shared access)
  4. Next request hits volta instance 2
  5. Instance 2 checks Redis → session found!
  6. User is authenticated without hitting PostgreSQL

  ┌──────────────────────────────────────────────┐
  │  Request flow with Redis:                    │
  │                                               │
  │  Browser → Load Balancer → volta instance 2   │
  │                              │                │
  │                              ▼                │
  │                         Check Redis           │
  │                              │                │
  │                     ┌────────┴────────┐       │
  │                     │                 │       │
  │                  Cache hit         Cache miss  │
  │                     │                 │       │
  │                     ▼                 ▼       │
  │               Return session    Query Postgres │
  │               (fast: <1ms)      Cache in Redis │
  │                                 Return session │
  └──────────────────────────────────────────────┘
```

### Phase 2: Redis for rate limiting

Rate limiting counters must be shared. If instance 1 counts 30 requests from an IP and instance 2 counts 30, the attacker has made 60 requests but each instance thinks they made only 30.

```
  Without Redis (broken):        With Redis (correct):
  ┌──────┐  ┌──────┐            ┌──────┐  ┌──────┐
  │volta1│  │volta2│            │volta1│  │volta2│
  │ 30   │  │ 30   │            │      │  │      │
  └──────┘  └──────┘            └──┬───┘  └───┬──┘
  Total: 60 but each sees 30       │          │
  Attacker bypasses limit!          ▼          ▼
                                 ┌──────────────┐
                                 │    Redis      │
                                 │   count: 60   │
                                 └──────────────┘
                                 Correct total, limit enforced
```

### Phase 2: Redis Pub/Sub for cache invalidation

When a session is revoked on instance 1, instance 2 needs to know. Redis Pub/Sub broadcasts the invalidation:

```
  Admin revokes session on instance 1
       │
       ▼
  Instance 1: DELETE from Postgres + Redis
  Instance 1: PUBLISH "session:revoked" "abc123"
       │
       ▼
  Redis broadcasts to all subscribers
       │
       ▼
  Instance 2: receives message, removes from local Caffeine cache
```

---

## Common mistakes and attacks

### Mistake 1: Exposing Redis to the internet

Redis has no authentication by default. If Redis is accessible from the internet, anyone can read all sessions and impersonate any user. Redis must be on an internal network only.

### Mistake 2: No persistence, no backup

If Redis is your only session store and it crashes, all sessions are lost -- every user is logged out. volta uses Redis as a cache layer with PostgreSQL as the source of truth.

### Mistake 3: Using Redis as the primary database

Redis is a cache and a fast store, not a replacement for PostgreSQL. Session data lives permanently in Postgres; Redis provides fast access.

### Mistake 4: Ignoring Redis memory limits

Redis stores everything in RAM. If you do not set `maxmemory`, Redis will use all available RAM and crash. Configure eviction policies (`maxmemory-policy allkeys-lru`).

### Mistake 5: No Redis high availability

A single Redis instance is a single point of failure. For [high availability](high-availability.md), use Redis Sentinel or Redis Cluster.

---

## Further reading

- [horizontal-scaling.md](horizontal-scaling.md) -- The Phase 2 goal that requires Redis.
- [in-memory.md](in-memory.md) -- Understanding in-memory data storage.
- [caffeine-cache.md](caffeine-cache.md) -- volta's Phase 1 local cache (replaced by Redis in Phase 2).
- [session-storage-strategies.md](session-storage-strategies.md) -- Where sessions can live.
- [high-availability.md](high-availability.md) -- Keeping Redis itself reliable.
