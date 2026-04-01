# Frontend and Backend

[日本語版はこちら](frontend-backend.ja.md)

---

## What is it?

Frontend and backend are the two halves of a web application. The **frontend** is everything that runs in the user's [browser](browser.md) -- the [HTML](html.md) structure, the [CSS](css.md) styling, the [JavaScript](javascript.md) interactivity. The **backend** is everything that runs on the [server](server.md) -- the business logic, the [database](database.md) queries, the authentication checks, the [API](api.md) responses.

Think of it like a restaurant. The frontend is the dining room -- the menus, the decor, the waiter taking your order. It is what the customer sees and interacts with. The backend is the kitchen -- the chef cooking your food, the refrigerator storing ingredients, the dishwasher cleaning plates. The customer never sees the kitchen, but without it, there is no food. The waiter (API) carries requests from the dining room to the kitchen and brings the results back.

The terms come from the physical layout of early computing: the "front" was the terminal the user sat at; the "back" was the mainframe in a separate room.

---

## Why does it matter?

Understanding the frontend-backend split is essential because:

- **Security boundaries** -- The frontend runs on the user's machine. You cannot trust it. Any validation, authorization, or secret must live on the backend. A user can modify frontend code using browser developer tools.
- **Different languages and tools** -- Frontends typically use [JavaScript](javascript.md), HTML, CSS. Backends can use [Java](java.md), Python, Go, Rust, etc. volta-auth-proxy's backend is Java; its frontend pages use [jte](jte.md) templates.
- **Authentication architecture** -- volta sits between frontend and backend. The frontend sends [cookies](cookie.md); volta's backend validates them and issues [JWTs](jwt.md) or [headers](header.md) for the downstream backend.
- **Deployment model** -- Frontends are often static files served by a CDN. Backends are running [processes](process.md) on servers. They scale differently.

---

## How does it work?

### The split

```
  ┌────────────────────────────────────┐     ┌────────────────────────────────┐
  │           FRONTEND                  │     │           BACKEND              │
  │      (runs in browser)              │     │      (runs on server)          │
  │                                     │     │                                │
  │  ┌──────────┐  ┌──────────────┐    │     │  ┌─────────────┐              │
  │  │  HTML     │  │ JavaScript   │    │     │  │ Route        │              │
  │  │ (structure│  │ (behavior)   │    │ HTTP│  │ handlers     │              │
  │  │  of page) │  │              │    │────>│  │              │              │
  │  └──────────┘  │ fetch(url)   │    │     │  └──────┬───────┘              │
  │                │ onClick()    │    │<────│         │                       │
  │  ┌──────────┐  │ render()     │    │     │  ┌──────▼───────┐              │
  │  │  CSS      │  └──────────────┘    │     │  │ Business     │              │
  │  │ (styling) │                      │     │  │ logic        │              │
  │  └──────────┘                       │     │  └──────┬───────┘              │
  │                                     │     │  ┌──────▼───────┐              │
  │                                     │     │  │ Database     │              │
  │                                     │     │  │              │              │
  │                                     │     │  └──────────────┘              │
  └────────────────────────────────────┘     └────────────────────────────────┘
       User's computer                             Server (data center)
```

### What lives where

| Concern | Frontend | Backend |
|---------|----------|---------|
| User interface | Buttons, forms, colors, layout | -- |
| Input validation | Quick feedback ("email format wrong") | Authoritative check (actually validates) |
| Business rules | -- | "ADMIN can invite, MEMBER cannot" |
| Data storage | -- | PostgreSQL, session store |
| Authentication | Sends cookie with request | Validates cookie, creates session |
| Secrets (API keys, DB passwords) | NEVER | Always |
| Data formatting | Renders JSON into HTML | Returns JSON |

### Communication between frontend and backend

The frontend and backend communicate over [HTTP](http.md):

```
  Frontend (Browser)                    Backend (Server)
  ──────────────────                    ────────────────
  User clicks "Save"
       │
       ▼
  JavaScript builds request:
  POST /api/v1/tenants
  Body: {"name":"ACME Corp"}
  Cookie: JSESSIONID=abc123
       │
       ├──── HTTP Request ────────────>  Receives request
       │                                 Validates session (cookie)
       │                                 Checks role (OWNER/ADMIN?)
       │                                 Inserts into database
       │                                 Returns result
       │<──── HTTP Response ───────────
       │
  HTTP 201 Created
  Body: {"id":"uuid-here","name":"ACME Corp"}
       │
       ▼
  JavaScript updates the page
  (adds new tenant to the list)
```

### Server-rendered vs. client-rendered

There are two approaches to who generates the HTML:

**Server-rendered (volta's auth pages):**
```
  Browser                              Server
  ───────                              ──────
  GET /auth/login  ──────────────────> Server runs jte template
                                       Fills in data (tenant name, etc.)
                                       Generates complete HTML
                   <────────────────── Returns <html>...</html>
  Browser displays the finished HTML
```

**Client-rendered ([SPA](spa.md)):**
```
  Browser                              Server
  ───────                              ──────
  GET /            ──────────────────> Returns index.html + app.js
                   <──────────────────
  JavaScript runs
  GET /api/v1/users/me ──────────────> Returns JSON data
                       <──────────────
  JavaScript builds HTML from data
  Browser displays the result
```

### Full-stack = frontend + backend

A "full-stack developer" works on both sides. A "full-stack framework" handles both sides (e.g., Next.js, Rails). volta-auth-proxy is a backend-only application -- it serves some HTML pages via [jte](jte.md), but it is not a frontend framework. The frontend apps that use volta are separate projects.

---

## How does volta-auth-proxy use it?

### volta is primarily a backend

volta-auth-proxy is a [Java](java.md) backend application. Its primary job is handling HTTP requests, managing sessions, issuing JWTs, and talking to the database. It has minimal frontend code.

### volta's small frontend: jte templates

volta does serve some HTML pages directly for auth flows:

```
  volta's frontend pages (server-rendered via jte):
  ├── /auth/login          → Login page with "Sign in with Google" button
  ├── /auth/tenant-select  → Tenant selector after login
  ├── /auth/invite/accept  → Invitation acceptance page
  └── /error               → Error pages
```

These pages are [server-rendered](template.md) using [jte](jte.md) templates. They are not a [SPA](spa.md). This is intentional -- auth pages should work without JavaScript.

### volta sits between frontend and backend

For [downstream apps](downstream-app.md), volta is the authentication layer between their frontend and their backend:

```
  ┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐
  │  App Frontend    │     │  volta-auth-proxy │     │  App Backend     │
  │  (React SPA)     │     │  (auth gateway)   │     │  (your API)      │
  │                  │     │                   │     │                  │
  │  fetch("/api")───┼────>│  Check session    │     │                  │
  │                  │     │  Add X-Volta-*    │────>│  Read X-Volta-*  │
  │                  │     │  headers          │     │  Trust them      │
  │  <───────────────┼─────┼──────────────────┼─────│  Return data     │
  └──────────────────┘     └──────────────────┘     └──────────────────┘
      Browser side              Server side              Server side
      (FRONTEND)                (BACKEND)                (BACKEND)
```

### The trust boundary

The critical security concept: **never trust the frontend**.

```
  UNTRUSTED                          TRUSTED
  ─────────────────────────────────  ─────────────────────────────
  Browser (frontend)                 volta-auth-proxy (backend)
  ├── User can modify JavaScript     ├── Validates session cookies
  ├── User can edit HTML             ├── Signs JWTs with private key
  ├── User can forge headers         ├── Checks roles in database
  ├── User can replay requests       ├── Enforces rate limits
  └── User can read all cookies*     └── Stores secrets securely

  * except HttpOnly cookies, which JavaScript cannot read
```

volta sets session cookies with `HttpOnly` and `Secure` flags, so the frontend JavaScript cannot read or modify them. The backend is the only place where trust is established.

---

## Common mistakes and attacks

### Mistake 1: Validating only on the frontend

A frontend form might check "is this email valid?" before submitting. But an attacker can bypass the frontend entirely using `curl` or a modified browser. The backend must always re-validate all input.

```
  WRONG:  Frontend checks email format → Backend trusts it
  RIGHT:  Frontend checks email format → Backend ALSO checks email format
```

### Mistake 2: Storing secrets on the frontend

Anything in frontend code is visible to the user. Never put API keys, database passwords, or signing keys in JavaScript. volta's RSA private key exists only on the backend. The public key is intentionally published at `/.well-known/jwks.json` because public keys are meant to be public.

### Attack 1: Frontend manipulation

An attacker modifies the frontend JavaScript to send a different role:

```json
// Attacker modifies the request body
POST /api/v1/tenants
{"name": "Evil Corp", "role": "OWNER"}
```

If the backend reads the `role` from the request body instead of from the authenticated session, the attacker has escalated their privileges. volta never reads roles from client requests -- roles come from the database via the session.

### Mistake 3: Leaking backend errors to the frontend

A backend error like `PSQLException: relation "users" does not exist` tells an attacker you use PostgreSQL and that the `users` table is missing. volta returns generic error messages to the frontend and logs detailed errors on the backend only.

### Attack 2: CORS exploitation

If the backend allows any origin (`Access-Control-Allow-Origin: *`), a malicious website can make API requests on behalf of the user. volta's [CORS](cors.md) configuration only allows specific trusted origins.

---

## Further reading

- [browser.md](browser.md) -- The environment where frontend code runs.
- [server.md](server.md) -- The environment where backend code runs.
- [spa.md](spa.md) -- A frontend architecture where JavaScript does all rendering.
- [template.md](template.md) -- How volta generates its frontend HTML on the backend.
- [api.md](api.md) -- How frontend and backend communicate.
- [cookie.md](cookie.md) -- How the frontend sends authentication data to the backend.
- [header.md](header.md) -- How the backend passes user info to downstream backends.
- [javascript.md](javascript.md) -- The primary frontend language.
- [java.md](java.md) -- volta's backend language.
