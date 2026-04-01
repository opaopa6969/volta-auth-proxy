# Connection Pool

[日本語版はこちら](connection-pool.ja.md)

---

## What is it?

A connection pool is a set of pre-opened database connections that are reused across requests instead of being created and destroyed each time. Opening a new database connection is slow -- it involves TCP handshake, authentication, and protocol negotiation. A connection pool does this work once and keeps the connections warm and ready.

Think of it like a taxi stand at an airport. Without a pool, every traveler (request) must call a taxi company, wait for a car to drive over, take the ride, and then the car drives back to the depot. With a pool (taxi stand), a line of taxis is already waiting. You hop in, ride to your destination, and the taxi immediately returns to the stand for the next passenger. The pool is the standing line of ready-to-go taxis.

In volta-auth-proxy, the connection pool is managed by HikariCP, the fastest connection pool library for Java.

---

## Why does it matter?

- **Performance.** Opening a new PostgreSQL connection takes 5-50ms. Reusing a pooled connection takes <1ms. For a [ForwardAuth](forwardauth.md) check on every request, this difference is enormous.
- **Resource management.** Databases have a maximum number of connections they can handle. Without a pool, a traffic spike could open thousands of connections and crash PostgreSQL.
- **Reliability.** The pool handles connection failures, stale connections, and reconnection automatically.
- **Predictability.** A fixed pool size means predictable resource usage, which makes capacity planning possible.

---

## How does it work?

### Without a connection pool

```
  Request 1: Open connection (30ms) → Query (2ms) → Close (1ms)
  Request 2: Open connection (30ms) → Query (2ms) → Close (1ms)
  Request 3: Open connection (30ms) → Query (2ms) → Close (1ms)

  Total overhead: 90ms of connection setup for 6ms of actual work
```

### With a connection pool

```
  Startup: Open 10 connections (300ms total, one-time cost)

  Request 1: Borrow connection (<0.1ms) → Query (2ms) → Return (<0.1ms)
  Request 2: Borrow connection (<0.1ms) → Query (2ms) → Return (<0.1ms)
  Request 3: Borrow connection (<0.1ms) → Query (2ms) → Return (<0.1ms)

  Total overhead: ~0.3ms vs 90ms without pooling
```

### Pool lifecycle

```
  ┌────────────────────────────────────────────────┐
  │               Connection Pool                   │
  │                                                  │
  │  ┌──────────────────────────────────────────┐   │
  │  │  Available connections (idle)             │   │
  │  │                                          │   │
  │  │  ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐    │   │
  │  │  │Conn│ │Conn│ │Conn│ │Conn│ │Conn│    │   │
  │  │  │ 1  │ │ 2  │ │ 3  │ │ 4  │ │ 5  │    │   │
  │  │  └────┘ └────┘ └────┘ └────┘ └────┘    │   │
  │  └──────────────────────────────────────────┘   │
  │                                                  │
  │  ┌──────────────────────────────────────────┐   │
  │  │  In-use connections (active)              │   │
  │  │                                          │   │
  │  │  ┌────┐ ┌────┐ ┌────┐                   │   │
  │  │  │Conn│ │Conn│ │Conn│  ← serving         │   │
  │  │  │ 6  │ │ 7  │ │ 8  │    requests        │   │
  │  │  └────┘ └────┘ └────┘                   │   │
  │  └──────────────────────────────────────────┘   │
  │                                                  │
  │  Pool size: 10  │  Active: 3  │  Idle: 5        │
  │  Minimum: 5     │  Maximum: 10                   │
  └────────────────────────────────────────────────┘
```

### How HikariCP manages the pool

```
  Request comes in:
       │
       ▼
  Is there an idle connection?
       │
  ┌────┴────┐
  │         │
  Yes       No
  │         │
  ▼         ▼
  Borrow    Is pool at max size?
  it        │
  (<0.1ms)  ├── No → Create new connection
            │        Return it to request
            │
            └── Yes → Wait (up to connectionTimeout)
                      │
                 ┌────┴────┐
                 │         │
              Connection   Timeout
              returned     exceeded
                 │         │
                 ▼         ▼
              Use it    Throw
                       SQLException
```

### Key HikariCP settings

| Setting | Default | Meaning |
|---------|---------|---------|
| `maximumPoolSize` | 10 | Max connections in the pool |
| `minimumIdle` | same as max | Min idle connections to keep warm |
| `connectionTimeout` | 30000ms | How long to wait for a connection |
| `idleTimeout` | 600000ms | How long an idle connection lives |
| `maxLifetime` | 1800000ms | Max lifetime of any connection |
| `leakDetectionThreshold` | 0 (off) | Warn if connection not returned in time |

---

## How does volta-auth-proxy use it?

### HikariCP configuration

volta uses HikariCP to manage PostgreSQL connections:

```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:postgresql://localhost:5432/volta");
config.setUsername("volta");
config.setPassword("secret");
config.setMaximumPoolSize(10);
config.setMinimumIdle(5);
config.setConnectionTimeout(5000);  // 5 seconds
config.setLeakDetectionThreshold(10000);  // 10 seconds

HikariDataSource dataSource = new HikariDataSource(config);
```

### Connection usage in volta

Every database operation borrows a connection and returns it:

```java
// Session lookup (happens on every ForwardAuth check)
try (Connection conn = dataSource.getConnection()) {
    // Connection borrowed from pool
    PreparedStatement ps = conn.prepareStatement(
        "SELECT * FROM sessions WHERE token_hash = ?"
    );
    ps.setString(1, tokenHash);
    ResultSet rs = ps.executeQuery();
    // ... process result ...
}  // Connection automatically returned to pool
```

The `try-with-resources` block guarantees the connection is returned even if an exception occurs.

### Pool sizing for volta

```
  Pool size considerations:

  ForwardAuth check: ~2ms per query
  Peak requests/second: ~500 (example)

  Connections needed = requests/sec × query_time_sec
                     = 500 × 0.002
                     = 1 connection

  But: other queries run too (user lookup, tenant check)
  And: queries sometimes take longer under load

  Rule of thumb:
  ┌─────────────────────────────────┐
  │ connections = (2 × CPU cores)   │
  │            + number of disks    │
  │                                 │
  │ For a 4-core server:            │
  │ connections = (2 × 4) + 1 = 9  │
  │ Round to 10                     │
  └─────────────────────────────────┘
```

### Phase 2: Pool sizing with multiple instances

When volta scales horizontally, each instance has its own pool:

```
  3 volta instances × 10 connections each = 30 total connections

  PostgreSQL default max_connections: 100
  30 connections = safe

  But: other tools also connect (Flyway, monitoring, admin)
  Reserve: ~20 for non-volta connections
  Available: 80 for volta

  Max instances: 80 ÷ 10 = 8 volta instances
```

---

## Common mistakes and attacks

### Mistake 1: Connection leaks

If code borrows a connection but never returns it (missing `close()` or not using try-with-resources), the pool gradually runs out. Enable `leakDetectionThreshold` to catch these.

### Mistake 2: Pool too large

More connections is not better. Each PostgreSQL connection uses ~10MB of RAM. A pool of 100 connections on a server with 4 CPU cores means 99 connections are waiting anyway. Use the formula above.

### Mistake 3: Pool too small

If the pool is too small, requests queue up waiting for a connection. If `connectionTimeout` is short, they get SQL exceptions. Monitor the pool's active vs total count.

### Mistake 4: Not accounting for horizontal scaling

Adding volta instances without increasing PostgreSQL's `max_connections` leads to "too many connections" errors. Plan pool sizes across all instances.

### Mistake 5: Long-running transactions

A transaction that holds a connection for 30 seconds blocks that connection for all other requests. Keep transactions short, especially in the ForwardAuth path.

---

## Further reading

- [hikaricp.md](hikaricp.md) -- The connection pool library volta uses.
- [database.md](database.md) -- The PostgreSQL database connections pool into.
- [latency.md](latency.md) -- Why connection pooling reduces request latency.
- [horizontal-scaling.md](horizontal-scaling.md) -- Pool sizing with multiple instances.
- [in-memory.md](in-memory.md) -- Caching reduces the need for database connections.
