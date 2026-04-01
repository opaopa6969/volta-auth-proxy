# Middleware

[日本語版はこちら](middleware.ja.md)

---

## What is it?

Middleware is code that runs between receiving a request and executing the final handler for that request. It sits in the middle -- after the [server](server.md) receives the [HTTP](http.md) request, but before your business logic processes it. Middleware can inspect the request, modify it, reject it, or let it pass through.

Think of it like security checkpoints at an airport. You are trying to get to your gate (the handler). Before you get there, you pass through several checkpoints: ticket verification, ID check, baggage screening, maybe a customs check. Each checkpoint can let you through, redirect you, or stop you entirely. The gate agent (your handler) only sees passengers who passed all the checkpoints. Middleware works the same way -- each piece checks one thing and either passes the request along or rejects it.

Middleware is also called "filters," "interceptors," or "hooks" depending on the [framework](framework.md). In Javalin (which volta uses), they are `before()` and `after()` handlers.

---

## Why does it matter?

Without middleware, you would repeat the same checks in every handler:

- Is the user authenticated? (check in every endpoint)
- Does the user have the right [role](role.md)? (check in every endpoint)
- Should we log this request? (log in every endpoint)
- Is the CSRF token valid? (check in every endpoint)

This leads to:

- **Code duplication** -- the same 10 lines of auth checking in 30 handlers
- **Missed checks** -- one developer forgets the auth check in one handler, creating a security hole
- **Tangled logic** -- business logic mixed with security logic makes both harder to understand

Middleware centralizes cross-cutting concerns. Write the auth check once, apply it to all routes that need it.

---

## How does it work?

### The middleware chain

Middleware forms a chain. Each piece runs in order, and each can decide whether to continue to the next piece or stop.

```
  HTTP Request
       │
       ▼
  ┌─────────────────┐
  │ Middleware 1     │  Logging: log method, path, timestamp
  │ (logging)       │
  └────────┬────────┘
           │ pass
           ▼
  ┌─────────────────┐
  │ Middleware 2     │  Auth: check session cookie, reject if invalid
  │ (authentication)│
  └────────┬────────┘
           │ pass (or 401 reject)
           ▼
  ┌─────────────────┐
  │ Middleware 3     │  RBAC: check role >= ADMIN for this path
  │ (authorization) │
  └────────┬────────┘
           │ pass (or 403 reject)
           ▼
  ┌─────────────────┐
  │ Route Handler   │  Your actual business logic
  │ (your code)     │
  └────────┬────────┘
           │
           ▼
  ┌─────────────────┐
  │ After-middleware │  Add security headers, log response time
  │ (response hooks)│
  └────────┬────────┘
           │
           ▼
  HTTP Response
```

### Before vs. after middleware

Most frameworks support two types:

| Type | Runs when | Common uses |
|------|-----------|-------------|
| Before (pre-handler) | Before the route handler | Authentication, authorization, rate limiting, request parsing |
| After (post-handler) | After the route handler | Adding response headers, logging response time, cleanup |

### Path-scoped middleware

Middleware can apply to all routes or only to specific paths:

```java
// Applies to ALL requests
app.before(ctx -> {
    logger.info("{} {}", ctx.method(), ctx.path());
});

// Applies only to /api/* paths
app.before("/api/*", ctx -> {
    authMiddleware.requireAuth(ctx);
});

// Applies only to /api/v1/admin/* paths
app.before("/api/v1/admin/*", ctx -> {
    authMiddleware.requireRole(ctx, Role.ADMIN);
});
```

This creates a layered security model:

```
  Path                        Middleware applied
  ─────────────────────────   ────────────────────────────
  /auth/login                 logging only
  /auth/callback              logging only
  /api/v1/users/me            logging + auth
  /api/v1/admin/tenants       logging + auth + admin check
```

### Short-circuiting

When middleware decides to reject a request, it "short-circuits" the chain -- the remaining middleware and the handler never run:

```
  Request: GET /api/v1/users/me (no session cookie)
       │
       ▼
  ┌─────────────────┐
  │ Logging MW      │  ✓ logs the request
  └────────┬────────┘
           │
           ▼
  ┌─────────────────┐
  │ Auth MW         │  ✗ no valid session!
  │                 │  → returns 401 Unauthorized
  └─────────────────┘
           │
           ╳ (chain stops here)

  Route handler NEVER executes.
  Authorization MW NEVER runs.
```

This is how middleware protects [endpoints](endpoint.md) without the endpoint needing to know about security.

---

## How does volta-auth-proxy use it?

### volta's middleware layers

volta-auth-proxy uses Javalin's `before()` handlers to implement a layered security model:

```
  ┌──────────────────────────────────────────────┐
  │                All requests                   │
  │  before("/*"): request logging               │
  ├──────────────────────────────────────────────┤
  │            /api/* requests                    │
  │  before("/api/*"): session/JWT validation     │
  │  → sets X-Volta-User-Id on context           │
  │  → sets X-Volta-Tenant-Id on context         │
  ├──────────────────────────────────────────────┤
  │         /api/v1/admin/* requests              │
  │  before("/api/v1/admin/*"): role >= ADMIN    │
  ├──────────────────────────────────────────────┤
  │              ForwardAuth requests             │
  │  before("/forwardauth"): session check       │
  │  → returns X-Volta-* headers to Traefik     │
  └──────────────────────────────────────────────┘
```

### Authentication middleware

The core auth middleware checks for a valid [session](session.md):

```java
app.before("/api/*", ctx -> {
    var session = ctx.sessionAttribute("user");
    if (session == null) {
        throw new UnauthorizedResponse();  // 401
    }
    // Attach user info to request context for handlers
    ctx.attribute("userId", session.userId());
    ctx.attribute("tenantId", session.tenantId());
});
```

The handler downstream only sees a request that already has a verified user -- it never deals with session checking.

### Authorization middleware

After authentication, role-checking middleware enforces [RBAC](rbac.md):

```java
app.before("/api/v1/admin/*", ctx -> {
    var role = ctx.attribute("role");
    if (!role.isAtLeast(Role.ADMIN)) {
        throw new ForbiddenResponse();  // 403
    }
});
```

### ForwardAuth middleware

When Traefik sends a [ForwardAuth](forwardauth.md) request, middleware validates the session and returns user info as [headers](header.md):

```java
app.before("/forwardauth", ctx -> {
    var session = validateSession(ctx);
    ctx.header("X-Volta-User-Id", session.userId().toString());
    ctx.header("X-Volta-Tenant-Id", session.tenantId().toString());
    ctx.header("X-Volta-Roles", String.join(",", session.roles()));
});
```

The [downstream app](downstream-app.md) reads these headers and trusts them because Traefik strips any client-supplied X-Volta-* headers before forwarding.

### After-middleware for security headers

volta uses `after()` hooks to add security [headers](header.md) to every response:

```java
app.after(ctx -> {
    ctx.header("X-Content-Type-Options", "nosniff");
    ctx.header("X-Frame-Options", "DENY");
    ctx.header("Cache-Control", "no-store");
});
```

---

## Common mistakes and attacks

### Mistake 1: Wrong middleware order

Middleware runs in registration order. If you put the logging middleware after the auth middleware, rejected requests are never logged:

```java
// WRONG order -- auth failures are invisible
app.before("/api/*", ctx -> requireAuth(ctx));      // rejects → no log
app.before("/api/*", ctx -> logRequest(ctx));        // never runs

// CORRECT order -- everything gets logged
app.before("/api/*", ctx -> logRequest(ctx));        // logs first
app.before("/api/*", ctx -> requireAuth(ctx));       // then checks auth
```

### Mistake 2: Forgetting to scope middleware

Applying auth middleware too broadly can break public [endpoints](endpoint.md):

```java
// WRONG -- blocks the login page itself
app.before("/*", ctx -> requireAuth(ctx));

// CORRECT -- only protect API routes
app.before("/api/*", ctx -> requireAuth(ctx));
```

### Attack 1: Middleware bypass via path manipulation

An attacker might try to bypass path-scoped middleware with path tricks:

```
  /api/v1/admin/../public/resource    (path traversal)
  /API/v1/admin/tenants               (case manipulation)
  /api/v1/admin/tenants/              (trailing slash)
  /api/v1/admin/tenants;jsessionid=x  (path parameter injection)
```

Javalin normalizes paths before matching, which prevents most of these. But it is important to understand that path-based middleware security depends on the framework correctly matching paths.

### Attack 2: Header injection through middleware

If middleware reads a header from the client and passes it downstream without validation, an attacker can inject arbitrary values. volta's [ForwardAuth](forwardauth.md) middleware is safe because Traefik strips any X-Volta-* headers from the original request before calling the auth endpoint.

### Mistake 3: Doing too much in middleware

Middleware should be fast. If you put a database query in middleware that runs on every request, you add latency to every request -- even ones that will be rejected by a later middleware. Keep middleware lightweight: session lookup (ideally cached), role checks (from session data), header validation.

---

## Further reading

- [framework.md](framework.md) -- How frameworks like Javalin provide middleware hooks.
- [session.md](session.md) -- What the auth middleware validates.
- [role.md](role.md) -- The RBAC hierarchy enforced by authorization middleware.
- [forwardauth.md](forwardauth.md) -- How middleware powers the ForwardAuth pattern.
- [header.md](header.md) -- The X-Volta-* headers that middleware sets.
- [endpoint.md](endpoint.md) -- The route handlers that middleware protects.
- [authentication-vs-authorization.md](authentication-vs-authorization.md) -- The two security checks that middleware implements.
