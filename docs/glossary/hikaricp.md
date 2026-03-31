# HikariCP

[日本語版はこちら](hikaricp.ja.md)

---

## What is it?

HikariCP is a database connection pool for Java -- it manages a set of reusable database connections so your application does not have to create a new connection every time it needs to talk to the database.

Think of it like phone lines at a call center. Without a pool, every time a customer calls, the call center would install a brand new phone line, use it for one call, then rip it out. That is absurd -- installing a phone line takes time and money. Instead, the call center has 10 phone lines permanently installed. When a customer calls, they get assigned an available line. When they hang up, the line becomes available for the next caller. HikariCP is the system that manages those phone lines for your database.

("Hikari" means "light" in Japanese, reflecting that this pool is extremely fast and lightweight.)

---

## Why does it matter?

Creating a database connection is expensive. Each connection involves:

1. Opening a TCP socket to the database server
2. Performing a TLS handshake (if encrypted)
3. Authenticating (username/password)
4. Negotiating protocol parameters
5. Allocating server-side resources

This can take 50-200ms per connection. If your web application creates a new connection for every HTTP request and you get 100 requests per second, that is 100 connection setups per second -- a massive waste of time and resources.

```
  Without connection pool:
  Request 1 → open connection (100ms) → query (5ms) → close connection
  Request 2 → open connection (100ms) → query (5ms) → close connection
  Request 3 → open connection (100ms) → query (5ms) → close connection
  Total: 315ms for 3 requests

  With HikariCP:
  (connections already open, waiting in the pool)
  Request 1 → borrow connection (0.1ms) → query (5ms) → return to pool
  Request 2 → borrow connection (0.1ms) → query (5ms) → return to pool
  Request 3 → borrow connection (0.1ms) → query (5ms) → return to pool
  Total: 15.3ms for 3 requests
```

HikariCP keeps a pool of pre-established connections ready to go. When your code needs a connection, it borrows one from the pool (microseconds). When done, it returns the connection to the pool instead of closing it. The connection stays alive for the next request.

---

## Why HikariCP specifically?

There are several Java connection pools: DBCP2, C3P0, Tomcat Pool, HikariCP. HikariCP is the most popular because:

| Feature | HikariCP |
|---------|----------|
| **Speed** | Fastest Java connection pool (benchmarked) |
| **Size** | ~130KB JAR, minimal dependencies |
| **Reliability** | Used in production by millions of apps |
| **Defaults** | Sensible defaults that work well out of the box |
| **Monitoring** | Built-in metrics via Micrometer/Dropwizard |

It is the default connection pool in Spring Boot, which says a lot about its quality and reliability.

---

## How volta uses HikariCP

volta-auth-proxy uses HikariCP to manage its connections to PostgreSQL. Every database operation -- session lookups, user queries, tenant resolution, audit logging -- goes through the HikariCP pool.

```
  volta-auth-proxy
  ┌─────────────────────────────────────┐
  │                                     │
  │  ForwardAuth handler ──┐            │
  │  Login handler ────────┤            │
  │  API handlers ─────────┤            │
  │  Flyway migrations ────┤            │
  │                        ▼            │
  │  ┌─────────────────────────────┐    │
  │  │  HikariCP Connection Pool   │    │
  │  │  ┌────┐ ┌────┐ ┌────┐      │    │
  │  │  │conn│ │conn│ │conn│ ...   │    │
  │  │  └────┘ └────┘ └────┘      │    │
  │  └─────────────┬───────────────┘    │
  │                │                    │
  └────────────────┼────────────────────┘
                   │
                   ▼
  ┌─────────────────────────────────────┐
  │  PostgreSQL                         │
  │  (sessions, users, tenants,         │
  │   audit logs, etc.)                 │
  └─────────────────────────────────────┘
```

### Pool configuration in volta

volta configures HikariCP with sensible defaults:

```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl(databaseUrl);
config.setUsername(user);
config.setPassword(password);
config.setMaximumPoolSize(10);  // max 10 connections
```

Key settings:

- **Maximum pool size:** How many connections can be open at once. volta uses a small pool (default 10) because it is lightweight and does not need many concurrent queries.
- **Connection timeout:** How long to wait for a connection from the pool before failing.
- **Idle timeout:** How long an unused connection stays in the pool before being closed.

### Why a small pool works

volta is designed to be efficient. Each request does minimal database work:

- ForwardAuth: 1-2 queries (session lookup + optional tenant check)
- Login: 2-3 queries (user lookup + session creation)
- API calls: 1-3 queries

With 10 connections and queries taking ~5ms each, volta can handle ~2,000 requests per second with just 10 pool connections. This is why volta runs with ~30MB RAM -- it does not need a large pool.

---

## Simple example

Here is what using HikariCP looks like in code:

```java
// Create the pool (once, at application startup)
HikariDataSource ds = new HikariDataSource(config);

// Use a connection (this borrows from the pool)
try (Connection conn = ds.getConnection()) {
    PreparedStatement stmt = conn.prepareStatement(
        "SELECT * FROM sessions WHERE id = ?"
    );
    stmt.setObject(1, sessionId);
    ResultSet rs = stmt.executeQuery();
    // ... use the result ...
}
// Connection automatically returned to pool when try-block ends
```

The `try-with-resources` block ensures the connection is returned to the pool even if an exception occurs. This is critical -- leaked connections (borrowed but never returned) will eventually exhaust the pool.

---

## Further reading

- [HikariCP GitHub](https://github.com/brettwooldridge/HikariCP) -- Official documentation and benchmarks.
- [flyway.md](flyway.md) -- Flyway also uses the HikariCP pool for database migrations.
- [fat-jar.md](fat-jar.md) -- HikariCP is one of the libraries bundled in volta's JAR.
- [self-hosting.md](self-hosting.md) -- Efficient connection pooling is why volta runs on minimal resources.
