# volta-auth-proxy

[English](README.md) | [Japanese (日本語)](README.ja.md)

**[Multi-tenant](docs/glossary/multi-tenant.md) auth. One [HTTP header](docs/glossary/header.md). That's it.**

[Multi-tenant](docs/glossary/multi-tenant.md) [identity gateway](docs/glossary/identity-gateway.md) for [SaaS](docs/glossary/saas.md).
Handles [authentication](docs/glossary/authentication-vs-authorization.md), [tenant](docs/glossary/tenant.md)s, [role](docs/glossary/role.md)s, [invitations](docs/glossary/invitation-flow.md) so [downstream](docs/glossary/downstream-app.md) apps don't have to.

**No [Keycloak](docs/glossary/keycloak.md). No [oauth2-proxy](docs/glossary/reverse-proxy.md). Control is king.**

> **Auth terminology is hard. But pretending you understand is the most dangerous thing.**
> Every technical term in this document links to a [glossary article](docs/glossary/).
> Click and read. It's not embarrassing. Knowing what you don't know is the most im[port](docs/glossary/port.md)ant thing.
> In the age of AI, education matters.

> Every word is clickable. Every term is explained.
> Whether you're a grandmother or a new engineer, click and you'll understand.
> It's hell to write, but heaven to read.

## Who this is for

- You're building a [BtoB SaaS](docs/glossary/saas.md)
- You need [multi-tenant](docs/glossary/multi-tenant.md) [authentication](docs/glossary/authentication-vs-authorization.md)
- You're tired of [Keycloak](docs/glossary/keycloak.md) or [Auth0](docs/glossary/auth0.md)
- But you don't want to write [auth](docs/glossary/authentication-vs-authorization.md) from scratch

**→ Just read [HTTP headers](docs/glossary/header.md). That's all your apps need to do.**

> **New here?** [はじめよう：会話で学ぶ volta-auth-proxy](docs/getting-started-dialogue.ja.md)

***

## How it works

```
Browser ──→ Traefik ──────────────────────────────────→ App
                  │                                      ↑
                  └──→ volta (ForwardAuth check) ────────┘
                                  │
                         auth + tenant resolution
                                  │
                      X-Volta-User-Id: abc123
                      X-Volta-Tenant-Id: t456
                      X-Volta-Role: MEMBER
```

Every request is checked by volta before reaching your app.
Volta validates the session, resolves the tenant, and writes the result as headers.
**Your app reads headers. Zero auth code.**

***

## Quick Start (5 min)

```bash
# 1. Clone
git clone https://github.com/your-org/volta-auth-proxy
cd volta-auth-proxy

# 2. Create a Google OAuth app, set redirect URI to http://localhost:7070/callback
#    https://console.cloud.google.com/ → Credentials → OAuth 2.0 Client IDs
export GOOGLE_CLIENT_ID=your-client-id
export GOOGLE_CLIENT_SECRET=your-client-secret

# 3. Start the database
docker-compose up -d

# 4. Start volta
mvn compile exec:java

# 5. Open in browser
open http://localhost:7070/login
```

***

## Phase 2-4 integrations implemented

- [M2M](docs/glossary/m2m.md) [OAuth](docs/glossary/oauth2.md) [client credentials](docs/glossary/client-credentials.md): `POST /oauth/token`
- [Webhook](docs/glossary/webhook.md) outbox worker ([retry](docs/glossary/retry.md) + HMAC signature) with `WEBHOOK_ENABLED=true`
- [IdP](docs/glossary/idp.md) admin ([OIDC](docs/glossary/oidc.md)/[SAML](docs/glossary/sso.md) config) + SAML [login](docs/glossary/login.md) entry route (`/auth/saml/login`)
- [MFA](docs/glossary/mfa.md) [TOTP](docs/glossary/totp.md) setup/verify [API](docs/glossary/api.md)s
- SCIM user/group [endpoint](docs/glossary/endpoint.md)s (`/scim/v2/*`) with [service token](docs/glossary/service-token.md) auth
- [Billing](docs/glossary/billing.md) [webhook](docs/glossary/webhook.md) [ingestion](docs/glossary/ingestion.md) (`POST /api/v1/billing/stripe/webhook`)
- External audit sink options: `postgres | kafka | elasticsearch`
- Notification channels: `smtp | sendgrid | none`

***

## Why volta-auth-proxy?

You're [build](docs/glossary/build.md)ing a [SaaS](docs/glossary/saas.md). You need auth. Your options:

| Option | What happens |
|--------|-------------|
| [Auth0](docs/glossary/auth0.md) / Clerk | Works fast. Then $2,400/month at 100k [MAU](docs/glossary/mau.md). [Vendor lock-in](docs/glossary/vendor-lock-in.md). Can't [self-host](docs/glossary/self-hosting.md) |
| [Keycloak](docs/glossary/keycloak.md) | Free. Then 500+ line [realm.json](docs/glossary/realm-json.md) [config hell](docs/glossary/config-hell.md). 512MB RAM. 30s [startup](docs/glossary/startup.md). Theme customization with [FreeMarker](docs/glossary/freemarker.md) |
| [Build](docs/glossary/build.md) from scratch | Full control. But you have to get [OIDC](docs/glossary/oidc.md), [JWT](docs/glossary/jwt.md), [sessions](docs/glossary/session.md), [CSRF](docs/glossary/csrf.md), [tenant](docs/glossary/tenant.md) isolation all right |

**volta-auth-proxy is option 3, done right.** The hard parts ([OIDC](docs/glossary/oidc.md) flow, [JWT](docs/glossary/jwt.md) signing, [session](docs/glossary/session.md) management, [tenant](docs/glossary/tenant.md) resolution) are already built. Your apps just read [headers](docs/glossary/header.md).

### Compared to Alternatives

| | volta-auth-proxy | [Keycloak](docs/glossary/keycloak.md) | [Auth0](docs/glossary/auth0.md) | ZITADEL | [Ory Stack](docs/glossary/ory-stack.md) |
|---|---|---|---|---|---|
| **Self-hosted** | Yes (only) | Yes | No | Yes | Yes |
| **[Startup](docs/glossary/startup.md)** | ~200ms | ~30s | N/A | ~3-5s | ~seconds |
| **Memory** | ~30MB | ~512MB+ | N/A | ~150-300MB | ~200-400MB |
| **[Multi-tenant](docs/glossary/multi-tenant.md)** | Core design | [Realm](docs/glossary/realm.md)-based (limited) | [Organizations](docs/glossary/organizations.md) (paid) | Native | [DIY](docs/glossary/native-implementation.md) |
| **[Login](docs/glossary/login.md) UI** | Full control ([jte](docs/glossary/jte.md)) | Theme hell | Limited | Theme | DIY |
| **Cost at 100k [MAU](docs/glossary/mau.md)** | $0 | $0 (ops cost) | ~$2,400/mo | $0 (self-host) | $0 |
| **[App integration](docs/glossary/app-integration.md)** | [ForwardAuth](docs/glossary/forwardauth.md) ([📊](dge/specs/ui-flow.md#flow-2-returning-user---session-valid)) + [Internal API](docs/glossary/internal-api.md) | Generic [OIDC](docs/glossary/oidc.md) | [SDK](docs/glossary/sdk.md) | Generic OIDC | [Oathkeeper](docs/glossary/oathkeeper.md) |
| **[Config complexity](docs/glossary/complexity-of-configuration.md)** | [.env](docs/glossary/environment-variable.md) + 1 [YAML](docs/glossary/yaml.md) | Hundreds of settings | [Dashboard](docs/glossary/dashboard.md) | Moderate | 4 services |
| **[Dependencies](docs/glossary/external-dependency.md)** | [Postgres](docs/glossary/database.md) only | Postgres + [JVM](docs/glossary/jvm.md) | Cloud | Postgres/[CRDB](docs/glossary/crdb.md) | Postgres + multiple |

volta is the lightest, most controllable option. The [tradeoff](docs/glossary/tradeoff.md): you own the [security responsibility](docs/glossary/security-responsibility.md).

***

## Features

### Authentication

| Feature | Detail |
|---------|--------|
| Google [OIDC](docs/glossary/oidc.md) | Direct [integration](docs/glossary/integration.md) with [PKCE](docs/glossary/pkce.md) + [state](docs/glossary/state.md) + [nonce](docs/glossary/nonce.md). No [middleware](docs/glossary/middleware.md) |
| [Session](docs/glossary/session.md) management | Signed [cookie](docs/glossary/cookie.md). 8h [sliding](docs/glossary/sliding-window-expiry.md) window. Max 5 concurrent [session](docs/glossary/session.md)s per user |
| [JWT](docs/glossary/jwt.md) issuance | [RS256](docs/glossary/rs256.md) [self-signed](docs/glossary/self-signed.md). 5-min expiry. [Auto key generation](docs/glossary/auto-key-generation.md) on first boot |
| [JWKS](docs/glossary/jwks.md) [endpoint](docs/glossary/endpoint.md) | `/.well-known/jwks.json`. Serves active + rotated keys |
| [Key rotation](docs/glossary/key-rotation.md) | Admin [API](docs/glossary/api.md) to rotate/[revoke](docs/glossary/revoke.md). [Graceful transition](docs/glossary/graceful-transition.md) with overlap period |
| [Silent refresh](docs/glossary/silent-refresh.md) | volta-[sdk](docs/glossary/sdk.md)-js auto-refreshes [JWT](docs/glossary/jwt.md) on [HTTP status code](docs/glossary/http-status-codes.md) 401. User never sees [login](docs/glossary/login.md) during normal use (→ [see flow](dge/specs/ui-flow.md#flow-5-session-expired---silent-refresh)) |
| [Logout](docs/glossary/logout.md) | Single-device and all-device. [Session](docs/glossary/session.md) [invalidation](docs/glossary/invalidation.md) pr[opa](docs/glossary/opa.md)gates via [JWT](docs/glossary/jwt.md) expiry (max 5 min lag) (→ [see flow](dge/specs/ui-flow.md#flow-6-logout)) |

### Multi-tenancy

| Feature | Detail |
|---------|--------|
| [Tenant](docs/glossary/tenant.md) resolution | 4-level priority: [session](docs/glossary/session.md) > [subdomain](docs/glossary/subdomain.md) > email [domain](docs/glossary/domain.md) > [invitation](docs/glossary/invitation-flow.md)/manual (→ [see flow](dge/specs/ui-flow.md#flow-3-tenant-selection)) |
| Free email handling | gmail.com, outlook.com etc automatically excluded from [domain](docs/glossary/domain.md) matching |
| Multiple [membership](docs/glossary/membership.md) | One user can belong to multiple [tenants](docs/glossary/tenant.md) with different [roles](docs/glossary/role.md) |
| [Tenant](docs/glossary/tenant.md) switching | In-[session](docs/glossary/session.md) switch via [API](docs/glossary/api.md). Page reload for clean [state](docs/glossary/state.md) (→ [see flow](dge/specs/ui-flow.md#flow-4-tenant-switch-during-session)) |
| [Tenant](docs/glossary/tenant.md) [suspension](docs/glossary/suspension.md) | Suspended [tenant](docs/glossary/tenant.md) blocks all member access. Other [tenant](docs/glossary/tenant.md) access preserved |
| [Tenant](docs/glossary/tenant.md) isolation | [API](docs/glossary/api.md) path tenantId must match [JWT](docs/glossary/jwt.md) [claim](docs/glossary/claim.md). Cross-tenant access structurally prevented |

### Invitation System

| Feature | Detail |
|---------|--------|
| Invite codes | [Crypto-random](docs/glossary/crypto-random.md) 32 bytes ([base64](docs/glossary/base64.md)[url](docs/glossary/url.md), 43 chars). Unpredictable |
| Expiry | Configurable per [invitation](docs/glossary/invitation-flow.md). Default 72h |
| Usage limits | Single-use or multi-use (max configurable) |
| Email restriction | Optional: lock [invitation](docs/glossary/invitation-flow.md) to specific email address |
| [Consent screen](docs/glossary/consent-screen.md) | Explicit "Join this work[spa](docs/glossary/spa.md)ce?" confirmation before [membership](docs/glossary/membership.md) creation (→ [see flow](dge/specs/ui-flow.md#flow-1-invite-link---first-login)) |
| Status tracking | Pending / Used / Expired. Admin can see who used what |
| Link sharing | Copy button + QR code (no email sending in [Phase](docs/glossary/phase-based-development.md) 1) |

### Role-Based Access Control

| Feature | Detail |
|---------|--------|
| 4-level [hierarchy](docs/glossary/hierarchy.md) | OWNER > ADMIN > MEMBER > VIEWER |
| Per-app [enforcement](docs/glossary/enforcement.md) | volta-config.[yaml](docs/glossary/yaml.md) defines allowed\_[role](docs/glossary/role.md)s per [App](docs/glossary/downstream-app.md). Enforced at [ForwardAuth](docs/glossary/forwardauth.md) ([📊](dge/specs/ui-flow.md#flow-2-returning-user---session-valid)) |
| [Tenant](docs/glossary/tenant.md)-scoped | [Roles](docs/glossary/role.md) are per [tenant](docs/glossary/tenant.md). User can be ADMIN in [tenant](docs/glossary/tenant.md) A and VIEWER in [tenant](docs/glossary/tenant.md) B |
| OWNER protection | Last OWNER cannot be demoted or removed |
| [Role](docs/glossary/role.md) management UI | Admin page for changing member [role](docs/glossary/role.md)s. Drag-down with confirmation |

### Security

| Feature | Detail |
|---------|--------|
| [OIDC](docs/glossary/oidc.md) security | [state](docs/glossary/state.md) ([CSRF](docs/glossary/csrf.md)) + [nonce](docs/glossary/nonce.md) ([replay attack](docs/glossary/replay-attack.md) prevention) + [PKCE](docs/glossary/pkce.md) (S256) |
| [JWT](docs/glossary/jwt.md) security | [RS256](docs/glossary/rs256.md) only. [HS256](docs/glossary/hs256.md)/none rejected. alg [whitelist](docs/glossary/whitelist.md) enforced |
| Key [encryption at rest](docs/glossary/encryption-at-rest.md) | Private keys AES-256-GCM [encrypted](docs/glossary/encryption.md) at rest in DB |
| CSRF protection | [Token](docs/glossary/token.md)-based for [HTML](docs/glossary/html.md) forms. [JSON](docs/glossary/json.md) [API](docs/glossary/api.md) exempt via [SameSite](docs/glossary/samesite.md) + [Content Negotiation](docs/glossary/content-type.md) |
| [Rate limiting](docs/glossary/rate-limiting.md) | Per-IP for [login](docs/glossary/login.md) (10/min). Per-user for [API](docs/glossary/api.md) (200/min) |
| [Session fixation](docs/glossary/session-fixation.md) | [Session](docs/glossary/session.md) ID [regenerate](docs/glossary/regenerate.md)d on every [login](docs/glossary/login.md) |
| [Content negotiation](docs/glossary/content-negotiation.md) | [JSON](docs/glossary/json.md) requests never get 302 [redirect](docs/glossary/redirect.md)s. Prevents [SPA](docs/glossary/spa.md) fetch confusion |
| [Audit log](docs/glossary/audit-log.md)ging | Every auth event: login, [logout](docs/glossary/logout.md), [role](docs/glossary/role.md) change, [invitation](docs/glossary/invitation-flow.md), [session](docs/glossary/session.md) [revoke](docs/glossary/revoke.md) |
| [Cache-Control](docs/glossary/cache-control.md) | `no-store, private` on all auth [endpoint](docs/glossary/endpoint.md)s. Prevents back-button data leaks |

### Developer Experience

| Feature | Detail |
|---------|--------|
| [ForwardAuth](docs/glossary/forwardauth.md) | [Apps](docs/glossary/downstream-app.md) get identity via [HTTP](docs/glossary/http.md) [headers](docs/glossary/header.md). Zero auth code needed [📊](dge/specs/ui-flow.md#flow-2-returning-user---session-valid) |
| [Internal API](docs/glossary/internal-api.md) | REST [API](docs/glossary/api.md) for user/[tenant](docs/glossary/tenant.md)/member CRUD [delegation](docs/glossary/delegation.md) |
| volta-[sdk](docs/glossary/sdk.md)-js | [Browser](docs/glossary/browser.md) [SDK](docs/glossary/sdk.md) (~150 lines). Auto 401 refresh, [tenant](docs/glossary/tenant.md) switch, [logout](docs/glossary/logout.md) |
| volta-[sdk](docs/glossary/sdk.md) ([Java](docs/glossary/java.md)) | [Javalin](docs/glossary/javalin.md) [middleware](docs/glossary/middleware.md) for [JWT](docs/glossary/jwt.md) [verification](docs/glossary/verification.md) |
| Dev mode | `POST /dev/token` generates test [JWTs](docs/glossary/jwt.md) for local development |
| [Health check](docs/glossary/health-check.md) | `GET /healthz` for monitoring |
| Fast [startup](docs/glossary/startup.md) | ~200ms. Local dev cycle is instant |
| Minimal deps | Gateway + [Postgres](docs/glossary/database.md). That's it |

### Admin UI

| Feature | Detail |
|---------|--------|
| Member management | List, [role](docs/glossary/role.md) change, remove. Per-[tenant](docs/glossary/tenant.md) |
| [Invitation](docs/glossary/invitation-flow.md) management | Create, list, can[cel](docs/glossary/cel.md). Copy link, QR code [📊](dge/specs/ui-flow.md#flow-7-invitation-management---admin) |
| [Session](docs/glossary/session.md) management | User can view all active [sessions](docs/glossary/session.md), [revoke](docs/glossary/revoke.md) individually or all [📊](dge/specs/ui-flow.md#flow-9-session-management---user) |

***

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

### Option 2: [Docker Compose](docs/glossary/docker-compose.md) (Full Stack)

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

**What happens at [runtime](docs/glossary/runtime.md)** (once configured):

```
  Browser          Traefik         volta-auth-proxy        Google
     │                │                   │                   │
     │  GET /dashboard│                   │                   │
     │───────────────►│                   │                   │
     │                │  GET /auth/verify │                   │
     │                │──────────────────►│                   │
     │                │   401 (no session)│                   │
     │                │◄──────────────────│                   │
     │  302 → /login  │                   │                   │
     │◄───────────────│                   │                   │
     │                                    │                   │
     │  GET /login                        │                   │
     │───────────────────────────────────►│                   │
     │  302 → accounts.google.com         │                   │
     │  (with state + nonce + PKCE)       │                   │
     │◄───────────────────────────────────│                   │
     │                                                        │
     │  GET /callback?code=...&state=...  │  (Google redirects back)
     │───────────────────────────────────►│                   │
     │                                    │  POST /token      │
     │                                    │──────────────────►│
     │                                    │  id_token + access│
     │                                    │◄──────────────────│
     │                                    │  verify nonce,    │
     │                                    │  create session,  │
     │                                    │  issue JWT        │
     │  302 → /dashboard (session cookie) │                   │
     │◄───────────────────────────────────│                   │
     │                                    │                   │
     │  GET /dashboard│                   │                   │
     │───────────────►│                   │                   │
     │                │  GET /auth/verify │                   │
     │                │──────────────────►│                   │
     │                │  200 + X-Volta-*  │                   │
     │                │◄──────────────────│                   │
     │  200 + page    │                   │                   │
     │◄───────────────│                   │                   │
```

**One-time setup in [Google Cloud Console](docs/glossary/google-cloud-console.md):**

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create or select a project
3. Navigate to [API](docs/glossary/api.md)s & Services > [Credentials](docs/glossary/credentials.md)
4. Create [OAuth 2.0](docs/glossary/oauth2.md) [Client](docs/glossary/client.md) ID (Web application)
5. Add authorized [redirect](docs/glossary/redirect.md) [URI](docs/glossary/url.md): `http://localhost:7070/callback`
6. Copy [Client](docs/glossary/client.md) ID and [Client](docs/glossary/client.md) Secret to [`.env`](docs/glossary/environment-variable.md)

### Prerequisites

| Requirement | Version | Notes |
|------------|---------|-------|
| [Java](docs/glossary/java.md) | 21+ | [LTS](docs/glossary/lts.md) recommended |
| [Maven](docs/glossary/maven.md) | 3.9+ | [Build](docs/glossary/build.md) tool |
| [Postgres](docs/glossary/database.md) | 16+ | Via [Docker](docs/glossary/docker.md) or local install |
| [Docker](docs/glossary/docker.md) | 24+ | For [Postgres](docs/glossary/database.md) (optional if you have local Postgres) |

***

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

Open `http://localhost:7070/admin/invitations` and create an [invitation](docs/glossary/invitation-flow.md).
Copy the link and share via [Sla](docs/glossary/sla.md)ck/email.

#### 3. Register Apps

Edit `volta-config.yaml`:

```yaml
apps:
  - id: my-wiki
    url: https://wiki.mycompany.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
```

Configure [Traefik](docs/glossary/reverse-proxy.md) to use [ForwardAuth](docs/glossary/forwardauth.md) [middleware](docs/glossary/middleware.md) (see [Architecture](docs/glossary/architecture.md) section).

### As an [App](docs/glossary/downstream-app.md) Developer

#### Minimal Integration ([Headers](docs/glossary/header.md) Only)

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

#### Full Integration ([JWT](docs/glossary/jwt.md) Verification + [SDK](docs/glossary/sdk.md))

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

***

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

### How Requests Flow [📊 full screen transition map](dge/specs/ui-flow.md#full-screen-transition-map)

There are 3 types of traffic:

#### Type 1: Browser -> [App](docs/glossary/downstream-app.md) (normal page/[API](docs/glossary/api.md) access)

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

**Key point:** volta-auth-proxy never sees the [request body](docs/glossary/request-body.md). [Traefik](docs/glossary/reverse-proxy.md) only asks "is this user [authenticated](docs/glossary/authentication-vs-authorization.md)?" and gets [headers](docs/glossary/header.md) back. The actual request goes directly from [Traefik](docs/glossary/traefik.md) to the [App](docs/glossary/downstream-app.md). (→ [see ForwardAuth flow diagram](dge/specs/ui-flow.md#flow-2-returning-user---session-valid))

#### Type 2: [App](docs/glossary/downstream-app.md) -> volta-auth-proxy (CRUD delegation)

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

[Apps](docs/glossary/downstream-app.md) delegate user/[tenant](docs/glossary/tenant.md)/member operations to the proxy. Apps never access the users/[tenant](docs/glossary/tenant.md)s DB directly.

#### Type 3: Browser -> volta-auth-proxy (auth UI)

```
Browser ─── GET /login ──────────────────► volta-auth-proxy
Browser ─── GET /invite/{code} ──────────► volta-auth-proxy
Browser ─── GET /admin/members ──────────► volta-auth-proxy
Browser ─── GET /settings/sessions ──────► volta-auth-proxy
```

All auth-related UI ([login](docs/glossary/login.md), invite, admin, [sessions](docs/glossary/session.md)) is served directly by volta-auth-proxy using [jte](docs/glossary/jte.md) [template](docs/glossary/template.md)s.

### What [Apps](docs/glossary/downstream-app.md) Need To Do

[Apps](docs/glossary/downstream-app.md) have exactly 2 responsibilities:

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

[Apps](docs/glossary/downstream-app.md) do NOT:

- Handle [login](docs/glossary/login.md)/[logout](docs/glossary/logout.md)
- Manage users or [tenants](docs/glossary/tenant.md)
- Issue or verify [JWTs](docs/glossary/jwt.md) (unless they want extra security)
- Store auth [state](docs/glossary/state.md)
- Talk to Google [OIDC](docs/glossary/oidc.md)

### Connecting a New [App](docs/glossary/downstream-app.md)

```
  What you configure              What you get at runtime
  ══════════════════              ═══════════════════════

  volta-config.yaml               Browser
  ┌──────────────────┐              │
  │ apps:            │              ▼
  │  - id: my-app    │           Traefik
  │    url: http://  │     ┌────────┴────────────────────┐
  │    allowed_roles │     │  ForwardAuth middleware      │
  └──────────────────┘     │  → GET /auth/verify         │
           │               └────────────┬────────────────┘
           ▼                            │ 200 OK
  Traefik auto-config              X-Volta-User-Id
  (generated by volta)             X-Volta-Tenant-Id    ──► Your App
                                   X-Volta-Roles             reads
  src/main/resources/              X-Volta-JWT               headers
  public/js/volta.js
  ┌──────────────────┐
  │ Volta.fetch()    │◄── Your frontend uses this
  │ Volta.logout()   │    instead of fetch()
  └──────────────────┘
```

**Step 1:** Register in `volta-config.yaml`

```yaml
apps:
  - id: my-new-app
    url: https://my-new-app.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
```

**Step 2:** Add [Traefik](docs/glossary/reverse-proxy.md) route with [ForwardAuth](docs/glossary/forwardauth.md) [middleware](docs/glossary/middleware.md)

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

**Step 3:** Read [headers](docs/glossary/header.md) in your [App](docs/glossary/downstream-app.md)

```java
// Javalin example
app.get("/api/data", ctx -> {
    String userId = ctx.header("X-Volta-User-Id");
    String tenantId = ctx.header("X-Volta-Tenant-Id");
    String roles = ctx.header("X-Volta-Roles");
    // ... your business logic
});
```

Or use volta-[sdk](docs/glossary/sdk.md) for [JWT](docs/glossary/jwt.md) [verification](docs/glossary/verification.md):

```java
VoltaAuth volta = VoltaAuth.builder()
    .jwksUrl("http://volta-auth-proxy:7070/.well-known/jwks.json")
    .expectedIssuer("volta-auth")
    .expectedAudience("volta-apps")
    .build();

app.before("/api/*", volta.middleware());
```

**Step 4:** Add volta-[sdk](docs/glossary/sdk.md)-js to your frontend

```html
<script src="http://volta-auth-proxy:7070/js/volta.js"></script>
<script>
  Volta.init({ gatewayUrl: "http://volta-auth-proxy:7070" });
  // All fetch calls now auto-handle 401 -> refresh -> retry
  const res = await Volta.fetch("/api/data");
</script>
```

**That's it.** Your [App](docs/glossary/downstream-app.md) now has [multi-tenant](docs/glossary/multi-tenant.md) auth with zero auth code.

### [Docker Compose](docs/glossary/docker-compose.md) Example (Full Stack)

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

***

## Design Philosophy

- **Control is king** -- Minimize [external server](docs/glossary/external-vs-internal.md) [dependencies](docs/glossary/external-dependency.md)
- **Choose the hell you understand** -- Both [Keycloak](docs/glossary/keycloak.md) [config hell](docs/glossary/config-hell.md) and DIY auth hell are hell. At least with DIY you can read the [stack trace](docs/glossary/stack-trace.md). Auth stays in-house. Never trust a system you don't understand with your users' [authentication](docs/glossary/authentication-vs-authorization.md)
- **Tight coupling, no apologies** -- [Single process](docs/glossary/single-process.md). [Microservice](docs/glossary/microservice.md)-style loose coupling brings [configuration](docs/glossary/complexity-of-configuration.md) and [network](docs/glossary/network.md) complexity, not correctness. Auth is [latency](docs/glossary/latency.md)-sensitive and failure-pr[opa](docs/glossary/opa.md)gating. Fewer [network hop](docs/glossary/network-hop.md)s, debug in one place
- **[ForwardAuth](docs/glossary/forwardauth.md) pattern** -- Proxy never relays request bodies. Auth check only [📊](dge/specs/ui-flow.md#flow-2-returning-user---session-valid)
- **[Apps](docs/glossary/downstream-app.md) do only 2 things** -- Read [headers](docs/glossary/header.md) or call [APIs](docs/glossary/api.md)
- **[Phase](docs/glossary/phase-based-development.md)-minimal** -- [Build](docs/glossary/build.md) only what's needed now. Leave [Interface](docs/glossary/interface-extension-point.md) extension points for later. Never leak [App](docs/glossary/downstream-app.md)-specific logic into the proxy

***

## What It Does

| Capability | Description |
|-----------|-------------|
| **Google [OIDC](docs/glossary/oidc.md)** | Direct [integration](docs/glossary/integration.md). No intermediate [IdP](docs/glossary/idp.md). [PKCE](docs/glossary/pkce.md) + [state](docs/glossary/state.md) + [nonce](docs/glossary/nonce.md) |
| **[JWT](docs/glossary/jwt.md) Issuance** | [RS256](docs/glossary/rs256.md) [self-signed](docs/glossary/self-signed.md). 5-min expiry. [JWKS](docs/glossary/jwks.md) [endpoint](docs/glossary/endpoint.md) at `/.well-known/jwks.json` |
| **[Session](docs/glossary/session.md)** | Signed [cookie](docs/glossary/cookie.md) (`__volta_session`). 8h [sliding](docs/glossary/sliding-window-expiry.md). Max 5 concurrent |
| **[Tenant](docs/glossary/tenant.md) Resolution** | [Cookie](docs/glossary/cookie.md)/[JWT](docs/glossary/jwt.md) > [subdomain](docs/glossary/subdomain.md) > email [domain](docs/glossary/domain.md) > invite code > manual selection [📊](dge/specs/ui-flow.md#flow-3-tenant-selection) |
| **[Role](docs/glossary/role.md) [Hierarchy](docs/glossary/hierarchy.md)** | OWNER > ADMIN > MEMBER > VIEWER |
| **[Invitations](docs/glossary/invitation-flow.md)** | [Crypto-random](docs/glossary/crypto-random.md) codes. Expiry. Usage limits. [Consent screen](docs/glossary/consent-screen.md) [📊](dge/specs/ui-flow.md#flow-1-invite-link---first-login) |
| **[ForwardAuth](docs/glossary/forwardauth.md)** | `GET /auth/verify` returns `X-Volta-*` [headers](docs/glossary/header.md) to [Traefik](docs/glossary/reverse-proxy.md) [📊](dge/specs/ui-flow.md#flow-2-returning-user---session-valid) |
| **[Internal API](docs/glossary/internal-api.md)** | `/api/v1/*` for [apps](docs/glossary/downstream-app.md) to delegate user/[tenant](docs/glossary/tenant.md)/member CRUD |
| **[Audit Log](docs/glossary/audit-log.md)** | All auth events logged to `audit_logs` table |
| **[CSRF](docs/glossary/csrf.md)** | [Token](docs/glossary/token.md)-based for [HTML](docs/glossary/html.md) forms. [JSON](docs/glossary/json.md) [API](docs/glossary/api.md) exempt ([SameSite](docs/glossary/samesite.md) + Content-Type) |
| **[Rate Limiting](docs/glossary/rate-limiting.md)** | [Caffeine](docs/glossary/caffeine-cache.md)-based. Per-IP for [login](docs/glossary/login.md), per-user for [API](docs/glossary/api.md) |
| **Dev Mode** | `POST /dev/token` for local development (disabled in [production](docs/glossary/production.md)) |

***

## Tech Stack

| Component | Choice | Why |
|-----------|--------|-----|
| Language | [Java](docs/glossary/java.md) 21 | [LTS](docs/glossary/lts.md), mature ecosystem |
| [Build](docs/glossary/build.md) | **[Maven](docs/glossary/maven.md)** | Stable across [Java](docs/glossary/java.md) version upgrades (not Gradle) |
| Web | [Javalin](docs/glossary/javalin.md) 6.x | Lightweight, ~200ms [startup](docs/glossary/startup.md) |
| [Template](docs/glossary/template.md) | [jte](docs/glossary/jte.md) 3.x | [Type-safe](docs/glossary/type-safe.md), [compile](docs/glossary/compile.md)-time checked |
| [JWT](docs/glossary/jwt.md) | nimbus-jose-[jwt](docs/glossary/jwt.md) | [Java](docs/glossary/java.md) JOSE/[JWT](docs/glossary/jwt.md) de facto standard |
| DB | [Postgres](docs/glossary/database.md) 16 | Reliable, JSONB for [audit log](docs/glossary/audit-log.md)s |
| [Migration](docs/glossary/migration.md) | [Flyway](docs/glossary/flyway.md) | Auto-runs on [startup](docs/glossary/startup.md) |
| Pool | [HikariCP](docs/glossary/hikaricp.md) | Fast [connection pool](docs/glossary/connection-pool.md) |
| Cache | [Caffeine](docs/glossary/caffeine-cache.md) | [In-memory](docs/glossary/in-memory.md), for [rate limiting](docs/glossary/rate-limiting.md) + [session](docs/glossary/session.md) cache |
| [CSS](docs/glossary/css.md) | Single file `volta.css` | Mobile-first, [responsive](docs/glossary/responsive.md) |
| [JS](docs/glossary/javascript.md) | `volta.js` (~150 lines) | Vanilla [JavaScript](docs/glossary/javascript.md). No [framework](docs/glossary/framework.md) |

***

### Prerequisites

- [Java](docs/glossary/java.md) 21+
- [Maven](docs/glossary/maven.md) 3.9+
- [Docker](docs/glossary/docker.md) (for [Postgres](docs/glossary/database.md))
- [Google Cloud Console](docs/glossary/google-cloud-console.md) project with [OAuth 2.0](docs/glossary/oauth2.md) [credentials](docs/glossary/credentials.md)

***

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

***

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
| `users` | User accounts (email, display\_name, google\_sub) |
| `tenants` | Work[spa](docs/glossary/spa.md)ces (name, slug, email\_[domain](docs/glossary/domain.md), plan) |
| `tenant_domains` | Multiple [domain](docs/glossary/domain.md)s per [tenant](docs/glossary/tenant.md) |
| `memberships` | User-[tenant](docs/glossary/tenant.md) relationships ([role](docs/glossary/role.md), joined\_at) |
| `sessions` | Active [sessions](docs/glossary/session.md) ([cookie](docs/glossary/cookie.md)-based, [sliding](docs/glossary/sliding-window-expiry.md) 8h) |
| `signing_keys` | [JWT](docs/glossary/jwt.md) RSA key pairs (AES-256-GCM [encrypted](docs/glossary/encryption.md)) |
| `invitations` | Invite codes (expiry, usage limits, email restriction) |
| `invitation_usages` | Track who used which [invitation](docs/glossary/invitation-flow.md) |
| `audit_logs` | All auth events (JSONB details) |

[Migration](docs/glossary/migration.md) runs automatically on [startup](docs/glossary/startup.md) via [Flyway](docs/glossary/flyway.md).

***

## API Reference

### Auth Endpoints (Browser)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/login` | Start Google [OIDC](docs/glossary/oidc.md) flow |
| GET | `/callback` | [OIDC](docs/glossary/oidc.md) callback ([interstitial](docs/glossary/interstitial.md)) |
| POST | `/auth/callback/complete` | Complete [OIDC](docs/glossary/oidc.md) [verification](docs/glossary/verification.md) |
| POST | `/auth/refresh` | Refresh [JWT](docs/glossary/jwt.md) from [session](docs/glossary/session.md) [cookie](docs/glossary/cookie.md) |
| POST | `/auth/logout` | [Logout](docs/glossary/logout.md), clear [session](docs/glossary/session.md) |
| GET | `/select-tenant` | [Tenant](docs/glossary/tenant.md) selection page |
| POST | `/auth/switch-tenant` | Switch active [tenant](docs/glossary/tenant.md) |
| GET | `/invite/{code}` | [Invitation](docs/glossary/invitation-flow.md) landing page |
| POST | `/invite/{code}/accept` | Accept [invitation](docs/glossary/invitation-flow.md) |
| GET | `/settings/sessions` | [Session](docs/glossary/session.md) management page |
| GET | `/admin/members` | Member management page |
| GET | `/admin/invitations` | [Invitation](docs/glossary/invitation-flow.md) management page |

### [ForwardAuth](docs/glossary/forwardauth.md) ([Traefik](docs/glossary/reverse-proxy.md) Integration) [📊 flow diagram](dge/specs/ui-flow.md#flow-2-returning-user---session-valid)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/auth/verify` | Verify [session](docs/glossary/session.md), return `X-Volta-*` [headers](docs/glossary/header.md) |

**Response [headers](docs/glossary/header.md) on 200:**

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

### [Internal API](docs/glossary/internal-api.md) ([App](docs/glossary/downstream-app.md) -> Proxy)

[Authentication](docs/glossary/authentication-vs-authorization.md): `Authorization: Bearer <user-jwt>` or `Authorization: Bearer volta-service:<token>` (see [Bearer scheme](docs/glossary/bearer-scheme.md))

**Users:**

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/users/me` | Any | Current user profile |
| GET | `/api/v1/users/me/tenants` | Any | User's [tenant](docs/glossary/tenant.md) list |
| PATCH | `/api/v1/users/{id}` | Self or ADMIN+ | Update display\_name |

**[Tenant](docs/glossary/tenant.md) Members:**

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/tenants/{tid}/members` | MEMBER+ | List members (paginated) |
| GET | `/api/v1/tenants/{tid}/members/{uid}` | MEMBER+ | Member detail |
| PATCH | `/api/v1/tenants/{tid}/members/{uid}` | ADMIN+ | Change [role](docs/glossary/role.md) |
| DELETE | `/api/v1/tenants/{tid}/members/{uid}` | ADMIN+ | Remove member |

**[Invitations](docs/glossary/invitation-flow.md):**

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/tenants/{tid}/invitations` | ADMIN+ | List [invitations](docs/glossary/invitation-flow.md) |
| POST | `/api/v1/tenants/{tid}/invitations` | ADMIN+ | Create [invitation](docs/glossary/invitation-flow.md) |
| DELETE | `/api/v1/tenants/{tid}/invitations/{iid}` | ADMIN+ | Can[cel](docs/glossary/cel.md) [invitation](docs/glossary/invitation-flow.md) |

**Admin (Key Management):**

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/admin/keys` | OWNER | List [signing key](docs/glossary/signing-key.md)s |
| POST | `/api/v1/admin/keys/rotate` | OWNER | Rotate [signing key](docs/glossary/signing-key.md) |
| POST | `/api/v1/admin/keys/{kid}/revoke` | OWNER | [Revoke](docs/glossary/revoke.md) signing key |

**Development:**

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/dev/token` | DEV\_MODE only | Generate test [JWT](docs/glossary/jwt.md) |
| GET | `/healthz` | None | [Health check](docs/glossary/health-check.md) |
| GET | `/.well-known/jwks.json` | None | [JWKS](docs/glossary/jwks.md) public keys |

### Pagination

All list [endpoint](docs/glossary/endpoint.md)s support `?offset=0&limit=20` (max 100).

```json
{
  "data": [...],
  "meta": { "total": 150, "limit": 20, "offset": 0, "request_id": "uuid" }
}
```

### Error Format (→ [see error recovery flow](dge/specs/ui-flow.md#error-recovery-flow))

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

| [HTTP](docs/glossary/http.md) | Code | Meaning |
|------|------|---------|
| 401 | `AUTHENTICATION_REQUIRED` | Not logged in |
| 401 | `SESSION_EXPIRED` | [Session](docs/glossary/session.md) timed out |
| 401 | `SESSION_REVOKED` | [Session](docs/glossary/session.md) was [revoke](docs/glossary/revoke.md)d |
| 403 | `FORBIDDEN` | No permission |
| 403 | `TENANT_ACCESS_DENIED` | Not a member of this [tenant](docs/glossary/tenant.md) |
| 403 | `TENANT_SUSPENDED` | [Tenant](docs/glossary/tenant.md) is suspended |
| 403 | `ROLE_INSUFFICIENT` | [Role](docs/glossary/role.md) too low for this action |
| 404 | `NOT_FOUND` | Resource not found |
| 409 | `CONFLICT` | Already exists |
| 410 | `INVITATION_EXPIRED` | [Invitation](docs/glossary/invitation-flow.md) expired (→ [see flow](dge/specs/ui-flow.md#flow-1-invite-link---first-login)) |
| 410 | `INVITATION_EXHAUSTED` | [Invitation](docs/glossary/invitation-flow.md) fully used |
| 429 | `RATE_LIMITED` | Too many requests |

***

## JWT Specification

```
Algorithm:  RS256 (RSA 2048-bit)
Expiry:     5 minutes
Key store:  signing_keys table (AES-256-GCM encrypted)
JWKS:       GET /.well-known/jwks.json
```

**[Claims](docs/glossary/jwt-payload.md):**

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

- `alg` must be [RS256](docs/glossary/rs256.md). [HS256](docs/glossary/hs256.md) and none are rejected
- `kid` required in [header](docs/glossary/header.md)
- `aud` is an array (for [Phase](docs/glossary/phase-based-development.md) 2 per-app audience)
- Email is NOT in [JWT](docs/glossary/jwt.md) [claim](docs/glossary/claim.md)s (fetch via `/api/v1/users/me` instead)

***

## Tenant Resolution [📊 flow diagram](dge/specs/ui-flow.md#flow-3-tenant-selection)

Priority order:

1. Existing [session](docs/glossary/session.md) [cookie](docs/glossary/cookie.md) with `tenant_id` -- use it
2. [URL](docs/glossary/url.md) [subdomain](docs/glossary/subdomain.md) -- lookup in `tenant_domains`
3. Email [domain](docs/glossary/domain.md) -- lookup in `tenant_domains` (free email excluded)
4. None found -- show [invitation](docs/glossary/invitation-flow.md) code prompt or [tenant](docs/glossary/tenant.md) selection

**[Free email domains](docs/glossary/free-email-domains.md) excluded:** gmail.com, outlook.com, yahoo.com, yahoo.co.jp, hotmail.com, icloud.com, protonmail.com

***

## Role Hierarchy

```
OWNER > ADMIN > MEMBER > VIEWER
```

| [Role](docs/glossary/role.md) | Permissions |
|------|------------|
| OWNER | Delete [tenant](docs/glossary/tenant.md), transfer ownership, all ADMIN permissions |
| ADMIN | Invite/remove members, change [roles](docs/glossary/role.md) (up to ADMIN), change [tenant](docs/glossary/tenant.md) settings |
| MEMBER | Normal usage |
| VIEWER | Read-only (enforced by [App](docs/glossary/downstream-app.md)) |

***

## App Registration

Define [apps](docs/glossary/downstream-app.md) in `volta-config.yaml`:

```yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]

  - id: app-admin
    url: https://admin.example.com
    allowed_roles: [ADMIN, OWNER]
```

[ForwardAuth](docs/glossary/forwardauth.md) (`/auth/verify`) enforces `allowed_roles` per [App](docs/glossary/downstream-app.md).

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

***

## volta-sdk-js

[Browser](docs/glossary/browser.md) [SDK](docs/glossary/sdk.md) (~150 lines, vanilla [JavaScript](docs/glossary/javascript.md)). Handles [session](docs/glossary/session.md) refresh ([📊](dge/specs/ui-flow.md#flow-5-session-expired---silent-refresh)), [tenant](docs/glossary/tenant.md) switching ([📊](dge/specs/ui-flow.md#flow-4-tenant-switch-during-session)), and 401 recovery.

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

**For form submission, use `Volta.fetch()` instead of [HTML](docs/glossary/html.md) form POST:**

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

This ensures [session](docs/glossary/session.md) expiry during form input is handled tran[spa](docs/glossary/spa.md)rently (auto-refresh + [retry](docs/glossary/retry.md)).

***

## volta-sdk ([Java](docs/glossary/java.md), for [Apps](docs/glossary/downstream-app.md))

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

***

## Content Negotiation

```
Accept: application/json  -->  Always JSON response (never 302)
Accept: text/html         -->  HTML or redirect
X-Requested-With: XMLHttpRequest  -->  Treated as JSON
Authorization: Bearer ...  -->  Treated as JSON
```

**[SPA](docs/glossary/spa.md) rule:** Gateway never returns 302 to [JSON](docs/glossary/json.md) requests. Always 401 JSON. This prevents `fetch()` from receiving Google [login](docs/glossary/login.md) [HTML](docs/glossary/html.md).

***

## Security

| Feature | Implementation |
|---------|---------------|
| [OIDC](docs/glossary/oidc.md) | [state](docs/glossary/state.md) ([CSRF](docs/glossary/csrf.md)) + [nonce](docs/glossary/nonce.md) (replay) + [PKCE](docs/glossary/pkce.md) (S256) |
| [JWT](docs/glossary/jwt.md) signing | [RS256](docs/glossary/rs256.md). [HS256](docs/glossary/hs256.md)/none rejected. alg [whitelist](docs/glossary/whitelist.md) |
| Key storage | AES-256-GCM [encrypted in DB](docs/glossary/encryption-at-rest.md) |
| [Key rotation](docs/glossary/key-rotation.md) | `POST /api/v1/admin/keys/rotate` (OWNER) |
| [Session](docs/glossary/session.md) | HMAC-SHA256 signed [cookie](docs/glossary/cookie.md). [HttpOnly](docs/glossary/httponly.md), Secure, [SameSite](docs/glossary/samesite.md)=Lax |
| [Session fixation](docs/glossary/session-fixation.md) | [Session](docs/glossary/session.md) ID [regenerate](docs/glossary/regenerate.md)d on [login](docs/glossary/login.md) |
| [CSRF](docs/glossary/csrf.md) | [Token](docs/glossary/token.md)-based for [HTML](docs/glossary/html.md) forms. [JSON](docs/glossary/json.md) [API](docs/glossary/api.md) exempt |
| [Rate limiting](docs/glossary/rate-limiting.md) | Per-IP for [login](docs/glossary/login.md) (10/min), per-user for [API](docs/glossary/api.md) (200/min) |
| [Tenant](docs/glossary/tenant.md) isolation | Path [tenant](docs/glossary/tenant.md)Id must match [JWT](docs/glossary/jwt.md) volta\_tid |
| OWNER protection | Last OWNER cannot be demoted |
| Audit | All auth events logged with actor, IP, request\_id |
| [Cache-Control](docs/glossary/cache-control.md) | `no-store, private` on auth [endpoint](docs/glossary/endpoint.md)s |

***

## [Environment Variables](docs/glossary/environment-variable.md)

| [Variable](docs/glossary/variable.md) | Default | Description |
|----------|---------|-------------|
| `PORT` | 7070 | [Server](docs/glossary/server.md) [port](docs/glossary/port.md) |
| `DB_HOST` | [localhost](docs/glossary/localhost.md) | [Postgres](docs/glossary/database.md) host |
| `DB_PORT` | 54329 | [Postgres](docs/glossary/database.md) [port](docs/glossary/port.md) |
| `DB_NAME` | volta\_auth | [Database](docs/glossary/database.md) name |
| `DB_USER` | volta | [Database](docs/glossary/database.md) user |
| `DB_PASSWORD` | volta | Database password |
| `BASE_URL` | [http](docs/glossary/http.md)://[localhost](docs/glossary/localhost.md):7070 | Public [URL](docs/glossary/url.md) |
| `GOOGLE_CLIENT_ID` | | Google [OAuth](docs/glossary/oauth2.md) [client](docs/glossary/client.md) ID |
| `GOOGLE_CLIENT_SECRET` | | Google [OAuth](docs/glossary/oauth2.md) [client](docs/glossary/client.md) secret |
| `GOOGLE_REDIRECT_URI` | [http](docs/glossary/http.md)://[localhost](docs/glossary/localhost.md):7070/callback | [Open redirect](docs/glossary/open-redirect.md)-safe [OAuth](docs/glossary/oauth2.md) [redirect](docs/glossary/redirect.md) [URI](docs/glossary/url.md) |
| `JWT_ISSUER` | volta-auth | [JWT](docs/glossary/jwt.md) issuer [claim](docs/glossary/claim.md) |
| `JWT_AUDIENCE` | volta-apps | [JWT](docs/glossary/jwt.md) audience [claim](docs/glossary/claim.md) |
| `JWT_TTL_SECONDS` | 300 | [JWT](docs/glossary/jwt.md) expiry (5 min) |
| `JWT_KEY_ENCRYPTION_SECRET` | | AES-256 key for [encrypting](docs/glossary/encryption.md) [signing key](docs/glossary/signing-key.md)s |
| `SESSION_TTL_SECONDS` | 28800 | [Session](docs/glossary/session.md) expiry (8 hours) |
| `ALLOWED_REDIRECT_DOMAINS` | localhost,127.0.0.1 | [Whitelist](docs/glossary/whitelist.md) for return\_to ([open redirect](docs/glossary/open-redirect.md) prevention) |
| `VOLTA_SERVICE_TOKEN` | | Static [service token](docs/glossary/service-token.md) for [M2M](docs/glossary/m2m.md) ([Phase](docs/glossary/phase-based-development.md) 1) |
| `DEV_MODE` | false | Enable /dev/[token](docs/glossary/token.md) [endpoint](docs/glossary/endpoint.md) |
| `APP_CONFIG_PATH` | volta-config.[yaml](docs/glossary/yaml.md) | Path to [App](docs/glossary/downstream-app.md) registry |
| `SUPPORT_CONTACT` | | Admin contact shown on error pages |

***

## [Phase](docs/glossary/phase-based-development.md) Roadmap

| [Phase](docs/glossary/phase-based-development.md) | Status | What |
|-------|--------|------|
| **[Phase](docs/glossary/phase-based-development.md) 1: Core** | **Implemented** | Google [OIDC](docs/glossary/oidc.md), [tenants](docs/glossary/tenant.md), [roles](docs/glossary/role.md), [invitations](docs/glossary/invitation-flow.md), [ForwardAuth](docs/glossary/forwardauth.md), [Internal API](docs/glossary/internal-api.md) |
| **[Phase](docs/glossary/phase-based-development.md) 2: Scale** | **Implemented** | Multiple [IdPs](docs/glossary/idp.md) ([Git](docs/glossary/git.md)Hub, Microsoft), [M2M](docs/glossary/m2m.md) ([Client Credentials](docs/glossary/client-credentials.md)), [Redis](docs/glossary/redis.md) [sessions](docs/glossary/session.md), [Webhook](docs/glossary/webhook.md)s |
| **[Phase](docs/glossary/phase-based-development.md) 3: Enterprise** | **Partial** | [SAML](docs/glossary/sso.md) ✅, email notifications ✅, **[MFA](docs/glossary/mfa.md)/2FA ([TOTP](docs/glossary/totp.md))** ✅ — i18n ❌, **Conditional access** ❌, **Fraud alert** ❌ |
| **[Phase](docs/glossary/phase-based-development.md) 4: Platform** | **Partial** | SCIM ✅, [Billing](docs/glossary/billing.md) ([Stripe](docs/glossary/stripe.md) [webhook](docs/glossary/webhook.md)) ✅ — [Policy Engine](docs/glossary/policy-engine.md) ❌, GDPR export/deletion ❌, **Device trust** ❌, **Mobile [SDK](docs/glossary/sdk.md)** ❌ |

### Auth Trend Roadmap

| Trend | [Phase](docs/glossary/phase-based-development.md) | Approach |
|-------|-------|----------|
| **Passkeys ([WebAuthn](docs/glossary/webauthn.md)/FIDO2)** | [Phase](docs/glossary/phase-based-development.md) 2 | Passwordless auth mainstream. Add as 2nd auth method alongside Google |
| **[MFA](docs/glossary/mfa.md)/2FA ([TOTP](docs/glossary/totp.md))** | [Phase](docs/glossary/phase-based-development.md) 3 | Google Authenticator etc. [Tenant](docs/glossary/tenant.md) admins can enforce "[MFA](docs/glossary/mfa.md) required" |
| **Risk-based auth** | [Phase](docs/glossary/phase-based-development.md) 3 | Extra [verification](docs/glossary/verification.md) on new device/IP. `amr` [claim](docs/glossary/claim.md) reflects auth strength in [JWT](docs/glossary/jwt.md) |
| **Fraud detection/alerting** | [Phase](docs/glossary/phase-based-development.md) 3 | Suspicious [login](docs/glossary/login.md) detection (impossible travel, [credential stuffing](docs/glossary/credential-stuffing.md)). [Webhook](docs/glossary/webhook.md) alerts to admin. [Integration](docs/glossary/integration.md) with threat intelligence feeds |
| **Device trust** | [Phase](docs/glossary/phase-based-development.md) 4 | Remember known devices. Challenge unknown devices |
| **Mobile [SDK](docs/glossary/sdk.md)** | [Phase](docs/glossary/phase-based-development.md) 4 | Native iOS/Android [SDK](docs/glossary/sdk.md). Deep link support for invite flows. Biometric auth [integration](docs/glossary/integration.md) |
| **[SAML](docs/glossary/sso.md) [SSO](docs/glossary/sso.md)** | [Phase](docs/glossary/phase-based-development.md) 3 | Enterprise customer [IdP](docs/glossary/idp.md) [integration](docs/glossary/integration.md) ([Active Directory](docs/glossary/active-directory.md) etc.) |
| **SCIM** | [Phase](docs/glossary/phase-based-development.md) 4 | Automated user [provisioning](docs/glossary/provisioning.md) from [Okta](docs/glossary/okta.md), Azure AD etc. |

Full specification: [`dge/specs/implementation-all-phases.md`](dge/specs/implementation-all-phases.md)

***

## Design Documentation

This project was designed using DGE (Dialogue-driven Gap Extraction) -- 106 design gaps identified and resolved across 8 [session](docs/glossary/session.md)s.

| Document | Description |
|----------|-------------|
| [`dge/specs/implementation-all-phases.md`](dge/specs/implementation-all-phases.md) | Full implementation spec (all phases) |
| [`dge/specs/ux-specs-phase1.md`](dge/specs/ux-specs-phase1.md) | UI/UX specifications |
| [`dge/specs/ui-flow.md`](dge/specs/ui-flow.md) | Screen [transition](docs/glossary/transition.md) flows ([mermaid](docs/glossary/mermaid.md) diagrams) -- [📊 user states](dge/specs/ui-flow.md#user-state-model), [ForwardAuth](dge/specs/ui-flow.md#flow-2-returning-user---session-valid), [invite](dge/specs/ui-flow.md#flow-1-invite-link---first-login), [tenant](dge/specs/ui-flow.md#flow-3-tenant-selection), [logout](dge/specs/ui-flow.md#flow-6-logout), [errors](dge/specs/ui-flow.md#error-recovery-flow) |
| [`docs/getting-started-dialogue.md`](docs/getting-started-dialogue.md) | **Getting Started: a conversation between volta engineer and app developer** |
| [`docs/llm-integration-guide.md`](docs/llm-integration-guide.md) | **For AI assistants: how to help engineers integrate volta** |
| [`docs/no-traefik-guide.md`](docs/no-traefik-guide.md) | Running without [Traefik](docs/glossary/traefik.md): 3 patterns (no proxy, [nginx](docs/glossary/nginx.md), [Caddy](docs/glossary/caddy.md)) |
| [`docs/target-audience.md`](docs/target-audience.md) | Target audience, market position, revenue op[port](docs/glossary/port.md)unities |
| [`docs/dsl-overview.md`](docs/dsl-overview.md) | [DSL](docs/glossary/dsl.md) specification, [state machine](docs/glossary/state-machine.md), [policy engine](docs/glossary/policy-engine.md) driver strategy |
| [`docs/dsl-validator-spec.md`](docs/dsl-validator-spec.md) | [DSL](docs/glossary/dsl.md) validator (60+ checks) |
| [`dge/feedback/2026-03-31-volta-auth-proxy.md`](dge/feedback/2026-03-31-volta-auth-proxy.md) | DGE method feedback |
| [`tasks/001-fix-critical-bugs-and-implement-templates.md`](tasks/001-fix-critical-bugs-and-implement-templates.md) | Current implementation task |
| [`backlog/001-form-state-recovery.md`](backlog/001-form-state-recovery.md) | [Phase](docs/glossary/phase-based-development.md) 2: Form auto-save on [session](docs/glossary/session.md) expiry |

***

## License

TBD
