# In-Memory

[日本語版はこちら](in-memory.ja.md)

---

## What is it?

In-memory means storing data in a computer's RAM (Random Access Memory) instead of on a hard drive or SSD. RAM is the fast, volatile memory that loses its contents when the power goes off. Disk storage is slow but permanent; RAM is fast but temporary.

Think of it like your desk versus a filing cabinet. The filing cabinet (disk) stores everything permanently -- even if you leave the office, everything stays in the drawer. Your desktop (RAM) is where you put the documents you are actively working on. Grabbing a paper from your desk takes a fraction of a second; walking to the filing cabinet and searching a drawer takes much longer. But when you leave for the night (the server restarts), everything on your desk is gone.

In-memory storage is used when speed is more important than durability, or when the data is temporary by nature (like rate limiting counters that reset every minute).

---

## Why does it matter?

- **Speed.** RAM access is 100,000x faster than disk access. For operations that happen on every request (session checks, rate limiting), this makes a real difference.
- **Reduced database load.** Caching frequently-accessed data in memory means fewer queries to PostgreSQL, leaving the database free for writes and complex queries.
- **Temporary data needs no persistence.** Rate limiting counters, short-lived caches, and computed values do not need to survive a restart. In-memory is perfect for them.
- **Trade-off awareness.** Understanding in-memory means understanding what you lose on restart and why that is acceptable (or not).

---

## How does it work?

### Memory hierarchy

```
  ┌─────────────────────────────────────────────────────┐
  │              Memory Hierarchy                        │
  │                                                      │
  │  Speed                          Size                 │
  │  ◀────────────────────────────────▶                  │
  │  Fastest                      Largest                │
  │                                                      │
  │  ┌──────────┐                                       │
  │  │CPU Cache │  ~1ns    ~8MB     ← Tiny, fastest     │
  │  └──────────┘                                       │
  │  ┌──────────┐                                       │
  │  │   RAM    │  ~100ns  ~16-64GB  ← In-memory lives  │
  │  └──────────┘                     here               │
  │  ┌──────────┐                                       │
  │  │   SSD    │  ~100μs  ~1-4TB   ← PostgreSQL data   │
  │  └──────────┘                                       │
  │  ┌──────────┐                                       │
  │  │   HDD    │  ~10ms   ~4-16TB  ← Backups, logs     │
  │  └──────────┘                                       │
  │                                                      │
  │  1ns = 0.000001ms                                    │
  │  RAM is ~1000x faster than SSD                       │
  │  RAM is ~100,000x faster than HDD                    │
  └─────────────────────────────────────────────────────┘
```

### What "volatile" means

```
  Server running:                Server restarts:
  ┌────────────────────┐        ┌────────────────────┐
  │ RAM (in-memory)    │        │ RAM (in-memory)    │
  │                    │        │                    │
  │ session_cache: {   │        │ (empty)            │
  │   "abc": valid     │───X───▶│                    │
  │   "def": valid     │  lost! │                    │
  │ }                  │        │                    │
  │                    │        │                    │
  │ rate_limits: {     │        │                    │
  │   "1.2.3.4": 47   │───X───▶│                    │
  │ }                  │  lost! │                    │
  └────────────────────┘        └────────────────────┘

  ┌────────────────────┐        ┌────────────────────┐
  │ Disk (PostgreSQL)  │        │ Disk (PostgreSQL)  │
  │                    │        │                    │
  │ sessions table:    │        │ sessions table:    │
  │   abc: valid       │────────▶│   abc: valid       │
  │   def: valid       │  safe! │   def: valid       │
  └────────────────────┘        └────────────────────┘
```

### In-memory vs disk: when to use which

| Criterion | In-Memory (RAM) | Disk (Database) |
|-----------|-----------------|-----------------|
| Speed | Microseconds | Milliseconds |
| Survives restart | No | Yes |
| Shared across instances | No (local) | Yes |
| Capacity | Limited (GB) | Large (TB) |
| Cost per GB | Expensive | Cheap |
| **Best for** | Caches, counters, hot data | Permanent records, source of truth |

---

## How does volta-auth-proxy use it?

### Caffeine: volta's in-memory cache

volta uses [Caffeine](caffeine-cache.md) for in-memory caching. Caffeine stores data in the JVM's heap memory:

```
  ┌─────────────────────────────────────┐
  │  JVM Heap (volta-auth-proxy)        │
  │                                     │
  │  ┌───────────────────────────────┐  │
  │  │  Caffeine Cache               │  │
  │  │                               │  │
  │  │  Session cache:               │  │
  │  │  ┌────────────────────────┐   │  │
  │  │  │ "sess_abc" → {userId,  │   │  │
  │  │  │              role,     │   │  │
  │  │  │              tenantId} │   │  │
  │  │  └────────────────────────┘   │  │
  │  │                               │  │
  │  │  Rate limit cache:            │  │
  │  │  ┌────────────────────────┐   │  │
  │  │  │ "192.168.1.1" → 47    │   │  │
  │  │  │ "10.0.0.5" → 12       │   │  │
  │  │  └────────────────────────┘   │  │
  │  │                               │  │
  │  │  TTL: 30s (sessions)          │  │
  │  │  TTL: 60s (rate limits)       │  │
  │  └───────────────────────────────┘  │
  └─────────────────────────────────────┘
```

### Use case 1: Session caching

Every [ForwardAuth](forwardauth.md) request must validate the user's session. Without caching, every request hits PostgreSQL:

```
  Without in-memory cache:
  100 requests/sec × 3ms/query = 300ms of DB time per second

  With in-memory cache (30s TTL):
  ~3 cache misses/sec × 3ms = 9ms of DB time per second
  ~97 cache hits/sec × 0.001ms = 0.097ms

  Reduction: 97% fewer database queries
```

### Use case 2: Rate limiting

Rate limiting needs counters that increment with each request. These are perfect for in-memory because:
- They change on every request (writing to DB every time would be slow)
- They are temporary (1-minute windows that reset)
- Losing them on restart is acceptable (users get a fresh allowance)

```java
// In-memory rate limiting with Caffeine
Cache<String, AtomicInteger> rateLimitCache = Caffeine.newBuilder()
    .expireAfterWrite(Duration.ofMinutes(1))
    .build();

int count = rateLimitCache
    .get(clientIp, k -> new AtomicInteger(0))
    .incrementAndGet();

if (count > MAX_REQUESTS_PER_MINUTE) {
    throw new RateLimitExceededException();
}
```

### Phase 2: In-memory as L1 cache

When volta scales horizontally, in-memory Caffeine becomes a local "L1" cache with [Redis](redis.md) as a shared "L2" cache:

```
  Request arrives at volta-2:

  1. Check Caffeine (L1, local, ~0.001ms)
     │
     ├── Hit? → Return immediately
     │
     └── Miss? → Check Redis (L2, shared, ~0.5ms)
                  │
                  ├── Hit? → Store in Caffeine, return
                  │
                  └── Miss? → Query PostgreSQL (~3ms)
                               Store in Redis + Caffeine
                               Return
```

---

## Common mistakes and attacks

### Mistake 1: Treating in-memory as permanent storage

If your only copy of important data is in memory, a server restart destroys it. Always use in-memory as a cache with a durable backing store (PostgreSQL).

### Mistake 2: Unbounded cache size

Without a size limit, the cache grows until the JVM runs out of memory and crashes with `OutOfMemoryError`. Always set `maximumSize` in Caffeine.

### Mistake 3: Stale cache causing security issues

If a session is revoked in the database but the in-memory cache still says "valid," the user remains authenticated until the cache entry expires. Keep TTLs short for security-sensitive data.

### Mistake 4: Ignoring cache in multi-instance deployments

In-memory data is local to one instance. If volta-1 caches a session, volta-2 does not see it. This is why Phase 2 adds [Redis](redis.md) as a shared cache layer.

### Mistake 5: Caching too aggressively

Caching everything with long TTLs makes the application feel fast but can serve stale data. Balance speed against freshness based on the data type.

---

## Further reading

- [caffeine-cache.md](caffeine-cache.md) -- volta's in-memory caching library.
- [redis.md](redis.md) -- External in-memory store for shared caching (Phase 2).
- [connection-pool.md](connection-pool.md) -- Another technique to reduce database load.
- [rate-limiting.md](rate-limiting.md) -- Uses in-memory counters.
- [session-storage-strategies.md](session-storage-strategies.md) -- Choosing where sessions live.
