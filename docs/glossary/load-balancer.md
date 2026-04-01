# Load Balancer

[日本語版はこちら](load-balancer.ja.md)

---

## What is it?

A load balancer is a server that sits between users and your application instances, distributing incoming traffic across multiple copies of your application. Instead of all requests hitting one server, the load balancer spreads them evenly so no single server gets overwhelmed.

Think of it like the host at a busy restaurant. Customers (requests) walk in the door, and the host (load balancer) decides which table section (server instance) to seat them in. A good host spreads diners evenly across sections so no single waiter is overwhelmed while others stand idle. Without a host, all customers would crowd at the first table they see.

A load balancer does three critical things: distributes traffic, detects failed servers (health checks), and removes unhealthy servers from the pool until they recover.

---

## Why does it matter?

- **Enables horizontal scaling.** Without a load balancer, there is no way to split traffic across multiple volta instances.
- **Provides fault tolerance.** If one server crashes, the load balancer stops sending traffic to it. Users experience no interruption.
- **Prevents overload.** Even distribution of traffic prevents any single instance from being the bottleneck.
- **Enables zero-downtime deployments.** Update one instance at a time -- the load balancer routes around the updating instance.
- **Single entry point.** Users connect to one URL. The load balancer handles the complexity of routing to the right instance.

---

## How does it work?

### Basic load balancing

```
  Without load balancer:           With load balancer:

  Users → Server                   Users → ┌──────────────┐
  (all traffic to one)                     │Load Balancer │
                                           └──┬─────┬──┬─┘
                                              │     │  │
                                              ▼     ▼  ▼
                                           ┌───┐ ┌───┐ ┌───┐
                                           │ S1│ │ S2│ │ S3│
                                           └───┘ └───┘ └───┘
                                           33%   33%   33%
```

### Load balancing algorithms

| Algorithm | How it works | Best for |
|-----------|-------------|----------|
| **Round-robin** | Sends to each server in turn: 1, 2, 3, 1, 2, 3... | Equal-capacity servers |
| **Least connections** | Sends to the server with fewest active connections | Varying request durations |
| **Weighted round-robin** | Servers get traffic proportional to their weight | Different-size servers |
| **IP hash** | Same client IP always goes to same server | Session affinity needs |
| **Random** | Random server selection | Simple, surprisingly effective |

### Health checks

The load balancer periodically checks if each server is alive:

```
  Every 10 seconds:
  ┌──────────────┐
  │Load Balancer │
  └──┬─────┬──┬──┘
     │     │  │
     ▼     ▼  ▼
  ┌─────┐ ┌─────┐ ┌─────┐
  │ S1  │ │ S2  │ │ S3  │
  │ 200 │ │ 200 │ │ 503 │ ← unhealthy
  │  ✓  │ │  ✓  │ │  ✗  │
  └─────┘ └─────┘ └─────┘

  After detection:
  ┌──────────────┐
  │Load Balancer │
  └──┬─────┬─────┘
     │     │
     ▼     ▼
  ┌─────┐ ┌─────┐ ┌─────┐
  │ S1  │ │ S2  │ │ S3  │ ← removed from pool
  │ 50% │ │ 50% │ │  -  │
  └─────┘ └─────┘ └─────┘

  S3 recovers → load balancer re-adds it
```

### Layer 4 vs Layer 7 load balancing

```
  OSI Model layers:

  Layer 7 (Application):  HTTP, HTTPS
  ┌──────────────────────────────────────┐
  │ Can inspect: URL path, headers,      │
  │ cookies, request body                │
  │ Example: route /api/* to servers A,  │
  │          route /auth/* to servers B  │
  └──────────────────────────────────────┘

  Layer 4 (Transport):  TCP, UDP
  ┌──────────────────────────────────────┐
  │ Can inspect: IP address, port        │
  │ Cannot see: URL, headers, content    │
  │ Example: route port 443 to servers A │
  └──────────────────────────────────────┘

  Traefik is a Layer 7 load balancer -- it can make
  routing decisions based on HTTP headers and paths.
```

---

## How does volta-auth-proxy use it?

### Traefik as volta's load balancer

volta uses [Traefik](reverse-proxy.md) which serves double duty as both a reverse proxy and a load balancer. In Phase 1, Traefik routes to a single volta instance. In Phase 2, it distributes across multiple instances.

### Phase 1 configuration (single instance)

```
  ┌──────────┐    ┌──────────┐    ┌──────────────┐
  │ Browser  │───▶│ Traefik  │───▶│ volta (1)    │
  └──────────┘    └──────────┘    └──────────────┘
                   No load balancing needed
                   (only one backend)
```

### Phase 2 configuration (multiple instances)

```yaml
# Traefik configuration for load balancing volta
http:
  services:
    volta-auth:
      loadBalancer:
        servers:
          - url: "http://volta-1:8080"
          - url: "http://volta-2:8080"
          - url: "http://volta-3:8080"
        healthCheck:
          path: "/health"
          interval: "10s"
          timeout: "3s"
```

```
  ┌──────────┐    ┌──────────────────────┐
  │ Browser  │───▶│       Traefik         │
  └──────────┘    │                       │
                  │  ForwardAuth ──┐      │
                  │                │      │
                  └────────────────┼──────┘
                                   │
                          ┌────────┴────────┐
                          │  Load Balance   │
                          │  (round-robin)  │
                          └──┬──────┬──────┬┘
                             │      │      │
                             ▼      ▼      ▼
                          ┌─────┐┌─────┐┌─────┐
                          │v-1  ││v-2  ││v-3  │
                          └─────┘└─────┘└─────┘
```

### ForwardAuth with load balancing

When Traefik sends a [ForwardAuth](forwardauth.md) request, the load balancer picks which volta instance handles the auth check:

```
  1. Browser requests app.example.com/dashboard
  2. Traefik ForwardAuth → Load Balancer picks volta-2
  3. volta-2 checks session in Redis → valid
  4. volta-2 returns 200 + X-Volta-* headers
  5. Traefik routes the original request to the app
```

The browser's session cookie works with any volta instance because sessions are stored in [Redis](redis.md), not in any instance's local memory.

---

## Common mistakes and attacks

### Mistake 1: Session affinity ("sticky sessions") dependency

Making the load balancer always route the same user to the same instance (sticky sessions) seems easier but defeats the purpose. If that instance dies, the user loses their session. Use shared state ([Redis](redis.md)) instead.

### Mistake 2: No health checks

Without health checks, the load balancer sends traffic to dead instances. Users get random 502 errors that are hard to debug.

### Mistake 3: Load balancer as single point of failure

If you have one load balancer and it dies, everything is down. For true [high availability](high-availability.md), use redundant load balancers with failover.

### Mistake 4: Not accounting for connection pool limits

Three volta instances behind a load balancer means 3x the database [connections](connection-pool.md). Make sure PostgreSQL's `max_connections` is configured accordingly.

### Mistake 5: Testing with one instance but deploying with many

A bug that only appears when requests alternate between instances (e.g., race conditions in shared state) will not surface in single-instance testing.

---

## Further reading

- [horizontal-scaling.md](horizontal-scaling.md) -- Load balancing enables multiple instances.
- [high-availability.md](high-availability.md) -- Load balancers detect and route around failures.
- [reverse-proxy.md](reverse-proxy.md) -- Traefik serves as both reverse proxy and load balancer.
- [forwardauth.md](forwardauth.md) -- How volta integrates with Traefik's ForwardAuth.
- [redis.md](redis.md) -- Shared state that makes load-balanced sessions work.
