# Caffeine (In-Memory Cache)

[日本語版はこちら](caffeine-cache.ja.md)

---

## What is it?

Caffeine is a high-performance, in-memory caching library for Java -- it stores frequently-used data in your application's memory (RAM) so you do not have to fetch it from the database every time.

Think of it like a sticky note on your desk. You could look up your coworker's phone extension in the company directory every time you need it (going to the database). Or you could write it on a sticky note and keep it on your desk (caching). The sticky note is faster, but it can get outdated if your coworker changes desks, and you can only fit so many sticky notes before your desk is a mess. Caffeine is a smart system that manages your sticky notes -- it keeps the most useful ones, throws away old ones automatically, and makes sure you are not using a number that changed an hour ago.

---

## Why does it matter?

Databases are fast, but memory is faster. Much faster:

```
  Time to retrieve data:
  ┌──────────────────────────────────────┐
  │ From RAM (Caffeine):     ~100ns      │  ← 0.0001 milliseconds
  │ From PostgreSQL:         ~1-5ms      │  ← 10,000-50,000x slower
  │ From a remote API:       ~50-500ms   │  ← way slower
  └──────────────────────────────────────┘
```

For data that is read frequently but changes rarely, caching is a huge performance win. Instead of asking the database "is this session valid?" 100 times per second, you ask once, remember the answer for 30 seconds, and serve the next 99 requests from memory.

### Why Caffeine specifically?

There are several Java caching libraries. Caffeine is the best because:

| Feature | Caffeine |
|---------|----------|
| **Speed** | Fastest Java cache (near-optimal hit rates) |
| **Eviction** | Uses Window TinyLFU algorithm (smarter than LRU) |
| **Size-based limits** | Set max entries or max memory weight |
| **Time-based expiry** | Expire after write or after last access |
| **Statistics** | Built-in hit/miss rate tracking |
| **Thread-safe** | Safe to use from multiple threads simultaneously |

---

## How volta uses Caffeine

volta-auth-proxy uses in-memory caching for rate limiting and performance-critical lookups.

### Rate limiting

When a user (or attacker) sends too many requests, volta needs to track request counts per IP address or per user. This needs to be extremely fast -- every single request checks the rate limiter.

```
  Rate limiting with cache:

  Request comes in from IP 192.168.1.1
  ┌─────────────────────────────────────┐
  │ 1. Check cache: "192.168.1.1" → 47  │  ← 47 requests this minute
  │ 2. Increment: 47 → 48               │
  │ 3. Is 48 > limit (60)? No → allow   │
  └─────────────────────────────────────┘
  Time: ~0.001ms (microseconds)

  Without cache (database):
  ┌─────────────────────────────────────┐
  │ 1. SELECT count FROM rate_limits    │
  │    WHERE ip = '192.168.1.1'         │
  │ 2. UPDATE rate_limits SET count=48  │
  │ 3. Is 48 > 60? No → allow          │
  └─────────────────────────────────────┘
  Time: ~2-5ms (thousands of times slower)
```

Rate limiting data does not need to survive a restart. If volta restarts, the counters reset to zero. That is fine -- the worst case is that a rate-limited user gets a fresh allowance. This makes in-memory caching perfect for this use case.

### Session caching

When Traefik sends a [ForwardAuth](forwardauth.md) request to volta, volta needs to look up the user's session. This happens on every single request to every protected app. Caching session data for a short period (seconds) avoids hitting the database on every request:

```
  Without session cache:
  Request 1 → DB query (3ms) → session valid
  Request 2 → DB query (3ms) → session valid (same session, 1s later)
  Request 3 → DB query (3ms) → session valid (same session, 2s later)

  With session cache (30s TTL):
  Request 1 → DB query (3ms) → cache result → session valid
  Request 2 → cache hit (0.001ms) → session valid
  Request 3 → cache hit (0.001ms) → session valid
  ... 30 seconds pass, cache entry expires ...
  Request N → DB query (3ms) → cache result → session valid
```

The trade-off: if a session is revoked, it can take up to 30 seconds for the cache to expire and the revocation to take effect. volta accepts this trade-off because:

1. JWTs already have a 5-minute validity window
2. Session revocation is a rare event (logout, admin action)
3. The performance gain on every request is worth the brief delay

---

## The "sticky note" analogy in detail

```
  Caffeine cache is like a smart sticky note system:

  ┌─────────────────────────────────┐
  │ Your desk (RAM)                 │
  │                                 │
  │  ┌──────────┐ ┌──────────┐     │
  │  │ Session A │ │ Rate:    │     │
  │  │ valid     │ │ IP .1.1  │     │
  │  │ until 3pm │ │ = 23 req │     │
  │  └──────────┘ └──────────┘     │
  │                                 │
  │  Rules:                         │
  │  - Max 1000 sticky notes        │
  │  - Throw away after 30 seconds  │
  │  - Most-used notes stay longer  │
  └─────────────────────────────────┘

  If the sticky note exists → read it (fast!)
  If not → look it up in the filing cabinet
           (database), then make a new sticky note
```

---

## Simple code example

```java
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Duration;

// Create a cache that holds up to 10,000 entries
// and expires entries 30 seconds after they are written
Cache<String, SessionInfo> sessionCache = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(Duration.ofSeconds(30))
    .build();

// Check cache first, fall back to database
SessionInfo session = sessionCache.get(sessionId, key -> {
    // This lambda only runs on cache miss
    return database.lookupSession(key);
});
```

The `get` method is the key: it checks the cache first. If the entry is there (cache hit), it returns instantly. If not (cache miss), it calls the lambda to load from the database, stores the result in the cache, and returns it.

---

## Further reading

- [Caffeine GitHub](https://github.com/ben-manes/caffeine) -- Official documentation and benchmarks.
- [rate-limiting.md](rate-limiting.md) -- How volta rate-limits requests (uses in-memory counters).
- [forwardauth.md](forwardauth.md) -- The hot path where session caching matters most.
- [hikaricp.md](hikaricp.md) -- The database connection pool (what you fall back to on cache miss).
