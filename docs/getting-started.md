# Getting Started

[日本語版 / Japanese](getting-started-ja.md)

Set up volta-auth-proxy locally in **5 minutes**, then wire it to your IdP of choice.

> Looking for the conversational walkthrough? → [getting-started-dialogue.md](getting-started-dialogue.md)
> Looking for the architectural internals? → [architecture.md](architecture.md)

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [5-minute local quickstart](#5-minute-local-quickstart)
- [Configuration](#configuration)
  - [Environment variables](#environment-variables)
  - [`volta-config.yaml`](#volta-configyaml)
- [ForwardAuth wiring](#forwardauth-wiring)
  - [Option A: Traefik](#option-a-traefik)
  - [Option B: volta-gateway (built-in)](#option-b-volta-gateway-built-in)
  - [Option C: Nginx `auth_request`](#option-c-nginx-auth_request)
- [IdP setup examples](#idp-setup-examples)
  - [Google (OIDC)](#google-oidc)
  - [Microsoft Entra (OIDC)](#microsoft-entra-oidc)
  - [Okta (SAML)](#okta-saml)
  - [Generic SAML IdP](#generic-saml-idp)
- [Enabling Passkey](#enabling-passkey)
- [Enabling MFA (TOTP)](#enabling-mfa-totp)
- [Verifying the install](#verifying-the-install)
- [Next steps](#next-steps)

---

## Prerequisites

| Tool | Minimum | Why |
|------|---------|-----|
| Java | 21 LTS | Javalin + tramli runtime |
| Maven | 3.9 | Build |
| Docker / Compose | 24.x | Postgres for local dev |
| `jq`, `curl` | any | Troubleshooting |

---

## 5-minute local quickstart

```bash
git clone https://github.com/opaopa6969/volta-auth-proxy
cd volta-auth-proxy

# 1. Start Postgres
docker-compose up -d

# 2. Minimal env (Google OIDC for the first run)
cat > .env <<'EOF'
BASE_URL=http://localhost:7070
DATABASE_URL=jdbc:postgresql://localhost:5432/volta
DATABASE_USER=volta
DATABASE_PASSWORD=volta
SESSION_COOKIE_NAME=__volta_session
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
DEV_MODE=true
EOF

# 3. Build + run
mvn -q -DskipTests package
java -jar target/volta-auth-proxy-*-shaded.jar

# 4. Open the login page
open http://localhost:7070/login
```

On success you will see the login UI, pick "Login with Google", complete the
OAuth dance, and land back on `/` with a `__volta_session` cookie set.

---

## Configuration

### Environment variables

The full list is in `.env.example`. Essentials:

| Variable | Purpose | Default |
|----------|---------|---------|
| `BASE_URL` | Used for `Secure` cookie inference + redirect URIs | — (required) |
| `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD` | Postgres | — (required) |
| `SESSION_COOKIE_NAME` | Cookie name | `__volta_session` |
| `SESSION_TTL_MINUTES` | Idle timeout | `60` |
| `SESSION_ABSOLUTE_TTL_HOURS` | Hard cap | `24` |
| `MFA_ENABLED` | Feature flag | `false` |
| `PASSKEY_ENABLED` | Feature flag | `false` |
| `LOCAL_BYPASS_CIDRS` | ADR-003 LAN bypass (comma-separated CIDRs, `""` = disabled) | `192.168.0.0/16,10.0.0.0/8,172.16.0.0/12,100.64.0.0/10,127.0.0.1/32` |
| `DEV_MODE` | Enables localhost-only dev conveniences | `false` |
| `WEBHOOK_ENABLED` | Outbox worker | `false` |
| `BASE_URL` scheme (http/https) | Drives `Secure` cookie | inferred |

### `volta-config.yaml`

Tenancy / access / binding layers (schema v3). Start from `volta-config.example.yaml`:

```yaml
tenancy:
  mode: multi            # single | multi
  resolver: subdomain    # subdomain | header | path
access:
  default_role: MEMBER
  admin_emails:
    - admin@example.com
binding:
  apps:
    - host: console.example.com
      auth: required
      headers: [X-Volta-User-Id, X-Volta-Tenant-Id, X-Volta-Role]
    - host: public.example.com
      auth: anonymous
```

---

## ForwardAuth wiring

### Option A: Traefik

```yaml
# docker-compose.yml (excerpt)
services:
  traefik:
    image: traefik:v3
    command:
      - --providers.docker=true
      - --entrypoints.web.address=:80
    labels:
      - traefik.http.middlewares.volta.forwardauth.address=http://volta:7070/auth/verify
      - traefik.http.middlewares.volta.forwardauth.authResponseHeaders=X-Volta-User-Id,X-Volta-Tenant-Id,X-Volta-Role,X-Volta-Email

  app:
    image: your/app
    labels:
      - traefik.http.routers.app.rule=Host(`console.example.com`)
      - traefik.http.routers.app.middlewares=volta
```

### Option B: volta-gateway (built-in)

[volta-gateway](https://github.com/opaopa6969/volta-gateway) bundles a Rust reverse
proxy with a volta-auth-proxy-compatible auth server. Point `upstream_url` at your
app and skip Traefik entirely.

### Option C: Nginx `auth_request`

```nginx
location = /auth/verify {
    internal;
    proxy_pass http://volta:7070/auth/verify;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Uri $request_uri;
    proxy_set_header X-Forwarded-Proto $scheme;
}
location / {
    auth_request /auth/verify;
    auth_request_set $user_id  $upstream_http_x_volta_user_id;
    auth_request_set $tenant_id $upstream_http_x_volta_tenant_id;
    proxy_set_header X-Volta-User-Id  $user_id;
    proxy_set_header X-Volta-Tenant-Id $tenant_id;
    proxy_pass http://app:8080;
    error_page 401 = @login;
}
location @login { return 302 /login?return_to=$scheme://$host$request_uri; }
```

See `afb6eab` for the nginx routing fix that handles `/auth/*` routes correctly.

---

## IdP setup examples

### Google (OIDC)

1. <https://console.cloud.google.com/> → APIs & Services → Credentials
2. Create **OAuth 2.0 Client ID** → Web application
3. Authorized redirect URI: `https://auth.example.com/auth/callback`
4. Save `Client ID` + `Client secret` to `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`

### Microsoft Entra (OIDC)

1. Entra portal → App registrations → New registration
2. Redirect URI (Web): `https://auth.example.com/auth/callback`
3. Certificates & secrets → New client secret
4. Environment: `MICROSOFT_CLIENT_ID`, `MICROSOFT_CLIENT_SECRET`, `MICROSOFT_TENANT_ID`

### Okta (SAML)

1. Okta admin → Applications → Create App Integration → SAML 2.0
2. **Single sign-on URL (ACS)**: `https://auth.example.com/auth/saml/callback`
3. **Audience URI (SP Entity ID)**: `volta-sp-audience`
4. Name ID format: `EmailAddress`
5. Export the Okta metadata XML, register it in volta admin:

```bash
curl -X POST https://auth.example.com/admin/idp \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F "kind=SAML" \
  -F "issuer=http://www.okta.com/exk..." \
  -F "audience=volta-sp-audience" \
  -F "x509Cert=@okta.cer"
```

volta enforces **XXE / XSW** defences on every assertion — see
[auth-flows.md](auth-flows.md#saml-xswxxe-test-coverage).

### Generic SAML IdP

Any SAML 2.0 IdP works as long as it can POST an `AuthnResponse` to
`POST /auth/saml/callback`. Required fields:

- `<saml:Issuer>` matching the registered `issuer`
- `<saml:AudienceRestriction>` matching `audience`
- `<saml:Subject><saml:NameID>` containing an email
- A single `<ds:Signature>` over the Assertion (or Response)
- `<saml:SubjectConfirmationData NotOnOrAfter="...">` within 5 minutes

---

## Enabling Passkey

```bash
PASSKEY_ENABLED=true
PASSKEY_RP_ID=auth.example.com      # the RP ID the browser binds credentials to
PASSKEY_RP_NAME="Example Inc."
```

Users register credentials via `POST /auth/passkey/register/start` → browser
WebAuthn ceremony → `POST /auth/passkey/register/finish`. Authenticator type is
selectable at registration (`0d17ce6`).

## Enabling MFA (TOTP)

```bash
MFA_ENABLED=true
MFA_ISSUER="Example Inc."
```

Users enroll via `POST /auth/mfa/setup`, scan a QR code, and confirm with
`POST /auth/mfa/verify`. The MFA flow is a 4-state tramli FlowDefinition — see
[architecture.md](architecture.md#mfa-flow-tramli).

> **ADR-004**: MFA verification is *tenant-scoped*. Switching tenants forces a
> fresh MFA challenge.

---

## Verifying the install

```bash
# 1. Health
curl -s http://localhost:7070/health | jq .

# 2. ForwardAuth (unauthenticated → 302)
curl -i -H "X-Forwarded-Host: app.example.com" \
        -H "X-Forwarded-Uri: /"               \
        http://localhost:7070/auth/verify | head -5

# 3. ForwardAuth (authenticated → 200 with headers)
curl -i -H "Cookie: __volta_session=..." \
        http://localhost:7070/auth/verify
```

Expected for (3):

```
HTTP/1.1 200 OK
X-Volta-User-Id:   abc123
X-Volta-Tenant-Id: t456
X-Volta-Role:      MEMBER
X-Volta-Email:     user@example.com
```

---

## Next steps

- [architecture.md](architecture.md) — two-layer session SM + tramli flow SMs
- [auth-flows.md](auth-flows.md) — concrete OIDC / SAML / MFA / Passkey sequences + test coverage
- [decisions/](decisions/) — ADRs governing bypass, MFA scope, etc.
- [CHANGELOG.md](../CHANGELOG.md) — release notes
