# volta-auth-proxy

Multi-tenant identity gateway for SaaS.
Handles auth, tenants, roles, invitations so downstream apps don't have to.

**No Keycloak. No oauth2-proxy. Control is king.**

---

## Architecture

### Overview

```
                    ┌─────────────────────────────────────────────┐
                    │            volta-auth-proxy                  │
                    │                                             │
  Browser ──────── │  [UI]  login / signup / invite / admin       │
       ↑           │  [Auth] Google OIDC / session / JWT          │
       │           │  [ForwardAuth] GET /auth/verify              │ ◄── Traefik asks
       │           │  [Internal API] /api/v1/*                    │ ◄── Apps call
       │           │  [DB] Postgres (users/tenants/sessions/...)  │
       │           └─────────────────────────────────────────────┘
       │                              │
       │                    ┌─────────┴─────────┐
       │                    │                   │
       │               ┌────▼────┐         ┌────▼────┐
       └───────────────┤  App A  │         │  App B  │  ...
                       └─────────┘         └─────────┘
```

### How Requests Flow

There are 3 types of traffic:

#### Type 1: Browser -> App (normal page/API access)

```
Browser ─── GET /dashboard ───► Traefik
                                   │
                        ┌──────────▼──────────┐
                        │  ForwardAuth check   │
                        │  GET /auth/verify    │
                        │  to volta-auth-proxy │
                        └──────────┬──────────┘
                                   │
                    ┌──── 200 OK ──┴── 401 NG ────┐
                    │                              │
                    ▼                              ▼
          Traefik forwards               Browser redirected
          to App with headers:           to /login
          X-Volta-User-Id
          X-Volta-Tenant-Id
          X-Volta-Roles
          X-Volta-JWT
                    │
                    ▼
               App renders
               (reads headers)
```

**Key point:** volta-auth-proxy never sees the request body. Traefik only asks "is this user authenticated?" and gets headers back. The actual request goes directly from Traefik to the App.

#### Type 2: App -> volta-auth-proxy (CRUD delegation)

```
App ─── GET /api/v1/tenants/{tid}/members ───► volta-auth-proxy
        Authorization: Bearer <user-jwt>              │
                                                      ▼
                                              Validate JWT
                                              Check tenant match
                                              Check role
                                                      │
                                                      ▼
                                              Return member list
```

Apps delegate user/tenant/member operations to the proxy. Apps never access the users/tenants DB directly.

#### Type 3: Browser -> volta-auth-proxy (auth UI)

```
Browser ─── GET /login ──────────────────► volta-auth-proxy
Browser ─── GET /invite/{code} ──────────► volta-auth-proxy
Browser ─── GET /admin/members ──────────► volta-auth-proxy
Browser ─── GET /settings/sessions ──────► volta-auth-proxy
```

All auth-related UI (login, invite, admin, sessions) is served directly by volta-auth-proxy using jte templates.

### What Apps Need To Do

Apps have exactly 2 responsibilities:

```
1. Read identity from headers (ForwardAuth)
   ┌──────────────────────────────────────┐
   │  X-Volta-User-Id: u_abc123           │
   │  X-Volta-Tenant-Id: t_xyz789         │
   │  X-Volta-Roles: ADMIN,MEMBER         │
   │  X-Volta-JWT: eyJhbGci...            │
   └──────────────────────────────────────┘
   App reads these headers on every request.
   For maximum security, verify X-Volta-JWT signature.

2. Call Internal API for user/tenant data
   ┌──────────────────────────────────────┐
   │  GET  /api/v1/users/me               │
   │  GET  /api/v1/tenants/{tid}/members   │
   │  POST /api/v1/tenants/{tid}/invitations│
   │  ...                                  │
   └──────────────────────────────────────┘
   App forwards the user's JWT as Authorization header.
   volta-auth-proxy handles all auth logic.
```

Apps do NOT:
- Handle login/logout
- Manage users or tenants
- Issue or verify JWTs (unless they want extra security)
- Store auth state
- Talk to Google OIDC

### Connecting a New App

**Step 1:** Register in `volta-config.yaml`

```yaml
apps:
  - id: my-new-app
    url: https://my-new-app.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
```

**Step 2:** Add Traefik route with ForwardAuth middleware

```yaml
# traefik dynamic config
http:
  routers:
    my-new-app:
      rule: "Host(`my-new-app.example.com`)"
      middlewares: [volta-auth]
      service: my-new-app
  services:
    my-new-app:
      loadBalancer:
        servers:
          - url: "http://my-new-app:8080"
```

**Step 3:** Read headers in your app

```java
// Javalin example
app.get("/api/data", ctx -> {
    String userId = ctx.header("X-Volta-User-Id");
    String tenantId = ctx.header("X-Volta-Tenant-Id");
    String roles = ctx.header("X-Volta-Roles");
    // ... your business logic
});
```

Or use volta-sdk for JWT verification:

```java
VoltaAuth volta = VoltaAuth.builder()
    .jwksUrl("http://volta-auth-proxy:7070/.well-known/jwks.json")
    .expectedIssuer("volta-auth")
    .expectedAudience("volta-apps")
    .build();

app.before("/api/*", volta.middleware());
```

**Step 4:** Add volta-sdk-js to your frontend

```html
<script src="http://volta-auth-proxy:7070/js/volta.js"></script>
<script>
  Volta.init({ gatewayUrl: "http://volta-auth-proxy:7070" });
  // All fetch calls now auto-handle 401 -> refresh -> retry
  const res = await Volta.fetch("/api/data");
</script>
```

**That's it.** Your app now has multi-tenant auth with zero auth code.

### Docker Compose Example (Full Stack)

```yaml
services:
  postgres:
    image: postgres:16-alpine
    ports: ["54329:5432"]
    environment:
      POSTGRES_DB: volta_auth
      POSTGRES_USER: volta
      POSTGRES_PASSWORD: volta

  volta-auth-proxy:
    build: .
    ports: ["7070:7070"]
    depends_on: [postgres]
    env_file: .env

  traefik:
    image: traefik:v3.0
    ports: ["80:80"]
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - ./traefik.yaml:/etc/traefik/traefik.yaml

  my-app:
    image: my-app:latest
    labels:
      - "traefik.http.routers.my-app.rule=Host(`my-app.localhost`)"
      - "traefik.http.routers.my-app.middlewares=volta-auth"
```

---

## Design Philosophy

- **Control is king** -- Minimize external dependencies
- **Choose the hell you understand** -- Both Keycloak config hell and DIY auth hell are hell. At least with DIY you can read the stack trace. Auth stays in-house. Never trust a system you don't understand with your users' authentication
- **Tight coupling, no apologies** -- Single process. Microservice-style loose coupling brings configuration and network complexity, not correctness. Auth is latency-sensitive and failure-propagating. Fewer network hops, debug in one place
- **ForwardAuth pattern** -- Proxy never relays request bodies. Auth check only
- **Apps do only 2 things** -- Read headers or call APIs
- **Phase-minimal** -- Build only what's needed now. Leave Interface extension points for later. Never leak app-specific logic into the proxy

---

## What It Does

| Capability | Description |
|-----------|-------------|
| **Google OIDC** | Direct integration. No intermediate IdP. PKCE + state + nonce |
| **JWT Issuance** | RS256 self-signed. 5-min expiry. JWKS endpoint at `/.well-known/jwks.json` |
| **Session** | Signed cookie (`__volta_session`). 8h sliding. Max 5 concurrent |
| **Tenant Resolution** | Cookie/JWT > subdomain > email domain > invite code > manual selection |
| **Role Hierarchy** | OWNER > ADMIN > MEMBER > VIEWER |
| **Invitations** | Crypto-random codes. Expiry. Usage limits. Consent screen |
| **ForwardAuth** | `GET /auth/verify` returns `X-Volta-*` headers to Traefik |
| **Internal API** | `/api/v1/*` for apps to delegate user/tenant/member CRUD |
| **Audit Log** | All auth events logged to `audit_logs` table |
| **CSRF** | Token-based for HTML forms. JSON API exempt (SameSite + Content-Type) |
| **Rate Limiting** | Caffeine-based. Per-IP for login, per-user for API |
| **Dev Mode** | `POST /dev/token` for local development (disabled in production) |

---

## Tech Stack

| Component | Choice | Why |
|-----------|--------|-----|
| Language | Java 21 | LTS, mature ecosystem |
| Build | **Maven** | Stable across Java version upgrades (not Gradle) |
| Web | Javalin 6.x | Lightweight, ~200ms startup |
| Template | jte 3.x | Type-safe, compile-time checked |
| JWT | nimbus-jose-jwt | Java JOSE/JWT de facto standard |
| DB | Postgres 16 | Reliable, JSONB for audit logs |
| Migration | Flyway | Auto-runs on startup |
| Pool | HikariCP | Fast connection pool |
| Cache | Caffeine | In-memory, for rate limiting + session cache |
| CSS | Single file `volta.css` | Mobile-first, responsive |
| JS | `volta.js` (~150 lines) | Vanilla JS. No framework |

---

## Quick Start

```bash
# 1. Start Postgres
docker compose up -d postgres

# 2. Copy and edit environment
cp .env.example .env
# Edit .env: set GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, etc.

# 3. Build and run
mvn compile exec:java

# 4. Open
open http://localhost:7070/login
```

### Prerequisites

- Java 21+
- Maven 3.9+
- Docker (for Postgres)
- Google Cloud Console project with OAuth 2.0 credentials

---

## Project Structure

```
volta-auth-proxy/
  pom.xml                          Maven config
  docker-compose.yml               Postgres (port 54329)
  volta-config.yaml                App registration
  .env.example                     Environment template

  src/main/java/com/volta/authproxy/
    Main.java                      Entry point, routes
    AppConfig.java                 Environment variables
    AppRegistry.java               volta-config.yaml loader
    AuthService.java               Login, logout, session, tenant switch
    OidcService.java               Google OIDC direct integration
    JwtService.java                JWT issuance + JWKS endpoint
    KeyCipher.java                 AES-256-GCM key encryption
    SecurityUtils.java             CSRF, HMAC, random tokens
    SqlStore.java                  All DB queries (single class)
    Models.java                    Records: User, Tenant, Membership, etc.
    AuditService.java              Audit log writer
    RateLimiter.java               Caffeine-based rate limiter
    HttpSupport.java               HTTP client for OIDC
    ApiException.java              Typed error responses

  src/main/jte/                    jte templates
    layout/base.jte                Shared HTML layout
    auth/login.jte                 Login page (Google button)
    auth/callback.jte              OIDC callback interstitial
    auth/tenant-select.jte         Tenant selection
    auth/invite-consent.jte        Invitation consent
    auth/sessions.jte              Session management
    admin/members.jte              Member management
    admin/invitations.jte          Invitation management
    error/error.jte                Error pages

  src/main/resources/
    db/migration/
      V1__init.sql                 9 core tables
      V2__oidc_flows.sql           OIDC state/nonce tracking
      V3__csrf_token.sql           CSRF support
    public/
      css/volta.css                Responsive stylesheet
      js/volta.js                  volta-sdk-js

  dge/                             DGE design sessions + specs
  tasks/                           Implementation tasks (for Codex/AI)
  backlog/                         Future phase specs
```

---

## Database Schema

9 tables + 2 support tables:

```
users ──┐
        ├── memberships ──── tenants ──── tenant_domains
        ├── sessions
        └── invitations (created_by)
             └── invitation_usages

signing_keys (independent)
audit_logs (independent)
oidc_flows (OIDC state/nonce tracking)
```

| Table | Purpose |
|-------|---------|
| `users` | User accounts (email, display_name, google_sub) |
| `tenants` | Workspaces (name, slug, email_domain, plan) |
| `tenant_domains` | Multiple domains per tenant |
| `memberships` | User-tenant relationships (role, joined_at) |
| `sessions` | Active sessions (cookie-based, sliding 8h) |
| `signing_keys` | JWT RSA key pairs (AES-256-GCM encrypted) |
| `invitations` | Invite codes (expiry, usage limits, email restriction) |
| `invitation_usages` | Track who used which invitation |
| `audit_logs` | All auth events (JSONB details) |

Migration runs automatically on startup via Flyway.

---

## API Reference

### Auth Endpoints (Browser)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/login` | Start Google OIDC flow |
| GET | `/callback` | OIDC callback (interstitial) |
| POST | `/auth/callback/complete` | Complete OIDC verification |
| POST | `/auth/refresh` | Refresh JWT from session cookie |
| POST | `/auth/logout` | Logout, clear session |
| GET | `/select-tenant` | Tenant selection page |
| POST | `/auth/switch-tenant` | Switch active tenant |
| GET | `/invite/{code}` | Invitation landing page |
| POST | `/invite/{code}/accept` | Accept invitation |
| GET | `/settings/sessions` | Session management page |
| GET | `/admin/members` | Member management page |
| GET | `/admin/invitations` | Invitation management page |

### ForwardAuth (Traefik Integration)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/auth/verify` | Verify session, return `X-Volta-*` headers |

**Response headers on 200:**

```
X-Volta-User-Id:      <uuid>
X-Volta-Email:        <email>
X-Volta-Tenant-Id:    <uuid>
X-Volta-Tenant-Slug:  <slug>
X-Volta-Roles:        ADMIN,MEMBER
X-Volta-Display-Name: Taro Yamada
X-Volta-JWT:          <signed RS256 JWT>
X-Volta-App-Id:       app-wiki
```

### Internal API (App -> Proxy)

Authentication: `Authorization: Bearer <user-jwt>` or `Authorization: Bearer volta-service:<token>`

**Users:**

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/users/me` | Any | Current user profile |
| GET | `/api/v1/users/me/tenants` | Any | User's tenant list |
| PATCH | `/api/v1/users/{id}` | Self or ADMIN+ | Update display_name |

**Tenant Members:**

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/tenants/{tid}/members` | MEMBER+ | List members (paginated) |
| GET | `/api/v1/tenants/{tid}/members/{uid}` | MEMBER+ | Member detail |
| PATCH | `/api/v1/tenants/{tid}/members/{uid}` | ADMIN+ | Change role |
| DELETE | `/api/v1/tenants/{tid}/members/{uid}` | ADMIN+ | Remove member |

**Invitations:**

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/tenants/{tid}/invitations` | ADMIN+ | List invitations |
| POST | `/api/v1/tenants/{tid}/invitations` | ADMIN+ | Create invitation |
| DELETE | `/api/v1/tenants/{tid}/invitations/{iid}` | ADMIN+ | Cancel invitation |

**Admin (Key Management):**

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/admin/keys` | OWNER | List signing keys |
| POST | `/api/v1/admin/keys/rotate` | OWNER | Rotate signing key |
| POST | `/api/v1/admin/keys/{kid}/revoke` | OWNER | Revoke signing key |

**Development:**

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/dev/token` | DEV_MODE only | Generate test JWT |
| GET | `/healthz` | None | Health check |
| GET | `/.well-known/jwks.json` | None | JWKS public keys |

### Pagination

All list endpoints support `?offset=0&limit=20` (max 100).

```json
{
  "data": [...],
  "meta": { "total": 150, "limit": 20, "offset": 0, "request_id": "uuid" }
}
```

### Error Format

```json
{
  "error": {
    "code": "TENANT_ACCESS_DENIED",
    "message": "You do not have access to this workspace",
    "status": 403,
    "request_id": "uuid"
  }
}
```

| HTTP | Code | Meaning |
|------|------|---------|
| 401 | `AUTHENTICATION_REQUIRED` | Not logged in |
| 401 | `SESSION_EXPIRED` | Session timed out |
| 401 | `SESSION_REVOKED` | Session was revoked |
| 403 | `FORBIDDEN` | No permission |
| 403 | `TENANT_ACCESS_DENIED` | Not a member of this tenant |
| 403 | `TENANT_SUSPENDED` | Tenant is suspended |
| 403 | `ROLE_INSUFFICIENT` | Role too low for this action |
| 404 | `NOT_FOUND` | Resource not found |
| 409 | `CONFLICT` | Already exists |
| 410 | `INVITATION_EXPIRED` | Invitation expired |
| 410 | `INVITATION_EXHAUSTED` | Invitation fully used |
| 429 | `RATE_LIMITED` | Too many requests |

---

## JWT Specification

```
Algorithm:  RS256 (RSA 2048-bit)
Expiry:     5 minutes
Key store:  signing_keys table (AES-256-GCM encrypted)
JWKS:       GET /.well-known/jwks.json
```

**Claims:**

```json
{
  "iss": "volta-auth",
  "aud": ["volta-apps"],
  "sub": "user-uuid",
  "exp": 1711900000,
  "iat": 1711899700,
  "jti": "uuid",
  "volta_v": 1,
  "volta_tid": "tenant-uuid",
  "volta_tname": "ACME Corp",
  "volta_tslug": "acme",
  "volta_roles": ["ADMIN"],
  "volta_display": "Taro Yamada"
}
```

**Security rules:**
- `alg` must be RS256. HS256 and none are rejected
- `kid` required in header
- `aud` is an array (for Phase 2 per-app audience)
- Email is NOT in JWT claims (fetch via `/api/v1/users/me` instead)

---

## Tenant Resolution

Priority order:

1. Existing session cookie with `tenant_id` -- use it
2. URL subdomain -- lookup in `tenant_domains`
3. Email domain -- lookup in `tenant_domains` (free email excluded)
4. None found -- show invitation code prompt or tenant selection

**Free email domains excluded:** gmail.com, outlook.com, yahoo.com, yahoo.co.jp, hotmail.com, icloud.com, protonmail.com

---

## Role Hierarchy

```
OWNER > ADMIN > MEMBER > VIEWER
```

| Role | Permissions |
|------|------------|
| OWNER | Delete tenant, transfer ownership, all ADMIN permissions |
| ADMIN | Invite/remove members, change roles (up to ADMIN), change tenant settings |
| MEMBER | Normal usage |
| VIEWER | Read-only (enforced by app) |

---

## App Registration

Define apps in `volta-config.yaml`:

```yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]

  - id: app-admin
    url: https://admin.example.com
    allowed_roles: [ADMIN, OWNER]
```

ForwardAuth (`/auth/verify`) enforces `allowed_roles` per app.

### Traefik Configuration

```yaml
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
```

---

## volta-sdk-js

Browser SDK (~150 lines, vanilla JS). Handles session refresh, tenant switching, and 401 recovery.

```html
<script src="/js/volta.js"></script>
<script>
  Volta.init({ gatewayUrl: "" });  // same origin

  // Auth-aware fetch (auto-refresh on 401)
  const res = await Volta.fetch("/api/data");

  // Switch tenant (triggers page reload)
  await Volta.switchTenant("tenant-uuid");

  // Logout
  await Volta.logout();
</script>
```

**For form submission, use `Volta.fetch()` instead of HTML form POST:**

```javascript
document.querySelector("form").addEventListener("submit", function(e) {
  e.preventDefault();
  Volta.fetch("/api/data", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(Object.fromEntries(new FormData(e.target)))
  });
});
```

This ensures session expiry during form input is handled transparently (auto-refresh + retry).

---

## volta-sdk (Java, for Apps)

```java
VoltaAuth volta = VoltaAuth.builder()
    .jwksUrl("http://volta-auth-proxy:7070/.well-known/jwks.json")
    .expectedIssuer("volta-auth")
    .expectedAudience("volta-apps")
    .jwksCacheDuration(Duration.ofHours(1))
    .build();

// Javalin middleware
app.before("/api/*", volta.middleware());

// Access identity context
app.get("/api/data", ctx -> {
    VoltaUser user = VoltaAuth.getUser(ctx);
    String tenantId = user.getTenantId();
    if (!user.hasRole("ADMIN")) throw new ForbiddenResponse();
});
```

---

## Content Negotiation

```
Accept: application/json  -->  Always JSON response (never 302)
Accept: text/html         -->  HTML or redirect
X-Requested-With: XMLHttpRequest  -->  Treated as JSON
Authorization: Bearer ...  -->  Treated as JSON
```

**SPA rule:** Gateway never returns 302 to JSON requests. Always 401 JSON. This prevents `fetch()` from receiving Google login HTML.

---

## Security

| Feature | Implementation |
|---------|---------------|
| OIDC | state (CSRF) + nonce (replay) + PKCE (S256) |
| JWT signing | RS256. HS256/none rejected. alg whitelist |
| Key storage | AES-256-GCM encrypted in DB |
| Key rotation | `POST /api/v1/admin/keys/rotate` (OWNER) |
| Session | HMAC-SHA256 signed cookie. HttpOnly, Secure, SameSite=Lax |
| Session fixation | Session ID regenerated on login |
| CSRF | Token-based for HTML forms. JSON API exempt |
| Rate limiting | Per-IP for login (10/min), per-user for API (200/min) |
| Tenant isolation | Path tenantId must match JWT volta_tid |
| OWNER protection | Last OWNER cannot be demoted |
| Audit | All auth events logged with actor, IP, request_id |
| Cache-Control | `no-store, private` on auth endpoints |

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | 7070 | Server port |
| `DB_HOST` | localhost | Postgres host |
| `DB_PORT` | 54329 | Postgres port |
| `DB_NAME` | volta_auth | Database name |
| `DB_USER` | volta | Database user |
| `DB_PASSWORD` | volta | Database password |
| `BASE_URL` | http://localhost:7070 | Public URL |
| `GOOGLE_CLIENT_ID` | | Google OAuth client ID |
| `GOOGLE_CLIENT_SECRET` | | Google OAuth client secret |
| `GOOGLE_REDIRECT_URI` | http://localhost:7070/callback | OAuth redirect URI |
| `JWT_ISSUER` | volta-auth | JWT issuer claim |
| `JWT_AUDIENCE` | volta-apps | JWT audience claim |
| `JWT_TTL_SECONDS` | 300 | JWT expiry (5 min) |
| `JWT_KEY_ENCRYPTION_SECRET` | | AES-256 key for encrypting signing keys |
| `SESSION_TTL_SECONDS` | 28800 | Session expiry (8 hours) |
| `ALLOWED_REDIRECT_DOMAINS` | localhost,127.0.0.1 | Whitelist for return_to |
| `VOLTA_SERVICE_TOKEN` | | Static service token for M2M (Phase 1) |
| `DEV_MODE` | false | Enable /dev/token endpoint |
| `APP_CONFIG_PATH` | volta-config.yaml | Path to app registry |
| `SUPPORT_CONTACT` | | Admin contact shown on error pages |

---

## Phase Roadmap

| Phase | Status | What |
|-------|--------|------|
| **Phase 1: Core** | In progress | Google OIDC, tenants, roles, invitations, ForwardAuth, Internal API |
| Phase 2: Scale | Planned | Multiple IdPs (GitHub, Microsoft), M2M (Client Credentials), Redis sessions, Webhooks |
| Phase 3: Enterprise | Planned | SAML, email notifications, MFA (TOTP), i18n, admin UI expansion |
| Phase 4: Platform | Planned | SCIM, Policy Engine, Billing (Stripe), GDPR data export/deletion |

Full specification: [`dge/specs/implementation-all-phases.md`](dge/specs/implementation-all-phases.md)

---

## Design Documentation

This project was designed using DGE (Dialogue-driven Gap Extraction) -- 106 design gaps identified and resolved across 8 sessions.

| Document | Description |
|----------|-------------|
| [`dge/specs/implementation-all-phases.md`](dge/specs/implementation-all-phases.md) | Full implementation spec (all phases) |
| [`dge/specs/ux-specs-phase1.md`](dge/specs/ux-specs-phase1.md) | UI/UX specifications |
| [`dge/specs/ui-flow.md`](dge/specs/ui-flow.md) | Screen transition flows (mermaid diagrams) |
| [`dge/feedback/2026-03-31-volta-auth-proxy.md`](dge/feedback/2026-03-31-volta-auth-proxy.md) | DGE method feedback |
| [`tasks/001-fix-critical-bugs-and-implement-templates.md`](tasks/001-fix-critical-bugs-and-implement-templates.md) | Current implementation task |
| [`backlog/001-form-state-recovery.md`](backlog/001-form-state-recovery.md) | Phase 2: Form auto-save on session expiry |

---

## License

TBD
