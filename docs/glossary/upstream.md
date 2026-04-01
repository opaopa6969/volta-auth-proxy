# Upstream

[日本語版はこちら](upstream.ja.md)

---

## What is it?

In the context of a reverse proxy, "upstream" refers to the server that sits behind the proxy and receives forwarded requests. When volta-auth-proxy authenticates a request and forwards it onward, your application server is the "upstream." The client never talks to the upstream directly -- volta sits in between, handling authentication before passing the request through.

Think of it like a receptionist at a doctor's office. You (the client) talk to the receptionist (volta-auth-proxy) first. The receptionist checks your identity, verifies your appointment, and then sends you through to the doctor (the upstream). The doctor focuses on their job (your app's business logic) without worrying about checking IDs. The receptionist is "downstream" (closer to the client) and the doctor is "upstream" (further from the client, behind the receptionist).

Note: the terminology can be confusing because "upstream" means further from the client, which feels like it should be "deeper" or "behind." The name comes from data flowing "upstream" -- requests flow from client up toward the origin server.

---

## Why does it matter?

The upstream relationship defines the security boundary in volta's architecture:

```
  Internet (untrusted)    volta-auth-proxy (trust boundary)    Upstream (trusted)
  ====================    ================================    ==================

  ┌──────────┐           ┌────────────────────────┐          ┌──────────────┐
  │  Client  │──────────►│  volta-auth-proxy      │─────────►│  Your App    │
  │ (browser)│           │                        │          │  (upstream)  │
  │          │           │  1. Check session/JWT   │          │              │
  │          │           │  2. Verify tenant       │          │  Receives:   │
  │          │           │  3. Check suspension    │          │  - Original  │
  │          │           │  4. Evaluate ABAC       │          │    request   │
  │          │           │  5. Add X-Volta-*       │          │  - X-Volta-* │
  │          │           │     headers             │          │    headers   │
  │          │           │  6. Forward request     │          │              │
  │          │           │                        │          │  Trusts:     │
  │          │           │  OR: 401/403 if denied  │          │  X-Volta-*   │
  └──────────┘           └────────────────────────┘          └──────────────┘
```

Your upstream app can trust the `X-Volta-*` [headers](header.md) because:

1. volta strips any client-supplied `X-Volta-*` headers before forwarding
2. Only volta can set these headers
3. The upstream is not directly accessible from the internet

---

## How does it work?

### Upstream configuration

volta is configured with the upstream server's address in `volta-config.yaml`:

```yaml
# volta-config.yaml
upstream:
  url: "http://localhost:8081"    # your app
  timeout_ms: 30000               # 30 second timeout
  health_check: "/health"         # optional health endpoint
```

### Request forwarding

```
  Client request:
    GET /api/v1/tenants/acme/members
    Cookie: volta_session=abc123
    X-Custom-Header: my-value

  volta processing:
    1. Validate session cookie → user authenticated
    2. Strip any X-Volta-* headers from client request
    3. Add X-Volta-* headers with verified identity
    4. Forward to upstream

  Forwarded to upstream:
    GET /api/v1/tenants/acme/members
    X-Custom-Header: my-value           ← preserved
    X-Volta-User-Id: user-uuid          ← added by volta
    X-Volta-Email: alice@acme.com       ← added by volta
    X-Volta-Roles: ADMIN                ← added by volta
    X-Volta-Tenant-Id: acme-uuid        ← added by volta
    X-Volta-M2M: false                  ← added by volta
```

### Headers volta sends to upstream

| Header | Content | Example |
|--------|---------|---------|
| `X-Volta-User-Id` | Authenticated user's UUID | `550e8400-...` |
| `X-Volta-Email` | User's email | `alice@acme.com` |
| `X-Volta-Roles` | Comma-separated [roles](role.md) | `ADMIN,MEMBER` |
| `X-Volta-Tenant-Id` | [Tenant](tenant.md) UUID | `acme-uuid` |
| `X-Volta-M2M` | Is this an [M2M](m2m.md) request? | `true` / `false` |
| `X-Volta-Client-Id` | M2M client name (if M2M) | `billing-service` |
| `X-Volta-Request-Id` | Unique request ID for tracing | `req-uuid` |

### Upstream response handling

volta passes the upstream's response back to the client unchanged:

```
  Upstream response:
    HTTP 200 OK
    Content-Type: application/json
    {"members": [...]}

  volta adds:
    Cache-Control: no-store      (security headers)
    X-Volta-Request-Id: req-uuid (tracing)

  Client receives:
    HTTP 200 OK
    Content-Type: application/json
    Cache-Control: no-store
    X-Volta-Request-Id: req-uuid
    {"members": [...]}
```

### Multiple upstreams (path-based routing)

volta can route to different upstreams based on the request path:

```yaml
# volta-config.yaml
upstream:
  routes:
    - path: "/api/v1/*"
      url: "http://main-app:8081"
    - path: "/api/v2/*"
      url: "http://v2-app:8082"
    - path: "/admin/*"
      url: "http://admin-app:8083"
```

```
  ┌──────────┐     ┌──────────────┐     ┌──────────────┐
  │  Client  │────►│ volta-auth-  │──┬─►│ main-app     │
  │          │     │ proxy        │  │  │ :8081        │
  │ /api/v1/ │     │              │  │  └──────────────┘
  │ /api/v2/ │     │  route by    │  │  ┌──────────────┐
  │ /admin/  │     │  path prefix │  ├─►│ v2-app       │
  │          │     │              │  │  │ :8082        │
  └──────────┘     └──────────────┘  │  └──────────────┘
                                     │  ┌──────────────┐
                                     └─►│ admin-app    │
                                        │ :8083        │
                                        └──────────────┘
```

---

## How does volta-auth-proxy use it?

### The ForwardAuth pattern

volta implements the ForwardAuth pattern, commonly used with reverse proxies like Traefik or Nginx:

```
  Option 1: volta as standalone proxy (direct)
  ┌────────┐     ┌──────────────┐     ┌──────────┐
  │ Client │────►│ volta-auth-  │────►│ Upstream │
  │        │     │ proxy        │     │ App      │
  └────────┘     └──────────────┘     └──────────┘

  Option 2: volta behind a load balancer (ForwardAuth)
  ┌────────┐     ┌─────────┐  auth?  ┌──────────────┐
  │ Client │────►│ Traefik │────────►│ volta-auth-  │
  │        │     │ (LB)    │◄────────│ proxy        │
  │        │     │         │ 200 OK  │ (auth only)  │
  │        │     │         │         └──────────────┘
  │        │     │         │────────►┌──────────┐
  │        │     │         │         │ Upstream │
  └────────┘     └─────────┘         │ App      │
                                     └──────────┘
```

### Upstream trust model

The upstream app MUST trust volta's headers and MUST NOT be directly accessible from the internet:

```
  CORRECT:
  Internet → volta → upstream (private network)
  Upstream trusts X-Volta-* headers ✓

  WRONG:
  Internet → upstream (directly accessible!)
  Anyone can forge X-Volta-* headers ✗
```

### Health checks

volta can monitor upstream health:

```
  volta → GET http://upstream:8081/health → 200 OK?
    YES → forward requests normally
    NO  → return 503 to clients, alert ops
```

### Timeout handling

If the upstream is slow, volta applies a timeout:

```
  Client → volta → upstream (processing...)
                   ↓
           30 seconds pass
                   ↓
  volta → 504 Gateway Timeout → Client
```

---

## Common mistakes and attacks

### Mistake 1: Exposing upstream directly to the internet

If clients can reach the upstream without going through volta, they can forge `X-Volta-*` headers and impersonate any user. The upstream must only be accessible from volta's network.

### Mistake 2: Not stripping client-supplied X-Volta-* headers

If volta does not strip `X-Volta-*` headers from the original request, a client can set `X-Volta-Roles: ADMIN` and the upstream will trust it. volta always strips these headers before adding its own.

### Mistake 3: Upstream performs its own auth check differently

If the upstream re-authenticates using a different mechanism (e.g., checking a different token), the auth results may conflict. Trust volta's headers exclusively -- that is the whole point of the proxy pattern.

### Mistake 4: No timeout configuration

Without timeouts, a slow upstream can cause volta to hold connections indefinitely, exhausting resources. Always configure upstream timeouts.

### Attack: SSRF via upstream misconfiguration

If volta's upstream URL is user-controllable (e.g., from a header), an attacker could redirect requests to internal services. Defense: upstream URLs are configured in `volta-config.yaml` by operators, never derived from request data.

### Attack: Header injection

An attacker sends a request with newlines in a header value to inject additional headers. Defense: volta sanitizes header values before forwarding.

---

## Further reading

- [header.md](header.md) -- The X-Volta-* headers sent to upstream.
- [session.md](session.md) -- How volta authenticates before forwarding.
- [jwt.md](jwt.md) -- JWT verification before upstream forwarding.
- [m2m.md](m2m.md) -- M2M requests forwarded to upstream.
- [internal-api.md](internal-api.md) -- volta's own API endpoints (not forwarded to upstream).
