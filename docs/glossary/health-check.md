# Health Check

[日本語版はこちら](health-check.ja.md)

---

## What is it?

A health check is an endpoint that reports whether a service is alive and working. When you send a request to a health check URL, the service responds with a simple status message -- typically `{"status":"ok"}`. If the service is down or unhealthy, the request either fails or returns an error status.

Think of it like a doctor taking your pulse. They do not need to run a full-body MRI to know if you are alive -- they just check one simple vital sign. A health check does the same for your software: it answers the basic question "are you running?" quickly and cheaply.

Health checks are the foundation of automated operations. Container orchestrators (Docker, Kubernetes), load balancers (Traefik, Nginx), and monitoring systems (Prometheus, Datadog) all use health checks to decide whether to route traffic to a service, restart it, or alert an engineer.

---

## Why does it matter?

Without health checks, you do not know your service is down until a user complains. With health checks:

- **Automatic restart**: Docker/Kubernetes can restart a crashed container within seconds.
- **Traffic routing**: Load balancers stop sending requests to unhealthy instances.
- **Alerting**: Monitoring systems page engineers when health checks fail.
- **Deployment safety**: Rolling deployments wait for the new instance to pass health checks before killing the old one.
- **Dependency awareness**: Health checks can verify that critical dependencies (database, external services) are reachable.

A service without a health check is like a car without a dashboard -- it might run fine, but you have no way to know until it breaks down on the highway.

---

## How does it work?

### Types of health checks

| Type | What it checks | Speed | Use case |
|------|---------------|-------|----------|
| **Liveness** | Is the process running? | Very fast (<10ms) | Container restart decision |
| **Readiness** | Can the service handle requests? | Fast (<100ms) | Load balancer routing |
| **Startup** | Has the service finished initializing? | One-time | Avoid premature health checks |
| **Deep health** | Are all dependencies healthy? | Slower (<500ms) | Detailed status page |

### Typical health check endpoints

```
  GET /healthz          → Liveness check (am I alive?)
  GET /readyz           → Readiness check (can I serve traffic?)
  GET /healthz/detail   → Deep check (are my dependencies OK?)
```

### Health check response format

```json
  // Simple (liveness):
  {
    "status": "ok"
  }

  // Detailed (deep health):
  {
    "status": "ok",
    "checks": {
      "database": { "status": "ok", "latency_ms": 2 },
      "disk_space": { "status": "ok", "free_gb": 42 },
      "memory": { "status": "ok", "used_percent": 65 }
    },
    "uptime_seconds": 86400
  }
```

### How orchestrators use health checks

```
  Docker Compose / Kubernetes
         │
         │  Every 10 seconds:
         │  GET /healthz
         │
         ▼
  ┌──────────────────────┐
  │  Health check passes │──► Service is healthy
  │  (HTTP 200)          │    Continue routing traffic
  └──────────────────────┘

  ┌──────────────────────┐
  │  Health check fails  │──► 3 consecutive failures?
  │  (timeout/5xx/no     │    ├── Yes: Restart container
  │   response)          │    └── No:  Try again next interval
  └──────────────────────┘
```

### Health check configuration in Docker

```yaml
  services:
    volta-auth-proxy:
      image: volta-auth-proxy:latest
      healthcheck:
        test: ["CMD", "curl", "-f", "http://localhost:8080/healthz"]
        interval: 10s
        timeout: 5s
        retries: 3
        start_period: 5s
```

---

## How does volta-auth-proxy use it?

volta exposes a health check endpoint at `GET /healthz` that returns `{"status":"ok"}` with HTTP 200.

### volta's health check design

```java
  // In Main.java route setup:
  app.get("/healthz", ctx -> {
      ctx.json(Map.of("status", "ok"));
  });
```

This is deliberately simple:

| Design decision | Rationale |
|----------------|-----------|
| **No authentication** | Health checks must be accessible without a session |
| **No database query** | Keeps the check fast and avoids false negatives from slow queries |
| **Minimal response** | Returns only `{"status":"ok"}` -- no version, no internal details |
| **No dependency checks** | Phase 1 YAGNI -- SQLite is local, no network dependencies to check |

### Why /healthz (not /health)

The `/healthz` convention comes from Google's Borg/Kubernetes ecosystem. The `z` suffix is a convention (like `/readyz`, `/livez`) that avoids collisions with application routes. It has become a de facto standard.

### Health check in volta's Docker setup

volta's Docker configuration uses the health check to determine when the container is ready:

```yaml
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8080/healthz"]
    interval: 10s
    timeout: 5s
    retries: 3
    start_period: 2s   # volta starts in ~200ms, so 2s is generous
```

Because volta starts in approximately 200ms (see [startup.md](startup.md)), the `start_period` can be very short. Compare this to Keycloak, which needs a `start_period` of 60 seconds or more.

### Traefik integration

When volta runs behind Traefik, Docker labels configure Traefik to use the health check for routing decisions. See [docker-label.md](docker-label.md) for details.

---

## Common mistakes and attacks

### Mistake 1: Health check requires authentication

If the health check endpoint requires a session or API key, the orchestrator cannot check it. Health checks must be publicly accessible (or accessible within the internal network).

### Mistake 2: Health check is too expensive

A health check that queries the database, calls external APIs, and computes checksums will be slow and may itself cause failures under load. Keep liveness checks cheap. Use a separate deep-health endpoint if you need detailed status.

### Mistake 3: Health check reveals sensitive information

```
  BAD:
  {
    "status": "ok",
    "version": "1.2.3",
    "database": "sqlite:///var/lib/volta/data.db",
    "java_version": "21.0.1",
    "uptime": "3 days 4 hours"
  }

  GOOD:
  {
    "status": "ok"
  }
```

Exposing internal details helps attackers understand your stack. Keep the response minimal.

### Mistake 4: No health check at all

Without a health check, the orchestrator assumes the container is healthy as long as the process is running. But the process might be running in a broken state (deadlocked, out of memory, database connection lost). A health check catches these cases.

### Mistake 5: Health check always returns 200

A health check that returns `{"status":"ok"}` even when the application is broken is worse than no health check -- it actively hides problems. If you cannot serve requests, the health check must reflect that.

---

## Further reading

- [startup.md](startup.md) -- volta's ~200ms startup time makes health checks pass almost instantly.
- [docker-label.md](docker-label.md) -- How Traefik uses health checks via Docker labels.
- [sla.md](sla.md) -- Health checks are the foundation of uptime monitoring.
- [Kubernetes Health Check Documentation](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/) -- The standard reference.
