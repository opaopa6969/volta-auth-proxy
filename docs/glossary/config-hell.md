# Configuration Hell

[日本語版はこちら](config-hell.ja.md)

---

## What is it?

Configuration hell is when a piece of software has so many settings, options, and knobs to turn that setting it up (or maintaining it) becomes a nightmare of reading documentation, guessing at values, and praying nothing breaks.

Think of it like a TV remote with 97 buttons. You just want to change the channel and adjust the volume. But the remote has buttons for "input source," "aspect ratio," "color temperature," "motion smoothing," "audio return channel," "CEC control," and 91 other things you have never heard of. You press the wrong button and the screen goes blue. Configuration hell is when software gives you 97 buttons when you needed 3.

---

## Why does it matter?

Configuration hell is a real problem in enterprise software. It happens when software tries to be everything for everyone. Each use case adds more settings. Over years, the configuration file grows into a monster that nobody fully understands -- not even the original developers.

The consequences are serious:

1. **Slow setup:** What should take an hour takes a week of reading docs.
2. **Hidden bugs:** A wrong setting in line 347 of a 500-line config file causes a subtle security flaw.
3. **Fear of change:** "Don't touch the config, it works somehow" becomes the team mantra.
4. **Onboarding pain:** New team members spend days understanding the configuration before they can do anything useful.

---

## Keycloak as a real-world example

[Keycloak](keycloak.md) is the poster child for configuration hell in the auth world. A Keycloak realm export (`realm.json`) can easily reach 500+ lines:

```json
{
  "realm": "my-saas",
  "enabled": true,
  "sslRequired": "external",
  "registrationAllowed": false,
  "registrationEmailAsUsername": true,
  "rememberMe": false,
  "verifyEmail": false,
  "loginWithEmailAllowed": true,
  "duplicateEmailsAllowed": false,
  "resetPasswordAllowed": false,
  "editUsernameAllowed": false,
  "bruteForceProtected": true,
  "permanentLockout": false,
  "maxFailureWaitSeconds": 900,
  "minimumQuickLoginWaitSeconds": 60,
  "waitIncrementSeconds": 60,
  "quickLoginCheckMilliSeconds": 1000,
  "maxDeltaTimeSeconds": 43200,
  "failureFactor": 30,
  "roles": {
    "realm": [
      { "name": "offline_access", "composite": false },
      { "name": "uma_authorization", "composite": false }
    ],
    "client": {
      "my-app": [
        { "name": "user", "composite": false },
        { "name": "admin", "composite": false }
      ]
    }
  },
  "defaultRoles": ["offline_access", "uma_authorization"],
  "requiredCredentials": ["password"],
  "otpPolicyType": "totp",
  "otpPolicyAlgorithm": "HmacSHA1",
  "otpPolicyInitialCounter": 0,
  "otpPolicyDigits": 6,
  "otpPolicyLookAheadWindow": 1,
  "otpPolicyPeriod": 30,
  "clients": [
    {
      "clientId": "my-app",
      "enabled": true,
      "clientAuthenticatorType": "client-secret",
      "redirectUris": ["https://myapp.example.com/*"],
      "webOrigins": ["https://myapp.example.com"],
      "standardFlowEnabled": true,
      "implicitFlowEnabled": false,
      "directAccessGrantsEnabled": false,
      "serviceAccountsEnabled": false,
      "publicClient": true,
      "frontchannelLogout": false,
      "protocol": "openid-connect",
      "attributes": {
        "pkce.code.challenge.method": "S256"
      },
      "fullScopeAllowed": true,
      "defaultClientScopes": ["openid", "profile", "email"],
      "optionalClientScopes": ["offline_access"]
    }
  ]
  // ... this goes on for HUNDREDS more lines ...
}
```

And this is just **one realm**. If you have multi-tenancy via multiple realms, multiply this by the number of tenants.

Questions a developer faces with this file:

- What is `uma_authorization`? Do I need it?
- What is `quickLoginCheckMilliSeconds`? What happens if I set it wrong?
- What is `fullScopeAllowed`? Is `true` safe?
- What are `defaultClientScopes` versus `optionalClientScopes`?
- Why are there 6 different OTP policy settings?

Most developers cannot answer these questions without spending hours in documentation.

---

## How volta avoids configuration hell

volta takes the opposite approach: minimal configuration with sensible defaults.

### volta's entire configuration

**Environment variables (.env):**

```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/volta_auth
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
SESSION_SECRET=your-random-secret
BASE_URL=https://auth.example.com
```

**Application config (volta-config.yaml):**

```yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
  - id: app-admin
    url: https://admin.example.com
    allowed_roles: [ADMIN, OWNER]
```

That is it. The entire configuration fits on a single screen. Every setting is obvious:

- `DATABASE_URL` -- where the database is
- `GOOGLE_CLIENT_ID` -- your Google OAuth credentials
- `SESSION_SECRET` -- a random string for signing cookies
- `apps` -- which applications volta protects, and who can access them

### Why this works

volta can keep configuration simple because it makes opinionated choices instead of exposing options:

| Keycloak: you decide | volta: already decided |
|---------------------|----------------------|
| Which OIDC flows to enable? | Authorization Code + PKCE only |
| Session timeout? | 8-hour sliding window (hardcoded sensible default) |
| JWT algorithm? | RS256 only (see [rs256.md](rs256.md)) |
| Max concurrent sessions? | 5 per user |
| JWT expiry? | 5 minutes |
| Password policy? | N/A (Google handles passwords) |
| Token signing key format? | RSA 2048-bit, auto-generated on first boot |

This is not "less powerful." This is "we made the right choice so you don't have to." Every default in volta is a deliberate security decision, not a placeholder.

---

## The trade-off

Configuration hell happens when software prioritizes flexibility over simplicity. volta prioritizes simplicity over flexibility. The trade-off:

- **Keycloak:** Can do almost anything. Takes a week to configure correctly.
- **volta:** Does one thing (multi-tenant SaaS auth) well. Takes an hour to configure.

If you need features that volta does not expose settings for, you modify the source code. volta is open-source precisely for this reason.

---

## Further reading

- [keycloak.md](keycloak.md) -- The real-world example of configuration complexity.
- [self-hosting.md](self-hosting.md) -- How volta makes self-hosting practical via simple config.
- [flyway.md](flyway.md) -- Another area where volta automates away configuration (database migrations).
