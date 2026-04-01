# Endpoint

[日本語版はこちら](endpoint.ja.md)

---

## What is it?

An endpoint is a specific [URL](url.md) path combined with an [HTTP](http.md) method that a [server](server.md) listens on and responds to. When someone says "call the `/api/v1/users/me` endpoint," they mean "send an HTTP request to that specific path." Each endpoint does one thing -- returns user data, creates a tenant, deletes a member, etc.

Think of it like a counter at a government office. The building has many counters, each handling a specific task: Counter 1 for birth certificates, Counter 2 for driver's licenses, Counter 3 for tax forms. You go to the right counter for what you need. An endpoint is a counter -- it has a specific location (URL path) and handles a specific request type.

An [API](api.md) is the collection of all endpoints that a server offers. A single endpoint is one specific operation within that API.

---

## Why does it matter?

Endpoints are the interface between systems. Without clearly defined endpoints:

- [Client](client.md) applications would not know where to send requests
- [Downstream apps](downstream-app.md) would not know what data is available from volta
- Security rules could not be applied per-operation (e.g., "only ADMINs can access this endpoint")
- Documentation would be impossible -- there is nothing to document
- API versioning would break -- you need stable endpoint paths that clients can rely on

Every interaction between a [SPA](spa.md) and volta-auth-proxy, or between a downstream app and volta's [Internal API](internal-api.md), happens through an endpoint.

---

## How does it work?

### Anatomy of an endpoint

An endpoint is defined by three things:

```
  HTTP Method   +   URL Path              =   Endpoint
  ───────────       ──────────────────         ─────────────────────
  GET               /api/v1/users/me           "Get current user"
  POST              /api/v1/tenants            "Create a tenant"
  DELETE            /api/v1/admin/members/:id  "Remove a member"
```

The **same path** with **different methods** can be different endpoints:

```
  GET    /api/v1/tenants      →  List all tenants for this user
  POST   /api/v1/tenants      →  Create a new tenant
  PUT    /api/v1/tenants/:id  →  Update a tenant
  DELETE /api/v1/tenants/:id  →  Delete a tenant
```

### Path parameters

Some endpoints have dynamic segments, marked with `:` or `{}`:

```
  /api/v1/admin/members/:memberId
                        ^^^^^^^^^^
                        This part changes per request

  GET /api/v1/admin/members/550e8400-e29b-41d4-a716-446655440000
  GET /api/v1/admin/members/660f9500-f30c-52e5-b827-557766551111
```

The `:memberId` is a path parameter -- a [variable](variable.md) part of the URL.

### Query parameters

Endpoints can accept additional data via query parameters:

```
  GET /api/v1/admin/members?page=2&limit=20&role=ADMIN
                            ^^^^^^^^^^^^^^^^^^^^^^^^
                            Query parameters (filtering, pagination)
```

### Request and response

A complete endpoint interaction:

```
  Client                                        Server (volta)
  ──────                                        ──────────────
  GET /api/v1/users/me
  Headers:
    Cookie: JSESSIONID=abc123
    Accept: application/json
                          ─────────────────────>

                          <─────────────────────
  HTTP/1.1 200 OK
  Headers:
    Content-Type: application/json
  Body:
    {
      "userId": "550e8400-...",
      "displayName": "Taro Yamada",
      "tenantId": "abcd1234-...",
      "roles": ["ADMIN"]
    }
```

### REST endpoint conventions

Most APIs follow REST conventions for endpoint design:

| Operation | Method | Path pattern | Example |
|-----------|--------|-------------|---------|
| List all | GET | /resources | GET /api/v1/tenants |
| Get one | GET | /resources/:id | GET /api/v1/tenants/:id |
| Create | POST | /resources | POST /api/v1/tenants |
| Update | PUT | /resources/:id | PUT /api/v1/tenants/:id |
| Delete | DELETE | /resources/:id | DELETE /api/v1/tenants/:id |

### Endpoint groups

Endpoints are often organized into logical groups:

```
  volta-auth-proxy endpoints
  │
  ├── Auth endpoints (browser-facing, HTML responses)
  │   ├── GET  /auth/login
  │   ├── GET  /auth/callback
  │   ├── POST /auth/logout
  │   └── POST /auth/refresh
  │
  ├── API endpoints (JSON responses, require authentication)
  │   ├── GET  /api/v1/users/me
  │   ├── GET  /api/v1/tenants
  │   ├── POST /api/v1/tenants
  │   └── ...
  │
  ├── Admin endpoints (require ADMIN or OWNER role)
  │   ├── GET  /api/v1/admin/members
  │   ├── POST /api/v1/admin/members/invite
  │   └── ...
  │
  ├── Internal endpoints (called by Traefik, not users)
  │   └── GET  /forwardauth
  │
  └── Well-known endpoints (public, no auth)
      └── GET  /.well-known/jwks.json
```

---

## How does volta-auth-proxy use it?

### Registering endpoints in Javalin

volta registers endpoints in Main.java using Javalin's route methods:

```java
// Auth endpoints
app.get("/auth/login",     ctx -> authController.loginPage(ctx));
app.get("/auth/callback",  ctx -> authController.callback(ctx));
app.post("/auth/logout",   ctx -> authController.logout(ctx));
app.post("/auth/refresh",  ctx -> authController.refresh(ctx));

// API endpoints
app.get("/api/v1/users/me",    ctx -> userController.me(ctx));
app.get("/api/v1/tenants",     ctx -> tenantController.list(ctx));
app.post("/api/v1/tenants",    ctx -> tenantController.create(ctx));

// Admin endpoints
app.get("/api/v1/admin/members",        ctx -> adminController.listMembers(ctx));
app.post("/api/v1/admin/members/invite", ctx -> adminController.invite(ctx));

// ForwardAuth endpoint
app.get("/forwardauth", ctx -> forwardAuthController.check(ctx));

// JWKS endpoint
app.get("/.well-known/jwks.json", ctx -> jwksController.jwks(ctx));
```

Each line maps one endpoint to one handler method. There is no hidden routing configuration.

### Endpoint security layers

Different endpoints have different security requirements, enforced by [middleware](middleware.md):

```
  Endpoint                          Auth required?   Role required?
  ────────────────────────────────  ──────────────   ──────────────
  GET  /auth/login                  No               No
  GET  /.well-known/jwks.json       No               No
  GET  /api/v1/users/me             Yes (session)    No (any role)
  GET  /api/v1/tenants              Yes (session)    No (any role)
  POST /api/v1/admin/members/invite Yes (session)    ADMIN or OWNER
  GET  /forwardauth                 Yes (session)    No (internal)
```

### API versioning in endpoints

volta uses [API versioning](api-versioning.md) in the URL path:

```
  /api/v1/users/me      ← current version
  /api/v2/users/me      ← future version (not yet implemented)
```

The `v1` prefix means "version 1 of this API." If volta needs to make breaking changes to the response format, it creates `v2` endpoints while keeping `v1` working. Existing [downstream apps](downstream-app.md) are not broken.

### Endpoint response formats

| Endpoint group | Response format | Used by |
|---------------|----------------|---------|
| /auth/* | HTML (jte templates) | Browser (user's eyes) |
| /api/v1/* | [JSON](json.md) | SPAs, downstream apps |
| /forwardauth | Empty body + [headers](header.md) | Traefik |
| /.well-known/* | JSON | JWT verification libraries |

---

## Common mistakes and attacks

### Mistake 1: Inconsistent endpoint naming

Mixing naming conventions makes an API hard to learn:

```
  BAD:
  /api/v1/getUsers          (verb in path)
  /api/v1/tenant/create     (verb in path)
  /api/v1/member-list       (inconsistent with above)

  GOOD:
  GET  /api/v1/users        (noun, plural)
  POST /api/v1/tenants      (noun, plural)
  GET  /api/v1/members      (noun, plural)
```

The HTTP method (GET, POST, DELETE) already expresses the verb. The path should be a noun.

### Mistake 2: Exposing internal IDs in public endpoints

If an endpoint like `GET /api/v1/users/:id` lets anyone fetch any user's data by guessing IDs, that is an insecure direct object reference (IDOR). volta uses [UUIDs](uuid.md) (which are hard to guess) and also checks that the requesting user has permission to access the resource.

### Attack 1: Endpoint enumeration

An attacker systematically tries paths to discover undocumented endpoints:

```
  GET /api/v1/admin/settings       → 403 (exists but forbidden)
  GET /api/v1/admin/debug          → 404 (does not exist)
  GET /api/v1/admin/backup         → 404 (does not exist)
  GET /api/v1/internal/health      → 200 (found something!)
```

Defense: return the same error code for "not found" and "forbidden" to unauthenticated users (volta returns 401 for all unauthenticated API requests, not 403 or 404).

### Attack 2: HTTP method tampering

An attacker tries different methods on the same path:

```
  GET  /api/v1/admin/members/:id   → 200 (read access)
  DELETE /api/v1/admin/members/:id → 403? or 405?
```

If the endpoint only checks auth for GET but not DELETE, the attacker can delete members. volta registers each method + path combination explicitly, and unregistered combinations return 405 Method Not Allowed.

### Mistake 3: No rate limiting on sensitive endpoints

Endpoints like `/auth/login` or `/api/v1/admin/members/invite` can be abused if called thousands of times. Without [rate limiting](rate-limiting.md), an attacker can brute-force login or spam invitations.

---

## Further reading

- [api.md](api.md) -- The collection of endpoints that forms an API.
- [api-versioning.md](api-versioning.md) -- Why volta uses /v1/ in endpoint paths.
- [http.md](http.md) -- The protocol that endpoints use.
- [middleware.md](middleware.md) -- How security is applied to endpoint groups.
- [internal-api.md](internal-api.md) -- volta's API endpoints for downstream apps.
- [forwardauth.md](forwardauth.md) -- The special /forwardauth endpoint.
- [url.md](url.md) -- How endpoint paths relate to full URLs.
- [http-status-codes.md](http-status-codes.md) -- The response codes endpoints return.
