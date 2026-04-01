# Integration

[日本語版はこちら](integration.ja.md)

---

## What is it?

Integration is the process of connecting separate software systems so they work together as one. Each system does its own job, but they communicate through well-defined interfaces -- APIs, headers, shared databases, or message queues.

Think of it like a restaurant kitchen. The chef (your app) cooks the food. The host (Traefik) seats guests and directs them. The bouncer (volta-auth-proxy) checks IDs at the door. None of them do each other's jobs, but they integrate through simple signals: the bouncer gives a wristband (X-Volta-* headers), the host reads the wristband and seats the guest in the right section, and the chef sees the table number and serves the food. The integration is the wristband and the table number -- the agreed-upon signals between independent systems.

In volta's world, integration means: how does volta-auth-proxy connect with Traefik (reverse proxy), your downstream application, Google (OIDC provider), and PostgreSQL (database)?

---

## Why does it matter?

- **No app is an island.** Real-world software always depends on other systems -- databases, identity providers, reverse proxies, payment gateways.
- **Integration failures are the #1 cause of production incidents.** A timeout from Google OIDC, a misconfigured Traefik rule, or a database connection failure -- these are integration problems.
- **Integration defines your security boundary.** The contract between volta and your app (X-Volta-* headers via ForwardAuth) is the trust boundary. If this is misconfigured, authentication breaks.
- **Testing integration is harder than testing code.** Unit tests check your logic; integration tests check that systems actually talk to each other correctly.

---

## How does it work?

### Integration patterns

| Pattern | How it works | Example in volta |
|---------|-------------|------------------|
| **Request/Response** | System A calls System B and waits for an answer | volta calls Google OIDC token endpoint |
| **Proxy/Middleware** | System A sits between client and System B | Traefik ForwardAuth calls volta before routing |
| **Shared Database** | Systems read/write the same database | volta writes sessions; Internal API reads them |
| **Header Injection** | Proxy adds metadata headers for downstream | volta sets X-Volta-User-ID for your app |
| **Webhook/Callback** | System A redirects back to System B | Google redirects to volta's callback URL |

### The integration flow in volta

```
  ┌──────┐     ┌──────────┐     ┌───────────────┐     ┌──────────┐
  │Browser│────▶│  Traefik  │────▶│ volta-auth-   │────▶│  Google   │
  │      │     │          │     │  proxy         │     │  OIDC     │
  │      │◀────│          │◀────│               │◀────│          │
  └──────┘     └─────┬────┘     └───────┬───────┘     └──────────┘
                     │                  │
                     │                  ▼
                     │          ┌───────────────┐
                     │          │  PostgreSQL    │
                     ▼          └───────────────┘
               ┌──────────┐
               │ Your App  │
               │ (reads    │
               │ X-Volta-*)│
               └──────────┘
```

### Integration Point 1: Traefik ForwardAuth

Traefik intercepts every request to your app and asks volta "is this user allowed?"

```
  Browser → Traefik → volta (ForwardAuth check)
                         │
                    ┌────┴────┐
                    │         │
                 200 OK    401/403
                    │         │
                    ▼         ▼
            Traefik routes   Traefik returns
            to your app      error to browser
            with X-Volta-*
            headers added
```

Traefik configuration (simplified):

```yaml
# Traefik ForwardAuth middleware
http:
  middlewares:
    volta-auth:
      forwardAuth:
        address: "http://volta:8080/auth/verify"
        authResponseHeaders:
          - "X-Volta-User-ID"
          - "X-Volta-Tenant-ID"
          - "X-Volta-Role"
          - "X-Volta-Email"
```

### Integration Point 2: Google OIDC

volta integrates with Google as an [OIDC provider](oidc-provider.md) for login:

```
  1. User clicks "Login with Google"
  2. volta redirects to Google's authorization endpoint
  3. User authenticates with Google
  4. Google redirects back to volta's callback URL
  5. volta exchanges the authorization code for tokens
  6. volta creates a local session
```

### Integration Point 3: Your downstream app

Your app reads the X-Volta-* [headers](header.md) that Traefik injects after volta approves the request:

```java
// In your app (NOT in volta -- this is the downstream app)
String userId   = request.getHeader("X-Volta-User-ID");
String tenantId = request.getHeader("X-Volta-Tenant-ID");
String role     = request.getHeader("X-Volta-Role");

// Your app trusts these headers because Traefik only sets them
// after volta has verified the session
```

### Integration Point 4: Internal API

For server-to-server integration, volta exposes an [Internal API](internal-api.md):

```
  Your Backend ──HTTP──▶ volta Internal API
                          │
                          ├─ GET /internal/users/{id}
                          ├─ GET /internal/tenants/{id}/members
                          └─ POST /internal/invitations
```

---

## How does volta-auth-proxy use it?

### Integration map

```
  ┌─────────────────────────────────────────────────┐
  │              volta-auth-proxy                    │
  │                                                  │
  │  Integrates with:                               │
  │                                                  │
  │  ┌─────────────┐  ┌─────────────┐              │
  │  │ Google OIDC  │  │ PostgreSQL  │              │
  │  │ (login)      │  │ (storage)   │              │
  │  └─────────────┘  └─────────────┘              │
  │                                                  │
  │  ┌─────────────┐  ┌─────────────┐              │
  │  │ Traefik      │  │ Your App    │              │
  │  │ (ForwardAuth)│  │ (headers/   │              │
  │  │              │  │  Int. API)  │              │
  │  └─────────────┘  └─────────────┘              │
  │                                                  │
  │  Phase 2:                                       │
  │  ┌─────────────┐                                │
  │  │ Redis        │                                │
  │  │ (sessions)   │                                │
  │  └─────────────┘                                │
  └─────────────────────────────────────────────────┘
```

### What your app needs to integrate with volta

1. Be behind Traefik with the volta ForwardAuth middleware
2. Read `X-Volta-User-ID`, `X-Volta-Tenant-ID`, `X-Volta-Role`, `X-Volta-Email` headers
3. Trust these headers (Traefik strips them from external requests)
4. Optionally call the Internal API for user/tenant management

---

## Common mistakes and attacks

### Mistake 1: Trusting X-Volta-* headers without Traefik

If your app is accessible without going through Traefik, anyone can set fake `X-Volta-User-ID` headers. Your app must only be reachable via Traefik, which strips forged headers.

### Mistake 2: Wrong callback URL in Google OIDC

If the redirect URI registered in Google Cloud Console does not match volta's callback endpoint exactly, Google rejects the login. This includes protocol (https vs http), domain, port, and path.

### Mistake 3: Firewall misconfiguration

The Internal API should never be exposed to the internet. It should only be reachable from your backend services on the internal network.

### Mistake 4: Ignoring integration timeouts

If Google OIDC is slow or the database connection pool is exhausted, volta must handle timeouts gracefully -- not hang forever. Set connection and read timeouts on all external calls.

### Mistake 5: Testing integration with mocks only

Mocking Google OIDC in tests is useful, but you also need real integration tests that actually call Google's endpoints (with a test project). A mock cannot catch API changes.

---

## Further reading

- [forwardauth.md](forwardauth.md) -- The integration point between Traefik and volta.
- [internal-api.md](internal-api.md) -- Server-to-server integration with volta.
- [oidc.md](oidc.md) -- The protocol volta uses to integrate with Google.
- [header.md](header.md) -- How integration data travels via HTTP headers.
- [app-integration.md](app-integration.md) -- Detailed guide for downstream apps.
