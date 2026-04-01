# Traefik

[日本語版はこちら](traefik.ja.md)

---

## What is it?

Traefik (pronounced "traffic") is a modern reverse proxy and load balancer designed for cloud-native environments. A reverse proxy sits between the internet and your application servers, routing incoming requests to the right destination. Traefik's killer feature is **automatic service discovery** -- it watches Docker, Kubernetes, and other orchestrators and configures itself when services start or stop, without you editing config files.

Think of Traefik like a smart receptionist at a large office building. When a new company moves into Suite 405, the receptionist automatically updates the directory and starts directing visitors there. You do not have to call the receptionist and update the list manually -- they just notice the new nameplate on the door.

Traditional reverse proxies like [nginx](nginx.md) are more like a receptionist who only follows a printed list. Every time a tenant moves in or out, someone has to print a new list and hand it over.

---

## Why does it matter?

In modern deployments, services come and go constantly. Docker containers restart, scale up, scale down. Manually editing proxy config files every time a container changes is painful and error-prone. Traefik eliminates this by reading labels directly from Docker (or annotations from Kubernetes).

Traefik also natively supports **ForwardAuth**, a pattern where Traefik asks an external service "should I allow this request?" before forwarding it. This is exactly how [volta-auth-proxy](forwardauth.md) works -- Traefik delegates every authentication decision to volta.

Without Traefik (or something like it), you would need to either embed authentication logic into every service, or manually configure a traditional proxy to call an auth endpoint. Traefik makes the ForwardAuth pattern a first-class, well-documented feature.

---

## How does it work?

### Architecture overview

```
  Internet
     │
     ▼
  ┌──────────────┐
  │   Traefik    │  (reverse proxy, entrypoint)
  │              │
  │  1. Receive  │
  │     request  │
  │              │
  │  2. Check    │──── ForwardAuth ────► volta-auth-proxy
  │     auth     │◄─── 200 OK ─────────  (or 401/302)
  │              │
  │  3. Forward  │──── Only if 200 ───► Your App
  │     request  │
  └──────────────┘
```

### Key concepts

| Concept | Description |
|---------|-------------|
| **Entrypoint** | A port Traefik listens on (e.g., `:80`, `:443`) |
| **Router** | A rule that matches incoming requests (e.g., `Host(\`app.example.com\`)`) |
| **Service** | The backend that handles the request (your Docker container) |
| **Middleware** | Something that modifies the request/response pipeline (e.g., ForwardAuth, rate limiting, headers) |
| **Provider** | Where Traefik discovers services (Docker, Kubernetes, file, etc.) |

### ForwardAuth middleware

ForwardAuth is a middleware that tells Traefik: "Before forwarding this request, send it to another service first. If that service responds with 200, proceed. If it responds with anything else (401, 302), return that response to the client."

```yaml
# docker-compose labels example
labels:
  - "traefik.http.middlewares.volta-auth.forwardauth.address=http://volta:7070/verify"
  - "traefik.http.middlewares.volta-auth.forwardauth.authResponseHeaders=X-Volta-User,X-Volta-Tenant"
  - "traefik.http.routers.myapp.middlewares=volta-auth"
```

This tells Traefik:
1. Create a middleware called `volta-auth` that calls `http://volta:7070/verify`
2. If volta responds 200, copy `X-Volta-User` and `X-Volta-Tenant` headers to the upstream request
3. Attach this middleware to the `myapp` router

### Auto-discovery with Docker

Traefik watches the Docker socket for container events. When a container starts with Traefik labels, Traefik automatically creates the router, service, and middleware. When the container stops, Traefik removes them. No reload, no restart.

```yaml
services:
  traefik:
    image: traefik:v3.0
    command:
      - "--providers.docker=true"
      - "--entrypoints.web.address=:80"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
    ports:
      - "80:80"
```

### Comparison with other proxies

| Feature | Traefik | [nginx](nginx.md) | [Caddy](caddy.md) |
|---------|---------|-------|-------|
| Auto-discovery (Docker) | Native, first-class | Requires third-party tools | Limited |
| ForwardAuth | Built-in middleware | `auth_request` module | `forward_auth` directive |
| Auto HTTPS (Let's Encrypt) | Built-in | Requires certbot | Built-in (default on) |
| Config style | Labels / YAML / TOML | Custom config language | Caddyfile / JSON |
| Dashboard | Built-in web UI | Third-party (e.g., Amplify) | None built-in |
| Performance (raw throughput) | Good | Excellent | Good |
| Learning curve | Moderate | Steep (for advanced) | Easy |
| Maturity | Since 2015 | Since 2004 | Since 2015 |

---

## How does volta-auth-proxy use it?

Traefik is volta-auth-proxy's **recommended reverse proxy**. The entire ForwardAuth pattern that volta relies on was designed with Traefik as the primary integration target.

### The recommended stack

```
  Browser ──► Traefik ──► volta-auth-proxy (ForwardAuth)
                 │
                 ├──► App service A (protected)
                 ├──► App service B (protected)
                 └──► Public service C (no auth middleware)
```

volta's Docker Compose examples use Traefik by default. The setup requires:

1. **Traefik** as the edge proxy (handles TLS, routing)
2. **volta-auth-proxy** as the ForwardAuth backend (handles authentication)
3. **Your app services** with Traefik labels that attach the volta-auth middleware

### Why Traefik over alternatives?

volta recommends Traefik because:

- **ForwardAuth is a native, well-documented middleware** -- not an afterthought
- **Docker label-based config** means adding auth to a new service is one line
- **Header forwarding** (`authResponseHeaders`) passes volta's identity headers to upstream services seamlessly
- **The community** has extensive documentation on ForwardAuth patterns

That said, volta also works with [nginx](nginx.md) (`auth_request`) and [Caddy](caddy.md) (`forward_auth`). See those articles for setup details.

---

## Common mistakes and attacks

### Mistake 1: Exposing the Docker socket without read-only

Traefik needs access to `/var/run/docker.sock` to discover services. If you mount it without `:ro` (read-only), a compromised Traefik instance could create or destroy containers. Always use `:ro`.

### Mistake 2: Not protecting the Traefik dashboard

Traefik's built-in dashboard shows all routers, services, and middlewares. If exposed without authentication, attackers can map your entire infrastructure. Either disable it in production or protect it behind volta-auth-proxy.

### Mistake 3: Forgetting authResponseHeaders

If you set up ForwardAuth but do not configure `authResponseHeaders`, your upstream services will not receive the identity headers (like `X-Volta-User`). The request will be authenticated, but the app will not know who the user is.

### Mistake 4: Bypassing the proxy

If your app service is directly reachable (e.g., port published to the host), users can bypass Traefik entirely and skip ForwardAuth. Keep app services on an internal Docker network with no published ports.

### Attack: Header injection

If Traefik does not strip incoming `X-Volta-User` headers before calling ForwardAuth, an attacker could send a fake identity header. Traefik's ForwardAuth middleware replaces these headers with the ones from the auth response, but misconfigured setups could be vulnerable.

---

## Further reading

- [Traefik official documentation](https://doc.traefik.io/traefik/) -- Comprehensive reference.
- [Traefik ForwardAuth middleware](https://doc.traefik.io/traefik/middlewares/http/forwardauth/) -- The specific feature volta uses.
- [forwardauth.md](forwardauth.md) -- How the ForwardAuth pattern works in general.
- [nginx.md](nginx.md) -- Alternative proxy with `auth_request`.
- [caddy.md](caddy.md) -- Alternative proxy with `forward_auth`.
- [reverse-proxy.md](reverse-proxy.md) -- What a reverse proxy is and why you need one.
- [docker.md](docker.md) -- How volta and Traefik are deployed together.
