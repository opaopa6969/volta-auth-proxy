# Routing

[日本語版はこちら](routing.ja.md)

---

## In one sentence?

Routing is the process of looking at an incoming request's URL and deciding which [server](server.md) or service should handle it.

---

## The airport arrivals hall

Imagine an airport arrivals hall with multiple exits:

| Sign at the exit | Where it leads | Web equivalent |
|---|---|---|
| "Taxis" | Taxi stand | `api.example.com` goes to the API server |
| "Hotel Shuttles" | Shuttle pickup | `app.example.com` goes to the web app |
| "Rental Cars" | Car rental desks | `auth.example.com` goes to volta |
| "Domestic Transfers" | Domestic terminal | `admin.example.com` goes to the admin panel |

A **router** is the system that reads the signs and directs you. In web terms, the [reverse proxy](reverse-proxy.md) reads the [domain](domain.md)/[subdomain](subdomain.md)/path of each request and sends it to the right backend service.

---

## Why do we need this?

Without routing:

- Every service would need its own public IP address and [port](port.md)
- Users would have to remember which port each service runs on (`example.com:8080` for app, `example.com:8081` for auth)
- No way to run multiple services behind a single [domain](domain.md)
- [ForwardAuth](forwardauth.md) couldn't work -- the proxy wouldn't know which requests need authentication
- Load balancing (distributing traffic across multiple instances) would be impossible

Routing is what lets you run dozens of services behind a clean URL like `app.example.com`.

---

## Routing in volta-auth-proxy

volta's deployment involves multiple routing decisions:

```
  Request: https://app.acme.example.com/dashboard
                    │
  ┌─────────────────▼──────────────────┐
  │  Reverse Proxy (Traefik/Nginx)     │
  │                                    │
  │  Routing rules:                    │
  │  ┌──────────────────────────────┐  │
  │  │ auth.example.com → volta     │  │
  │  │ *.app.example.com → app      │  │
  │  │ api.example.com → api-server │  │
  │  └──────────────────────────────┘  │
  │                                    │
  │  Match: *.app.example.com          │
  │  BUT first → ForwardAuth to volta  │
  └─────────────────┬──────────────────┘
                    │
        ┌───────────▼───────────┐
        │ volta (ForwardAuth)   │
        │ Check session/cookie  │
        │ Inject X-Volta-* hdrs │
        └───────────┬───────────┘
                    │
        ┌───────────▼───────────┐
        │ Your App (:3000)      │
        │ Receives request +    │
        │ auth headers          │
        └───────────────────────┘
```

Types of routing in a volta deployment:

| Routing type | Example | Who does it |
|---|---|---|
| **Host-based** | `auth.example.com` vs `app.example.com` | Reverse proxy |
| **Subdomain-based** | `acme.app.example.com` → tenant "acme" | volta (tenant resolution) |
| **Path-based** | `/auth/login` vs `/api/v1/users/me` | volta's Javalin routes |
| **Method-based** | `GET /auth/verify` vs `POST /auth/logout` | volta's Javalin routes |

volta's internal routes:

| Path | Method | Purpose |
|---|---|---|
| `/auth/login` | GET | Start [login](login.md) flow |
| `/auth/callback` | GET | [OIDC](oidc.md) callback from Google |
| `/auth/logout` | POST | [Logout](logout.md) |
| `/auth/verify` | GET | [ForwardAuth](forwardauth.md) verification |
| `/auth/refresh` | POST | Refresh [JWT](jwt.md) from [session](session.md) |
| `/api/v1/users/me` | GET | Get current user info |
| `/.well-known/jwks.json` | GET | Public key for JWT verification |

---

## Concrete example

Setting up routing in Traefik for a volta deployment:

```yaml
# Traefik dynamic configuration (simplified)
http:
  routers:
    volta-auth:
      rule: "Host(`auth.example.com`)"
      service: volta
      tls:
        certResolver: letsencrypt

    app:
      rule: "HostRegexp(`{subdomain:[a-z]+}.app.example.com`)"
      service: app
      middlewares:
        - volta-forwardauth    # ← Check auth BEFORE routing to app
      tls:
        certResolver: letsencrypt

  middlewares:
    volta-forwardauth:
      forwardAuth:
        address: "http://volta:8080/auth/verify"
        authResponseHeaders:
          - "X-Volta-User-Id"
          - "X-Volta-Tenant-Id"
          - "X-Volta-Roles"
          - "X-Volta-JWT"
```

What happens step by step:

1. Request arrives for `https://acme.app.example.com/dashboard`
2. Traefik matches the `HostRegexp` rule for `*.app.example.com`
3. Before routing to the app, Traefik runs the `volta-forwardauth` middleware
4. Traefik sends the request to `http://volta:8080/auth/verify`
5. volta checks the [session](session.md) [cookie](cookie.md), resolves the tenant from the [subdomain](subdomain.md)
6. volta responds with 200 + [X-Volta-* headers](header.md)
7. Traefik copies those [headers](header.md) and routes the request to `http://app:3000`
8. The app reads the headers and serves the dashboard

---

## Learn more

- [Reverse Proxy](reverse-proxy.md) -- The component that performs routing
- [ForwardAuth](forwardauth.md) -- Authentication middleware in the routing pipeline
- [Subdomain](subdomain.md) -- How routing determines which tenant a request belongs to
- [Domain](domain.md) -- The address that routing decisions are based on
- [Header](header.md) -- Data that volta injects during the routing process
- [Port](port.md) -- Internal service addresses that routing maps to
