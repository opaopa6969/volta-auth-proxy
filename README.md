# volta-auth-proxy

[English](README.md) | [Japanese (日本語)](README.ja.md)

Multi-tenant identity gateway for SaaS.
Handles auth, tenants, roles, invitations so downstream apps don't have to.

**No Keycloak. No oauth2-proxy. Control is king.**

> **Auth terminology is hard. But pretending you understand is the most dangerous thing.**
> Every technical term in this document links to a [glossary article](docs/glossary/).
> Click and read. It's not embarrassing. Knowing what you don't know is the most important thing.
> In the age of AI, education matters.

## Phase 2-4 integrations implemented

- M2M OAuth client credentials: `POST /oauth/token`
- Webhook outbox worker (retry + HMAC signature) with `WEBHOOK_ENABLED=true`
- IdP admin (OIDC/SAML config) + SAML login entry route (`/auth/saml/login`)
- MFA TOTP setup/verify APIs
- SCIM user/group endpoints (`/scim/v2/*`) with service token auth
- Billing webhook ingestion (`POST /api/v1/billing/stripe/webhook`)
- External audit sink options: `postgres | kafka | elasticsearch`
- Notification channels: `smtp | sendgrid | none`

---

## Why volta-auth-proxy?

You're building a SaaS. You need auth. Your options:

| Option | What happens |
|--------|-------------|
| Auth0 / Clerk | Works fast. Then $2,400/month at 100k MAU. Vendor lock-in. Can't self-host |
| Keycloak | Free. Then 500+ line realm.json config hell. 512MB RAM. 30s startup. Theme customization with FreeMarker |
| Build from scratch | Full control. But you have to get OIDC, JWT, sessions, CSRF, tenant isolation all right |

**volta-auth-proxy is option 3, done right.** The hard parts (OIDC flow, JWT signing, session management, tenant resolution) are already built. Your apps just read headers.

### Compared to Alternatives

| | volta-auth-proxy | Keycloak | Auth0 | ZITADEL | Ory Stack |
|---|---|---|---|---|---|
| **Self-hosted** | Yes (only) | Yes | No | Yes | Yes |
| **Startup** | ~200ms | ~30s | N/A | ~3-5s | ~seconds |
| **Memory** | ~30MB | ~512MB+ | N/A | ~150-300MB | ~200-400MB |
| **Multi-tenant** | Core design | Realm-based (limited) | Organizations (paid) | Native | DIY |
| **Login UI** | Full control (jte) | Theme hell | Limited | Theme | DIY |
| **Cost at 100k MAU** | $0 | $0 (ops cost) | ~$2,400/mo | $0 (self-host) | $0 |
| **App integration** | ForwardAuth + Internal API | Generic OIDC | SDK | Generic OIDC | Oathkeeper |
| **Config complexity** | .env + 1 YAML | Hundreds of settings | Dashboard | Moderate | 4 services |
| **Dependencies** | Postgres only | Postgres + JVM | Cloud | Postgres/CRDB | Postgres + multiple |

volta is the lightest, most controllable option. The tradeoff: you own the security responsibility.

---

## Features

### Authentication

| Feature | Detail |
|---------|--------|
| Google OIDC | Direct integration with PKCE + state + nonce. No middleware |
| Session management | Signed cookie. 8h sliding window. Max 5 concurrent sessions per user |
| JWT issuance | RS256 self-signed. 5-min expiry. Auto key generation on first boot |
| JWKS endpoint | `/.well-known/jwks.json`. Serves active + rotated keys |
| Key rotation | Admin API to rotate/revoke. Graceful transition with overlap period |
| Silent refresh | volta-sdk-js auto-refreshes JWT on 401. User never sees login during normal use |
| Logout | Single-device and all-device. Session invalidation propagates via JWT expiry (max 5 min lag) |

### Multi-tenancy

| Feature | Detail |
|---------|--------|
| Tenant resolution | 4-level priority: session > subdomain > email domain > invite/manual |
| Free email handling | gmail.com, outlook.com etc automatically excluded from domain matching |
| Multiple membership | One user can belong to multiple tenants with different roles |
| Tenant switching | In-session switch via API. Page reload for clean state |
| Tenant suspension | Suspended tenant blocks all member access. Other tenant access preserved |
| Tenant isolation | API path tenantId must match JWT claim. Cross-tenant access structurally prevented |

### Invitation System

| Feature | Detail |
|---------|--------|
| Invite codes | Crypto-random 32 bytes (base64url, 43 chars). Unpredictable |
| Expiry | Configurable per invitation. Default 72h |
| Usage limits | Single-use or multi-use (max configurable) |
| Email restriction | Optional: lock invitation to specific email address |
| Consent screen | Explicit "Join this workspace?" confirmation before membership creation |
| Status tracking | Pending / Used / Expired. Admin can see who used what |
| Link sharing | Copy button + QR code (no email sending in Phase 1) |

### Role-Based Access Control

| Feature | Detail |
|---------|--------|
| 4-level hierarchy | OWNER > ADMIN > MEMBER > VIEWER |
| Per-app enforcement | volta-config.yaml defines allowed_roles per app. Enforced at ForwardAuth |
| Tenant-scoped | Roles are per tenant. User can be ADMIN in tenant A and VIEWER in tenant B |
| OWNER protection | Last OWNER cannot be demoted or removed |
| Role management UI | Admin page for changing member roles. Drag-down with confirmation |

### Security

| Feature | Detail |
|---------|--------|
| OIDC security | state (CSRF) + nonce (replay prevention) + PKCE (S256) |
| JWT security | RS256 only. HS256/none rejected. alg whitelist enforced |
| Key encryption | Private keys AES-256-GCM encrypted at rest in DB |
| CSRF protection | Token-based for HTML forms. JSON API exempt via SameSite + Content-Type |
| Rate limiting | Per-IP for login (10/min). Per-user for API (200/min) |
| Session fixation | Session ID regenerated on every login |
| Content negotiation | JSON requests never get 302 redirects. Prevents SPA fetch confusion |
| Audit logging | Every auth event: login, logout, role change, invitation, session revoke |
| Cache control | `no-store, private` on all auth endpoints. Prevents back-button data leaks |

### Developer Experience

| Feature | Detail |
|---------|--------|
| ForwardAuth | Apps get identity via HTTP headers. Zero auth code needed |
| Internal API | REST API for user/tenant/member CRUD delegation |
| volta-sdk-js | Browser SDK (~150 lines). Auto 401 refresh, tenant switch, logout |
| volta-sdk (Java) | Javalin middleware for JWT verification |
| Dev mode | `POST /dev/token` generates test JWTs for local development |
| Health check | `GET /healthz` for monitoring |
| Fast startup | ~200ms. Local dev cycle is instant |
| Minimal deps | Gateway + Postgres. That's it |

### Admin UI

| Feature | Detail |
|---------|--------|
| Member management | List, role change, remove. Per-tenant |
| Invitation management | Create, list, cancel. Copy link, QR code |
| Session management | User can view all active sessions, revoke individually or all |

---

## Installation

### Option 1: Local Development (Recommended for first try)

```bash
# Clone
git clone git@github.com:opaopa6969/volta-auth-proxy.git
cd volta-auth-proxy

# Start Postgres
docker compose up -d postgres

# Configure
cp .env.example .env
# Edit .env:
#   GOOGLE_CLIENT_ID=your-google-client-id
#   GOOGLE_CLIENT_SECRET=your-google-client-secret
#   JWT_KEY_ENCRYPTION_SECRET=some-random-32-byte-string
#   VOLTA_SERVICE_TOKEN=some-random-64-byte-string

# Build and run
mvn compile exec:java

# Verify
curl http://localhost:7070/healthz
# {"status":"ok"}
```

### Option 2: Docker Compose (Full Stack)

```bash
# Clone and configure
git clone git@github.com:opaopa6969/volta-auth-proxy.git
cd volta-auth-proxy
cp .env.example .env
# Edit .env (same as above)

# Start everything
docker compose up -d

# Verify
curl http://localhost:7070/healthz
```

### Option 3: Production Deployment

```bash
# Build fat JAR
mvn package -DskipTests

# Run
java -jar target/volta-auth-proxy-0.1.0-SNAPSHOT.jar

# Environment variables must be set (see .env.example)
# Postgres must be accessible
# Flyway runs migrations automatically on startup
```

### Google OAuth Setup

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create or select a project
3. Navigate to APIs & Services > Credentials
4. Create OAuth 2.0 Client ID (Web application)
5. Add authorized redirect URI: `http://localhost:7070/callback`
6. Copy Client ID and Client Secret to `.env`

### Prerequisites

| Requirement | Version | Notes |
|------------|---------|-------|
| Java | 21+ | LTS recommended |
| Maven | 3.9+ | Build tool |
| Postgres | 16+ | Via Docker or local install |
| Docker | 24+ | For Postgres (optional if you have local Postgres) |

---

## Usage Guide

### As a Platform Operator

#### 1. Create Your First Tenant

```bash
# Start the proxy, then use the admin API:
# (You'll need to create the first user + tenant via direct DB insert
#  or use DEV_MODE=true to bootstrap)

# With DEV_MODE=true:
curl -X POST http://localhost:7070/dev/token \
  -H 'Content-Type: application/json' \
  -d '{"userId":"admin-001","tenantId":"tenant-001","roles":["OWNER"]}'
```

#### 2. Invite Team Members

Open `http://localhost:7070/admin/invitations` and create an invitation.
Copy the link and share via Slack/email.

#### 3. Register Apps

Edit `volta-config.yaml`:

```yaml
apps:
  - id: my-wiki
    url: https://wiki.mycompany.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
```

Configure Traefik to use ForwardAuth middleware (see Architecture section).

### As an App Developer

#### Minimal Integration (Headers Only)

```java
// Read identity from Traefik-forwarded headers
app.get("/api/data", ctx -> {
    String userId = ctx.header("X-Volta-User-Id");
    String tenantId = ctx.header("X-Volta-Tenant-Id");

    // Use tenantId in your DB queries
    var data = db.query("SELECT * FROM items WHERE tenant_id = ?", tenantId);
    ctx.json(data);
});
```

#### Full Integration (JWT Verification + SDK)

```java
// Server-side: verify JWT
VoltaAuth volta = VoltaAuth.builder()
    .jwksUrl("http://volta-auth-proxy:7070/.well-known/jwks.json")
    .expectedIssuer("volta-auth")
    .expectedAudience("volta-apps")
    .build();

app.before("/api/*", volta.middleware());

app.get("/api/data", ctx -> {
    VoltaUser user = VoltaAuth.getUser(ctx);

    // Tenant-scoped query
    var data = db.query(
        "SELECT * FROM items WHERE tenant_id = ?",
        user.getTenantId()
    );

    // Role check
    if (user.hasRole("ADMIN")) {
        // admin-only logic
    }

    ctx.json(data);
});
```

```html
<!-- Client-side: volta-sdk-js -->
<script src="http://volta-auth-proxy:7070/js/volta.js"></script>
<script>
  Volta.init({ gatewayUrl: "http://volta-auth-proxy:7070" });

  // Fetch with auto 401 recovery
  async function loadData() {
    const res = await Volta.fetch("/api/data");
    const data = await res.json();
    renderTable(data);
  }

  // Form submit with auto 401 recovery
  document.querySelector("#my-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    await Volta.fetch("/api/data", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(Object.fromEntries(new FormData(e.target)))
    });
    alert("Saved!");
  });

  // Tenant switch
  document.querySelector("#switch-btn").addEventListener("click", () => {
    Volta.switchTenant("other-tenant-id"); // triggers reload
  });
</script>
```

#### Delegating User/Tenant Operations

```java
// App calls proxy's Internal API to list tenant members
app.get("/app/team", ctx -> {
    String jwt = ctx.header("X-Volta-JWT");
    String tenantId = ctx.header("X-Volta-Tenant-Id");

    // Forward user's JWT to proxy
    var response = httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://volta-auth-proxy:7070/api/v1/tenants/" + tenantId + "/members"))
            .header("Authorization", "Bearer " + jwt)
            .build(),
        HttpResponse.BodyHandlers.ofString()
    );

    ctx.json(response.body());
});
```

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
| Phase 2: Scale | Planned | Multiple IdPs (GitHub, Microsoft), M2M (Client Credentials), Redis sessions, Webhooks, **Passkeys (WebAuthn/FIDO2)** |
| Phase 3: Enterprise | Planned | SAML, email notifications, **MFA/2FA (TOTP, WebAuthn)**, i18n, admin UI expansion, **Conditional access (risk-based auth)**, **Fraud alert integration** |
| Phase 4: Platform | Planned | SCIM, Policy Engine, Billing (Stripe), GDPR data export/deletion, **Device trust**, **Mobile SDK (iOS/Android)** |

### Auth Trend Roadmap

| Trend | Phase | Approach |
|-------|-------|----------|
| **Passkeys (WebAuthn/FIDO2)** | Phase 2 | Passwordless auth mainstream. Add as 2nd auth method alongside Google |
| **MFA/2FA (TOTP)** | Phase 3 | Google Authenticator etc. Tenant admins can enforce "MFA required" |
| **Risk-based auth** | Phase 3 | Extra verification on new device/IP. `amr` claim reflects auth strength in JWT |
| **Fraud detection/alerting** | Phase 3 | Suspicious login detection (impossible travel, credential stuffing). Webhook alerts to admin. Integration with threat intelligence feeds |
| **Device trust** | Phase 4 | Remember known devices. Challenge unknown devices |
| **Mobile SDK** | Phase 4 | Native iOS/Android SDK. Deep link support for invite flows. Biometric auth integration |
| **SAML SSO** | Phase 3 | Enterprise customer IdP integration (Active Directory etc.) |
| **SCIM** | Phase 4 | Automated user provisioning from Okta, Azure AD etc. |

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
