# nginx

[日本語版はこちら](nginx.ja.md)

---

## What is it?

nginx (pronounced "engine-x") is a high-performance web server and reverse proxy that has been one of the most widely used servers on the internet since the mid-2000s. Originally created by Igor Sysoev to handle the "C10K problem" (serving 10,000 simultaneous connections), nginx is known for its stability, low memory footprint, and raw speed.

Think of nginx like a veteran traffic cop at a busy intersection. They have been directing traffic for decades, know every trick, and can handle enormous volumes. They are reliable and fast, but if you need to change the traffic patterns, you have to give them a new written rulebook -- they do not figure things out on their own.

By contrast, [Traefik](traefik.md) is like a traffic cop with a radio who automatically adapts when new roads open. nginx needs to be told explicitly.

---

## Why does it matter?

nginx serves a huge portion of the internet. According to various surveys, it powers 30-40% of all websites. Many developers already have nginx in their infrastructure. If you are one of them, you do not need to switch to Traefik just to use volta-auth-proxy.

nginx supports an `auth_request` module that works similarly to Traefik's ForwardAuth -- it sends a subrequest to an authentication service before allowing the main request through. This means volta-auth-proxy integrates with nginx out of the box.

nginx matters because it is the most battle-tested reverse proxy available. When you need raw performance, extensive documentation, and proven stability at massive scale, nginx is the default choice.

---

## How does it work?

### Core architecture

nginx uses an **event-driven, asynchronous** architecture. Instead of spawning a new thread or process for each connection (like Apache), nginx uses a small number of worker processes that each handle thousands of connections using non-blocking I/O.

```
  ┌─────────────────────────────────────────┐
  │               nginx                      │
  │                                          │
  │  Master Process (reads config, manages)  │
  │       │                                  │
  │       ├── Worker Process 1 (handles ~10K connections)
  │       ├── Worker Process 2 (handles ~10K connections)
  │       └── Worker Process N              │
  └─────────────────────────────────────────┘
```

### The auth_request module

The `auth_request` module is nginx's equivalent of Traefik's ForwardAuth. It sends a subrequest to an internal location before processing the main request.

```nginx
server {
    listen 80;
    server_name app.example.com;

    # Every request to /app/ must pass auth
    location /app/ {
        auth_request /volta-verify;
        auth_request_set $volta_user $upstream_http_x_volta_user;
        auth_request_set $volta_tenant $upstream_http_x_volta_tenant;

        proxy_set_header X-Volta-User $volta_user;
        proxy_set_header X-Volta-Tenant $volta_tenant;
        proxy_pass http://backend:8080;
    }

    # Internal location -- calls volta-auth-proxy
    location = /volta-verify {
        internal;
        proxy_pass http://volta:7070/verify;
        proxy_pass_request_body off;
        proxy_set_header Content-Length "";
        proxy_set_header X-Original-URI $request_uri;
        proxy_set_header X-Forwarded-Host $host;
    }
}
```

### How auth_request works

```
  Browser ──► nginx ──► /volta-verify (subrequest to volta)
                           │
                           ├── 200 OK → nginx proceeds to proxy_pass → Backend
                           ├── 401     → nginx returns 401 to browser
                           └── 302     → nginx returns 302 to browser (login redirect)
```

### Comparison with Traefik and Caddy

| Feature | nginx | [Traefik](traefik.md) | [Caddy](caddy.md) |
|---------|-------|---------|-------|
| Auth subrequest | `auth_request` module | ForwardAuth middleware | `forward_auth` directive |
| Auto-discovery | No | Yes (Docker, K8s) | Limited |
| Config style | `nginx.conf` (custom syntax) | Labels / YAML | Caddyfile / JSON |
| Auto HTTPS | Requires certbot | Built-in | Built-in (default) |
| Performance | Excellent (industry benchmark) | Good | Good |
| Memory usage | Very low | Low | Low |
| Community/ecosystem | Massive | Large | Growing |
| Hot reload | `nginx -s reload` | Automatic | Automatic |

### nginx vs nginx Plus vs OpenResty

| Variant | Description |
|---------|-------------|
| **nginx OSS** | Free, open-source. What most people use. |
| **nginx Plus** | Commercial. Adds dashboard, health checks, session persistence. |
| **OpenResty** | nginx + LuaJIT. Scriptable request processing. Popular in API gateways. |

---

## How does volta-auth-proxy use it?

volta-auth-proxy works with nginx as an alternative to Traefik. While Traefik is the recommended proxy, many production environments already run nginx, and switching proxies just for auth is impractical.

### Integration pattern

The integration uses nginx's `auth_request` module:

1. A request arrives at nginx
2. nginx sends a subrequest to volta's `/verify` endpoint
3. volta checks the session cookie and responds with 200 (authenticated) or 401/302 (unauthenticated)
4. If 200, nginx copies volta's identity headers (`X-Volta-User`, `X-Volta-Tenant`) and forwards the request to the backend
5. If 401/302, nginx returns that response to the browser

### Key differences from Traefik

- **Manual config**: You must write `nginx.conf` rules for each protected location. No auto-discovery.
- **Header forwarding**: Requires explicit `auth_request_set` and `proxy_set_header` directives (more verbose than Traefik's `authResponseHeaders`).
- **Reload required**: Adding new services requires editing config and running `nginx -s reload`.

### When to choose nginx over Traefik

- You already have nginx in production and do not want to add another proxy
- You need maximum raw throughput (nginx consistently benchmarks higher)
- Your infrastructure does not change frequently (static config is fine)
- You need features like GeoIP, advanced rate limiting, or Lua scripting

---

## Common mistakes and attacks

### Mistake 1: Forgetting to enable auth_request module

The `auth_request` module is not always compiled into nginx by default. On some distributions, you need to install `nginx-extras` or compile with `--with-http_auth_request_module`. If it is missing, the directive silently fails.

### Mistake 2: Not marking the verify location as internal

The `/volta-verify` location must have the `internal;` directive. Without it, external clients can call it directly, which could leak information or bypass intended flows.

### Mistake 3: Forwarding the request body to the auth endpoint

The subrequest should not include the original request body. Always set `proxy_pass_request_body off;` and `proxy_set_header Content-Length "";`. Sending large POST bodies to the auth endpoint wastes resources and may cause timeouts.

### Mistake 4: Not handling auth_request errors

If volta is unreachable, nginx returns a 500 error by default. Consider adding `error_page 500 502 503 504 /auth-error.html;` to show a friendly error page and alerting on auth endpoint failures.

### Attack: Request smuggling

nginx and backend servers can disagree on where one HTTP request ends and the next begins (HTTP request smuggling). Keep nginx updated and use HTTP/1.1 with proper `Connection: close` handling to mitigate this.

---

## Further reading

- [nginx official documentation](https://nginx.org/en/docs/) -- The complete reference.
- [nginx auth_request module](https://nginx.org/en/docs/http/ngx_http_auth_request_module.html) -- How the subrequest module works.
- [traefik.md](traefik.md) -- volta's recommended proxy with ForwardAuth.
- [caddy.md](caddy.md) -- Another alternative proxy with forward_auth.
- [forwardauth.md](forwardauth.md) -- The ForwardAuth pattern explained.
- [reverse-proxy.md](reverse-proxy.md) -- What a reverse proxy is.
