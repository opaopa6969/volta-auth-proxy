# Single Process

[日本語版はこちら](single-process.ja.md)

---

## What is it?

A single-process application is one running program that does everything it needs to do within itself, without relying on other running programs to handle parts of the work. All the logic, all the data handling, all the decisions happen inside one program.

Think of it like a Swiss Army knife vs a full toolbox. The Swiss Army knife has a blade, a screwdriver, a bottle opener, and scissors -- all in one tool. You carry one thing. When you need scissors, you fold them out. A full toolbox has separate tools, each better at its job, but you need to carry a box, find the right tool, and put it back. For a camping trip (small scale), the Swiss Army knife wins. For building a house (large scale), the toolbox wins.

---

## What is the alternative?

The alternative is multiple processes communicating over the network:

```
Single process (volta):
┌─────────────────────────────────┐
│ volta-auth-proxy                 │
│                                 │
│ [OIDC] → [Session] → [JWT]     │
│          → [ForwardAuth]        │
│          → [Internal API]       │
│                                 │
│ All method calls. No network.   │
└─────────────────────────────────┘

Multiple processes (e.g., Ory stack):
┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐
│ Kratos   │──►│ Hydra    │──►│ Keto     │──►│ Oathkeeper│
│ (identity)│   │ (OAuth)  │   │ (perms)  │   │ (proxy)   │
└──────────┘   └──────────┘   └──────────┘   └──────────┘
  HTTP calls between processes. Each can fail independently.
```

---

## Why single process is simpler

### Debugging

When something goes wrong in a single process, you get one stack trace that shows exactly what happened:

```
Error in single process:
  NullPointerException at SessionService.java:47
  called from ForwardAuthHandler.java:23
  called from Router.java:15

  → Open SessionService.java, line 47. Found the bug.
```

When something goes wrong across multiple processes, you get... a timeout:

```
Error across processes:
  "Connection timed out after 5000ms connecting to session-service:8080"

  → Which process is broken? Session service? Network? DNS?
    Load balancer? The calling service? Check logs in all 4 services.
    Correlate timestamps. Hope the logs are in sync.
```

### Fewer failure points

A single process has exactly one thing that can crash: itself. A multi-process system has N things that can crash, plus the network connections between them:

```
Single process failure points:
  1. The volta process
  2. The database connection
  Total: 2

Multi-process failure points:
  1. Process A
  2. Process B
  3. Process C
  4. Process D
  5. Network A→B
  6. Network B→C
  7. Network C→D
  8. DNS resolution
  9. Load balancers
  Total: 9+
```

### Faster

Method calls within a process take nanoseconds. HTTP calls between processes take milliseconds. That is a 1,000x difference:

```
Internal method call:  ~0.0001ms (100 nanoseconds)
HTTP call to another service: ~1-5ms

For a ForwardAuth check that involves session lookup + JWT issue:
  Single process: ~0.0002ms for internal calls
  Multi-process:  ~2-10ms for HTTP calls between services
```

### Simpler deployment

One process means one Docker container, one health check, one log stream, one monitoring target:

```
Deploy single process:
  docker run volta-auth-proxy
  Done.

Deploy multi-process:
  docker-compose up
    - oidc-service
    - session-service
    - jwt-service
    - forwardauth-service
    - redis (for inter-service communication)
  Plus: health checks for each, log aggregation, service discovery.
```

---

## The trade-off

Single process means you cannot scale individual components independently. If JWT signing is CPU-heavy and session lookup is IO-heavy, in a multi-process system you could scale them separately. In a single process, you scale everything together.

For volta's target use case (thousands of users, not millions), this is not a problem. One volta instance behind one PostgreSQL instance handles more than enough load. If you need more, you add another volta instance (horizontal scaling) and share the database.

---

## In volta-auth-proxy

volta runs as a single Java process that handles OIDC login, session management, JWT issuance, ForwardAuth verification, and the internal API -- all as method calls within one JVM, giving simpler debugging, fewer failure points, and sub-millisecond internal communication.

---

## Further reading

- [microservice.md](microservice.md) -- The multi-process alternative and why volta chose not to use it.
- [stack-trace.md](stack-trace.md) -- How single process makes debugging easier.
- [debugging.md](debugging.md) -- The debugging advantage of one process.
- [fat-jar.md](fat-jar.md) -- How volta packages everything into one deployable file.
