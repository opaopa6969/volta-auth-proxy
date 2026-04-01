# Adding an Identity Provider (IdP)

[English](add-idp.md) | [日本語](add-idp.ja.md)

volta-auth-proxy supports multiple OAuth2/OIDC providers out of the box.
Each provider requires only environment variables — no code changes.

---

## Supported Providers

| Provider | Type | ENV prefix | Notes |
|----------|------|-----------|-------|
| Google | OIDC + PKCE | `GOOGLE_` | Default. Recommended for consumer SaaS |
| GitHub | OAuth2 | `GITHUB_` | Recommended for developer tools |
| Microsoft / Azure AD | OIDC + PKCE | `MICROSOFT_` | Recommended for enterprise / B2B |
| SAML (per-tenant) | SAML 2.0 | Admin API | Tenant-specific SSO — see [SAML setup](#saml-per-tenant) |

Multiple providers can be active simultaneously. The login page shows a button for each enabled provider.

---

## Google (default)

1. Go to [Google Cloud Console](https://console.cloud.google.com/) → APIs & Services → Credentials
2. Create OAuth 2.0 Client ID (Web application)
3. Authorized redirect URI: `https://auth.example.com/callback`

```env
GOOGLE_CLIENT_ID=your-client-id.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=your-client-secret
GOOGLE_REDIRECT_URI=https://auth.example.com/callback
```

---

## GitHub

1. Go to GitHub → Settings → Developer settings → OAuth Apps → New OAuth App
2. Authorization callback URL: `https://auth.example.com/callback`

```env
GITHUB_CLIENT_ID=your-github-client-id
GITHUB_CLIENT_SECRET=your-github-client-secret
```

**Note:** Users with a private email on GitHub must set a public email in their GitHub profile, or grant `user:email` scope (volta requests this automatically).

---

## Microsoft / Azure AD

### Option A: Any Microsoft account (personal + work)

1. Go to [Azure Portal](https://portal.azure.com/) → Microsoft Entra ID → App registrations → New registration
2. Supported account types: **Accounts in any organizational directory and personal Microsoft accounts**
3. Redirect URI: `https://auth.example.com/callback`
4. After registration: Certificates & secrets → New client secret

```env
MICROSOFT_CLIENT_ID=your-application-client-id
MICROSOFT_CLIENT_SECRET=your-client-secret
MICROSOFT_TENANT_ID=common
```

### Option B: Single Azure AD tenant (work accounts only)

Use your tenant's ID instead of `common`:

```env
MICROSOFT_CLIENT_ID=your-application-client-id
MICROSOFT_CLIENT_SECRET=your-client-secret
MICROSOFT_TENANT_ID=your-tenant-id-or-domain.onmicrosoft.com
```

**Note:** When `MICROSOFT_TENANT_ID=common`, volta accepts tokens from any Microsoft tenant. To restrict to your organization only, set your specific tenant ID.

---

## Enabling multiple providers

Just set all relevant ENV vars. volta enables each provider automatically when its client ID is non-empty.

```env
# All three enabled — login page shows 3 buttons
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
GITHUB_CLIENT_ID=...
GITHUB_CLIENT_SECRET=...
MICROSOFT_CLIENT_ID=...
MICROSOFT_CLIENT_SECRET=...
```

The login page renders one button per enabled provider. Users can choose.

---

## SAML (per-tenant)

SAML is configured per-tenant via the Admin API, not ENV vars.
This allows each tenant to bring their own corporate IdP (Okta, Azure AD SAML, etc.).

```bash
# Register a SAML IdP for a tenant
curl -X POST https://auth.example.com/api/v1/admin/idp \
  -H "Authorization: Bearer volta-service:$VOLTA_SERVICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "tenant_id": "your-tenant-uuid",
    "provider_type": "SAML",
    "metadata_url": "https://your-idp.com/saml/metadata",
    "issuer": "https://your-idp.com"
  }'
```

Users in that tenant then access `/auth/saml/login?tenant_id=<uuid>` to use their corporate IdP.

---

## Adding a new provider (for contributors)

To add a provider not listed above (e.g., GitLab, Slack, Apple):

1. **`OidcService.java`** — Add a new `case` in `createAuthorizationUrl()` and `exchangeAndValidate()`
   - OIDC providers: follow the Microsoft pattern (`verifyIdToken` + JWKS URL)
   - OAuth2-only providers: follow the GitHub pattern (access token → userinfo API)

2. **`AppConfig.java`** — Add `fooClientId`, `fooClientSecret`, `isFooEnabled()` fields

3. **`V1x__foo_provider.sql`** — No migration needed (provider is just a string in `oidc_flows.provider`)

4. **`login.jte`** — Add `@param boolean fooEnabled` and a button

5. **`.env.example`** — Document the new ENV vars

6. **This file** — Add a setup section above

The key interface contract:

```java
// What every provider must produce:
new OidcIdentity(
    "<provider>:<unique-id>",  // sub — stored as provider_sub in users table
    email,                     // verified email address
    displayName,               // user's display name
    true,                      // emailVerified
    flow.returnTo(),
    flow.inviteCode(),
    "PROVIDER_NAME"            // uppercase, stored in audit log
)
```

---

## Troubleshooting

| Error | Cause | Fix |
|-------|-------|-----|
| `No IdP configured` | All client IDs are empty | Set at least one `*_CLIENT_ID` |
| `GitHub account has no verified email` | User's GitHub email is private | User sets public email in GitHub profile |
| `Invalid Microsoft issuer` | Token from unexpected tenant | Check `MICROSOFT_TENANT_ID` |
| `Invalid nonce` | State expired (> 10 min) or reused | User restarts login flow |
| `SAML IDP_NOT_FOUND` | No SAML config for tenant | Register via Admin API first |
