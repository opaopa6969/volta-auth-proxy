# Client

[日本語版はこちら](client.ja.md)

---

## What is it?

A client is any program or device that sends requests to a [server](server.md) and receives responses. In web development, the most common client is a [browser](browser.md) -- it sends [HTTP](http.md) requests to web servers and displays the results. But a client can also be a mobile app, a command-line tool like `curl`, another server, or an [SDK](sdk.md).

Think of it like ordering food at a restaurant. You are the client -- you make the request ("I'd like the pasta"). The kitchen is the server -- it processes your request and gives you the result. You don't need to know how the kitchen works. You just need to know how to order (the [API](api.md)).

The client-server model is the foundation of the internet. Every time you open a website, your browser (client) sends a request to a server, and the server sends back a response.

---

## Why does it matter?

Understanding what a client is matters because:

- **Security trust boundary** -- Clients are untrusted. A server must never assume a client is honest. The user controls the client and can modify its behavior.
- **Multiple client types** -- volta-auth-proxy serves different types of clients: browsers, [SPAs](spa.md), [downstream apps](downstream-app.md), Traefik. Each communicates differently.
- **Client-side vs. server-side** -- This distinction determines where code runs, what data is accessible, and what security guarantees exist. See [frontend-backend](frontend-backend.md).
- **Authentication flows differ by client** -- A browser uses [cookies](cookie.md). An API client uses [Bearer tokens](bearer-scheme.md). A server-to-server client uses the [Internal API](internal-api.md) with JWTs.

---

## How does it work?

### The client-server model

```
  Client                                Server
  ──────                                ──────
  Sends request ──────────────────────> Processes request
                                        Reads database
                                        Applies business logic
  Receives response <────────────────── Sends response
```

### Types of clients in web development

```
  ┌─────────────────────────────────────────────────────────┐
  │                     CLIENTS                              │
  │                                                          │
  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────┐ │
  │  │ Browser  │  │ Mobile   │  │ Another  │  │ CLI    │  │
  │  │ (Chrome, │  │ App      │  │ Server   │  │ tool   │  │
  │  │  Firefox)│  │ (iOS,    │  │ (micro-  │  │ (curl, │  │
  │  │          │  │  Android)│  │  service) │  │  httpie)│ │
  │  └─────┬────┘  └─────┬────┘  └─────┬────┘  └────┬───┘ │
  │        │              │              │            │      │
  └────────┼──────────────┼──────────────┼────────────┼──────┘
           │              │              │            │
           └──────────────┴──────┬───────┴────────────┘
                                 │
                          HTTP Requests
                                 │
                                 ▼
                    ┌─────────────────────┐
                    │  volta-auth-proxy   │
                    │  (SERVER)           │
                    └─────────────────────┘
```

### Client identification

The server sees clients through the information they provide:

| Information | Source | Purpose |
|-------------|--------|---------|
| IP address | TCP connection | Rate limiting, logging |
| User-Agent header | HTTP header | Browser/bot identification |
| Session cookie | [Cookie](cookie.md) header | Authentication (browsers) |
| Authorization header | HTTP header | Authentication (API clients) |
| Origin header | HTTP header | [CORS](cors.md) enforcement |

### How different clients authenticate with volta

| Client type | Auth method | Token location |
|-------------|-------------|----------------|
| Browser (human user) | Session [cookie](cookie.md) | `Cookie: JSESSIONID=...` |
| SPA (JavaScript) | Session cookie + JWT refresh | Cookie for session, memory for [JWT](jwt.md) |
| Downstream backend | [ForwardAuth](forwardauth.md) headers | `X-Volta-User-Id`, `X-Volta-Tenant-Id` |
| API client | [Bearer token](bearer-scheme.md) | `Authorization: Bearer <jwt>` |
| Traefik | ForwardAuth | Internal network call |

---

## How does volta-auth-proxy use it?

### volta as a server to browser clients

When a user opens the volta login page, their browser is the client:

```
  Browser (client)                    volta (server)
  ────────────────                    ──────────────
  GET /auth/login  ──────────────────> Returns login HTML page
                   <──────────────────

  User clicks "Sign in with Google"
  GET /auth/callback?code=... ───────> Validates code with Google
                                       Creates session
                   <────────────────── Set-Cookie: JSESSIONID=abc
                                       Redirect to /dashboard
```

### volta as a server to SPA clients

When a [SPA](spa.md) calls volta's API:

```
  SPA (client)                        volta (server)
  ────────────                        ──────────────
  GET /api/v1/users/me
  Cookie: JSESSIONID=abc  ──────────> Validates session
                                      Looks up user in database
                          <────────── 200 OK {"userId":"...","name":"Taro"}
```

### volta as a server to Traefik (another server as client)

In the [ForwardAuth](forwardauth.md) pattern, Traefik is the client:

```
  Traefik (client)                    volta (server)
  ────────────────                    ──────────────
  GET /forwardauth
  X-Forwarded-Uri: /app/dashboard
  Cookie: JSESSIONID=abc  ──────────> Validates session
                          <────────── 200 OK
                                      X-Volta-User-Id: 550e...
                                      X-Volta-Tenant-Id: abcd...
                                      X-Volta-Roles: ADMIN
```

### volta as a CLIENT to Google

volta is not always the server. When performing [OIDC](oidc.md) authentication, volta acts as a client to Google's servers:

```
  volta (client)                      Google (server)
  ──────────────                      ────────────────
  POST /token
  code=xyz&redirect_uri=...  ────────> Validates authorization code
                             <──────── Returns id_token + access_token

  GET /userinfo
  Authorization: Bearer ... ─────────> Returns user profile
                            <────────── {"email":"taro@example.com",...}
```

This shows that client and server are **roles**, not fixed properties. The same program can be a client in one interaction and a server in another.

---

## Common mistakes and attacks

### Mistake 1: Trusting client-supplied data

The client controls what it sends. It can send fake headers, modified request bodies, or forged cookies. The server must validate everything:

```
  NEVER trust:
  ├── X-Forwarded-For header (client can fake IP)
  ├── User-Agent header (client can pretend to be anything)
  ├── Request body values (client can send any JSON)
  └── Query parameters (client can modify URLs)

  ALWAYS validate on the server:
  ├── Session cookie (cryptographically verified)
  ├── JWT signature (RS256 verification)
  └── Role from database (not from request)
```

### Attack 1: Client impersonation

An attacker writes a script that pretends to be a browser:

```bash
curl -H "User-Agent: Mozilla/5.0 Chrome/120" \
     -H "Cookie: JSESSIONID=stolen_value" \
     https://volta.example.com/api/v1/users/me
```

The server cannot distinguish a real browser from `curl` with the same headers. This is why session cookies must be protected with `HttpOnly`, `Secure`, and `SameSite` flags -- to prevent [XSS](xss.md) from stealing them in the first place.

### Mistake 2: Assuming one client type

If you build an API that only works correctly with browsers (relying on cookies and redirects), it will not work for API clients, mobile apps, or server-to-server communication. volta supports multiple client types by offering both cookie-based authentication (for browsers) and JWT-based authentication (for API clients).

### Attack 2: Automated client abuse

Bots and scripts can send thousands of requests per second. Without [rate limiting](rate-limiting.md), a malicious client can overwhelm the server or brute-force authentication. volta protects sensitive [endpoints](endpoint.md) with rate limiting.

---

## Further reading

- [server.md](server.md) -- The other half of the client-server model.
- [browser.md](browser.md) -- The most common type of client.
- [frontend-backend.md](frontend-backend.md) -- How client-side and server-side code differ.
- [cookie.md](cookie.md) -- How browser clients authenticate.
- [bearer-scheme.md](bearer-scheme.md) -- How API clients authenticate.
- [forwardauth.md](forwardauth.md) -- Traefik as a client to volta.
- [sdk.md](sdk.md) -- volta-sdk-js, a client library for SPAs.
- [cors.md](cors.md) -- Browser restrictions on which clients can call which servers.
