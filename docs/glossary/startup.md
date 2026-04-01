# Startup (Service Startup)

[日本語版はこちら](startup.ja.md)

---

## What is it?

Startup is the time and process of a service going from "not running" to "ready to handle requests." When you start a web application, it must load configuration, initialize the database connection, set up routes, and begin listening for incoming requests. Startup time is the duration from process launch to the moment the first request can be served.

Think of it like opening a restaurant in the morning. The chef arrives, turns on the ovens, preps the ingredients, checks the reservations, and unlocks the front door. Only after all that is the restaurant "started" and ready for customers. Some restaurants take 10 minutes to open (food truck), others take an hour (fine dining). The difference matters when customers are waiting outside.

In software, startup time ranges from milliseconds (lightweight frameworks) to minutes (enterprise application servers). This difference profoundly affects development experience, deployment speed, and operational resilience.

---

## Why does it matter?

Startup time affects almost every aspect of operations:

- **Development speed**: If your application takes 30 seconds to start, every code change costs 30 seconds of waiting. Over a day of development, this adds up to hours.
- **Deployment speed**: Rolling deployments need the new instance to start before the old one stops. Slow startup = slow deployments or downtime.
- **Recovery time**: When a service crashes, it needs to restart. A 200ms restart is invisible to users. A 30-second restart is a minor outage.
- **Scaling speed**: Auto-scaling adds new instances when load increases. If startup takes 30 seconds, you are 30 seconds behind the load spike.
- **Container orchestration**: Kubernetes and Docker health checks have timeouts. Slow startup requires longer timeout configuration and delays readiness.

---

## How does it work?

### What happens during startup

```
  Process starts
       │
       ▼
  1. JVM / Runtime initialization
     (Java: class loading, JIT warmup)
       │
       ▼
  2. Configuration loading
     (Read YAML/env vars, validate settings)
       │
       ▼
  3. Database initialization
     (Connect, run migrations, verify schema)
       │
       ▼
  4. Framework initialization
     (Set up routes, middleware, filters)
       │
       ▼
  5. Dependency initialization
     (Connect to cache, message queue, external services)
       │
       ▼
  6. Start listening on port
     ("Ready to accept requests")
       │
       ▼
  7. Health check passes
     ("I am alive and ready")
```

### Startup time comparison

| Technology | Typical startup | Why |
|-----------|----------------|-----|
| **volta (Javalin)** | ~200ms | Minimal framework, no annotation scanning, no DI container |
| **Spring Boot** | 3-8 seconds | Component scanning, auto-config, DI container initialization |
| **Keycloak** | 20-40 seconds | Full Java EE stack, theme compilation, SPI loading |
| **Node.js (Express)** | 100-500ms | V8 is fast to start, but npm dependency loading varies |
| **Go (net/http)** | 10-50ms | Compiled binary, no runtime initialization |
| **Rails** | 5-15 seconds | Eager loading, asset compilation, gem initialization |

### What makes startup slow

| Factor | Impact | Example |
|--------|--------|---------|
| **Classpath scanning** | Scanning thousands of classes for annotations | Spring's @ComponentScan |
| **Dependency injection** | Building and wiring the object graph | Spring/Guice container |
| **Database migrations** | Running schema changes at startup | Flyway/Liquibase |
| **External connections** | Waiting for remote services to respond | Redis, Kafka, external APIs |
| **JIT compilation** | First-request latency as JVM compiles hot paths | Any JVM app |
| **Configuration parsing** | Complex config with validation and defaults | XML-heavy config (Keycloak) |

---

## How does volta-auth-proxy use it?

volta-auth-proxy starts in approximately **200 milliseconds**. This is a deliberate design achievement, not an accident.

### How volta achieves fast startup

| Decision | Impact on startup |
|----------|------------------|
| **Javalin over Spring Boot** | No component scanning, no DI container. Routes are explicit Java code. |
| **SQLite over PostgreSQL** | No network connection needed. Database is a local file. |
| **No annotation processing** | No classpath scan for @Controller, @Service, @Repository etc. |
| **Minimal dependencies** | ~10 dependencies vs 100+ in Spring Boot. Less to load. |
| **Explicit wiring** | Objects created with `new`, not injected. Constructor runs in microseconds. |
| **No migration framework** | Schema creation is a simple SQL script, not a Flyway migration chain. |

### Startup sequence in volta

```
  t=0ms     JVM starts, loads Main.class
  t=20ms    Configuration loaded from YAML
  t=40ms    SQLite database opened (local file I/O)
  t=60ms    Schema verified / created if needed
  t=80ms    Javalin app created, routes registered
  t=120ms   Middleware configured (CSRF, session, auth)
  t=160ms   Javalin starts Jetty on port 8080
  t=200ms   "volta-auth-proxy started on port 8080"
            Ready to serve requests.
```

### Why 200ms matters for volta's use case

| Scenario | 200ms startup | 30s startup |
|----------|--------------|-------------|
| **Developer restarts** | Instant feedback | Coffee break every change |
| **Docker restart** | Container healthy in <1s | Container unhealthy for 30s+ |
| **Health check** | Passes on first check | Needs long start_period |
| **Crash recovery** | Users notice nothing | Users see errors for 30s |
| **CI/CD pipeline** | Integration tests start immediately | Pipeline waits for startup |

### The development experience difference

```
  volta development cycle:
  ┌──────────────────────────────────────────┐
  │  Edit code → Save → Auto-restart (200ms) │
  │  → See result → Edit again               │
  │  Total cycle: ~1 second                  │
  └──────────────────────────────────────────┘

  Keycloak development cycle:
  ┌──────────────────────────────────────────┐
  │  Edit code → Save → Rebuild (30s)        │
  │  → Wait for startup (30s) → See result   │
  │  → Edit again                            │
  │  Total cycle: ~60+ seconds               │
  └──────────────────────────────────────────┘
```

---

## Common mistakes and attacks

### Mistake 1: Ignoring startup time during development

Slow startup accumulates. If you restart 50 times a day and each restart takes 30 seconds, you lose 25 minutes daily -- over 2 hours per week -- just waiting.

### Mistake 2: Doing too much at startup

Loading every possible configuration, pre-warming every cache, and connecting to every service at startup makes the process fragile. If any dependency is unavailable, the entire application fails to start. Prefer lazy initialization for non-critical dependencies.

### Mistake 3: Not measuring startup time

If you do not measure it, you do not notice it creeping up. Add a log line that prints elapsed time at startup. volta logs `"started on port 8080"` with a timestamp so degradation is immediately visible.

### Mistake 4: Conflating startup time with first-request latency

The application may "start" in 200ms but the first request takes 2 seconds due to JIT compilation, lazy class loading, or cold caches. Measure both.

---

## Further reading

- [javalin.md](javalin.md) -- The framework that makes volta's fast startup possible.
- [health-check.md](health-check.md) -- How fast startup affects health check configuration.
- [yagni.md](yagni.md) -- Fewer features = less startup work.
- [greenfield.md](greenfield.md) -- Technology choices that enable fast startup.
