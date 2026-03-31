# ForwardAuth

[日本語版はこちら](forwardauth.ja.md)

---

## What is it?

ForwardAuth is a pattern where a reverse proxy (like Traefik or Nginx) asks an external service "is this request allowed?" before forwarding it to the actual application. The reverse proxy sends a subrequest to the auth service, and based on the response (200 OK or 401 Unauthorized), decides whether to let the request through or block it.

Think of it like a bouncer at a restaurant. The bouncer (Traefik) stands at the door. When a guest arrives, the bouncer calls the reservation desk (volta-auth-proxy) and asks "is this person on the list?" The reservation desk says yes (with details like "table 7, VIP") or no. The bouncer never seats anyone -- they only decide who gets in. The actual meal service happens inside the restaurant (your app).

---

## Why does it matter?

Without ForwardAuth (or something like it), every application would need to implement its own authentication:

```
  Without ForwardAuth:
  ┌─────────┐   ┌─────────────────────┐
  │ App A   │   │ Auth code in App A   │  ← Duplicated auth logic
  └─────────┘   └─────────────────────┘
  ┌─────────┐   ┌─────────────────────┐
  │ App B   │   │ Auth code in App B   │  ← Same logic, different bugs
  └─────────┘   └─────────────────────┘
  ┌─────────┐   ┌─────────────────────┐
  │ App C   │   │ Auth code in App C   │  ← Now maintain it in 3 places
  └─────────┘   └─────────────────────┘

  With ForwardAuth:
  ┌─────────────────────────────────┐
  │ volta-auth-proxy (one place)    │  ← All auth logic here
  └─────────────────────────────────┘
            ↑ "Is this OK?"
  ┌─────────────────────────────────┐
  │ Traefik (reverse proxy)         │  ← Asks volta before forwarding
  └─────────────────────────────────┘
       ↓ yes           ↓ no
  ┌─────────┐     ┌─────────┐
  │ App A   │     │ Redirect │
  │ (no auth│     │ to login │
  │  code!) │     └─────────┘
  └─────────┘
```

ForwardAuth means your apps have **zero authentication code**. They just read headers that Traefik passes through after volta approves the request.

---

## How does it work?

### Request flow diagram

```
  Browser                   Traefik                volta-auth-proxy        App
  =======                   =======                ================        ===

  1. GET /dashboard
  ──────────────────────►

                           2. "Hold on, let me check auth first"

                              GET /auth/verify
                              (forwards original headers:
                               Cookie, Host, X-Forwarded-*)
                           ──────────────────────►

                                                   3. Read session cookie
                                                   4. Look up session in DB
                                                   5. Verify user + tenant
                                                   6. Check role vs app's
                                                      allowed_roles
                                                   7. Issue fresh JWT

                           ◄──────────────────────
                              200 OK
                              X-Volta-User-Id: user-uuid
                              X-Volta-Tenant-Id: tenant-uuid
                              X-Volta-Roles: ADMIN
                              X-Volta-JWT: eyJhbGci...
                              X-Volta-Display-Name: Taro
                              X-Volta-Email: taro@acme.com
                              X-Volta-Tenant-Slug: acme
                              X-Volta-App-Id: app-wiki

                           8. "OK, user is authenticated"
                              Forward original request
                              + add volta headers

                                                                    ──────►
                                                                    GET /dashboard
                                                                    X-Volta-User-Id: user-uuid
                                                                    X-Volta-Tenant-Id: tenant-uuid
                                                                    X-Volta-Roles: ADMIN
                                                                    X-Volta-JWT: eyJhbGci...

                                                                    9. App reads headers
                                                                       Renders dashboard
                                                                       for the right user
                                                                       and tenant

  ◄──────────────────────────────────────────────────────────────────
  10. User sees their dashboard
```

### When authentication fails

```
  Browser                   Traefik                volta-auth-proxy
  =======                   =======                ================

  1. GET /dashboard
     (no session cookie, or expired session)
  ──────────────────────►

                              GET /auth/verify
                           ──────────────────────►

                                                   2. No valid session found

                           ◄──────────────────────
                              401 Unauthorized
                              (for JSON requests)
                              OR
                              302 Redirect to /login
                              (for browser requests)

                           3. Traefik returns the 401/302 to browser

  ◄──────────────────────
  4. Browser redirects to /login
```

### Why the proxy never sees the request body

This is a crucial point. In the ForwardAuth pattern:

```
  What Traefik sends to volta:          What Traefik sends to the app:
  ┌──────────────────────────┐          ┌──────────────────────────────┐
  │ GET /auth/verify          │          │ GET /dashboard                │
  │ Cookie: __volta_session=  │          │ Cookie: __volta_session=      │
  │ X-Forwarded-Host: wiki.  │          │ X-Volta-User-Id: user-uuid   │
  │ X-Forwarded-Uri: /dash   │          │ X-Volta-Tenant-Id: tenant-id │
  │ X-Forwarded-Method: GET  │          │ X-Volta-JWT: eyJhbGci...     │
  │                           │          │                              │
  │ NO REQUEST BODY           │          │ (original request body,      │
  │                           │          │  if any, goes directly to    │
  └──────────────────────────┘          │  the app)                    │
                                        └──────────────────────────────┘
```

volta-auth-proxy **never** sees the actual request body (POST data, file uploads, etc.). It only sees headers. This is important because:

1. **Privacy:** Sensitive data in request bodies never passes through the auth service.
2. **Performance:** No overhead from proxying large request bodies through auth.
3. **Security:** The auth service has a minimal attack surface. It cannot be used to modify request bodies.

### Comparison with reverse proxy pattern

Some auth systems work as full reverse proxies, where all traffic flows through them:

```
  Reverse Proxy Pattern (NOT what volta uses):
  ┌─────────┐     ┌──────────────┐     ┌─────────┐
  │ Browser  │────►│ Auth Proxy   │────►│   App   │
  │          │◄────│ (sees ALL    │◄────│         │
  │          │     │  traffic)    │     │         │
  └─────────┘     └──────────────┘     └─────────┘

  Problems:
  - Auth proxy is a bottleneck (all traffic goes through it)
  - Auth proxy sees request/response bodies (privacy concern)
  - If auth proxy goes down, everything goes down
  - Auth proxy needs to handle large file uploads, websockets, etc.


  ForwardAuth Pattern (what volta uses):
  ┌─────────┐     ┌──────────────┐     ┌─────────┐
  │ Browser  │────►│  Traefik     │────►│   App   │
  │          │◄────│              │◄────│         │
  └─────────┘     └──────────────┘     └─────────┘
                         │
                    "Is this OK?"
                         │
                  ┌──────▼───────┐
                  │ volta-auth-  │
                  │ proxy        │
                  │ (sees only   │
                  │  headers)    │
                  └──────────────┘

  Benefits:
  - volta only handles auth checks (lightweight)
  - Actual traffic flows directly through Traefik to app
  - volta can go down briefly without losing in-flight requests
  - No bottleneck for large payloads
```

---

## How does volta-auth-proxy implement ForwardAuth?

### The /auth/verify endpoint

When Traefik sends a ForwardAuth subrequest, volta's `/auth/verify` handler:

1. **Reads the session cookie** from the forwarded `Cookie` header
2. **Looks up the session** in the PostgreSQL database
3. **Validates the session** (not expired, not revoked, user is active member)
4. **Resolves the app** from the `X-Forwarded-Host` header, matching against `volta-config.yaml`
5. **Checks role authorization** -- does the user's role match the app's `allowed_roles`?
6. **Issues a fresh JWT** (5-minute expiry) with user/tenant/role claims
7. **Returns 200 with X-Volta-* headers** that Traefik passes to the app

### Traefik configuration

```yaml
# traefik dynamic configuration
http:
  middlewares:
    volta-auth:
      forwardAuth:
        address: http://volta-auth-proxy:7070/auth/verify
        authResponseHeaders:
          - X-Volta-User-Id
          - X-Volta-Email
          - X-Volta-Tenant-Id
          - X-Volta-Tenant-Slug
          - X-Volta-Roles
          - X-Volta-Display-Name
          - X-Volta-JWT
          - X-Volta-App-Id

  routers:
    my-wiki:
      rule: "Host(`wiki.example.com`)"
      middlewares: [volta-auth]
      service: wiki-service

  services:
    wiki-service:
      loadBalancer:
        servers:
          - url: "http://wiki-app:8080"
```

The `authResponseHeaders` setting tells Traefik which headers from volta's response should be forwarded to the downstream app. Any header not in this list is stripped.

### App registration in volta-config.yaml

```yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]

  - id: app-admin
    url: https://admin.example.com
    allowed_roles: [ADMIN, OWNER]
```

When a ForwardAuth request comes in, volta matches the `X-Forwarded-Host` against registered app URLs. If the user's role is not in the app's `allowed_roles`, volta returns 403 Forbidden.

### What apps receive

Apps get identity information in HTTP headers. No JWT verification is required for basic use:

```java
// Minimal app integration (Javalin example)
app.get("/api/data", ctx -> {
    String userId = ctx.header("X-Volta-User-Id");
    String tenantId = ctx.header("X-Volta-Tenant-Id");
    String roles = ctx.header("X-Volta-Roles");

    // Use tenantId in DB queries for data isolation
    var data = db.query("SELECT * FROM items WHERE tenant_id = ?", tenantId);
    ctx.json(data);
});
```

For higher security, apps can verify the `X-Volta-JWT` header against volta's JWKS endpoint:

```java
VoltaAuth volta = VoltaAuth.builder()
    .jwksUrl("http://volta-auth-proxy:7070/.well-known/jwks.json")
    .build();

app.before("/api/*", volta.middleware());
```

---

## Common mistakes and attacks

### Mistake 1: Trusting headers without ForwardAuth

If your app reads `X-Volta-User-Id` but there is no ForwardAuth middleware in front of it, anyone can set that header manually and impersonate any user. Headers are only trustworthy when Traefik strips/replaces them via ForwardAuth.

### Mistake 2: Not stripping X-Volta-* headers from client requests

Traefik should replace (not merge) `X-Volta-*` headers. If a client sends `X-Volta-User-Id: admin-uuid` in their request and Traefik does not strip it, the app might trust the forged header.

### Mistake 3: Exposing the app directly (bypassing Traefik)

If the app is accessible without going through Traefik (e.g., directly on port 8080), there is no ForwardAuth check. Anyone can access the app without authentication. Always ensure apps are only reachable through the reverse proxy.

### Attack: Header injection

If an attacker can inject headers into the ForwardAuth response (e.g., via CRLF injection in the auth service), they can set arbitrary `X-Volta-*` headers. volta is not vulnerable to this because it constructs headers from validated session data, not from user input.

---

## Further reading

- [Traefik ForwardAuth Documentation](https://doc.traefik.io/traefik/middlewares/http/forwardauth/) -- Official Traefik docs.
- [oidc.md](oidc.md) -- The authentication flow that creates the session ForwardAuth checks.
- [session.md](session.md) -- How sessions work (what ForwardAuth validates).
- [jwt.md](jwt.md) -- The JWT that ForwardAuth issues and passes to apps.
- [tenant.md](tenant.md) -- How tenant resolution works in the ForwardAuth flow.
