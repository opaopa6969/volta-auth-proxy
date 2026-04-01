# M2M (Machine-to-Machine)

[日本語版はこちら](m2m.ja.md)

---

## What is it?

M2M (Machine-to-Machine) communication is when two software systems talk to each other without any human involvement. No browser, no login form, no "click Allow" -- just one server sending requests to another server, authenticated by pre-shared credentials.

Imagine a factory with two robots on an assembly line. Robot A finishes painting a car door and signals Robot B to start installing it. No human supervisor stands between them -- they coordinate directly, using a protocol they both understand. M2M communication on the internet works the same way: services authenticate with credentials and exchange data automatically.

In the OAuth 2.0 world, M2M communication uses the [Client Credentials](client-credentials.md) grant. The calling service presents a `client_id` and `client_secret`, receives a short-lived [JWT](jwt.md), and uses that token to access APIs.

---

## Why does it matter?

Modern SaaS applications are not monoliths -- they are composed of many services:

```
  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
  │  Billing     │    │  Notification│    │  Analytics   │
  │  Service     │    │  Service     │    │  Service     │
  └──────┬───────┘    └──────┬───────┘    └──────┬───────┘
         │                   │                   │
         ▼                   ▼                   ▼
  ┌─────────────────────────────────────────────────────┐
  │              volta-auth-proxy                        │
  │         (authenticates all M2M calls)                │
  └─────────────────────────────────────────────────────┘
         │                   │                   │
         ▼                   ▼                   ▼
  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
  │  User        │    │  Tenant      │    │  Role        │
  │  API         │    │  API         │    │  API         │
  └──────────────┘    └──────────────┘    └──────────────┘
```

Without M2M authentication:

- Any process on the network could call any [internal API](internal-api.md)
- You cannot distinguish the billing service from the analytics service in logs
- There is no way to scope permissions -- every service has full access
- Credential rotation is a nightmare

With proper M2M auth, each service has its own identity, scoped permissions, and auditable access.

---

## How does it work?

### The M2M authentication flow

```
  Billing Service                       volta-auth-proxy
  ================                      ================

  1. I need to read tenant data.
     I have no user context -- I am a machine.

  2. POST /oauth/token
     grant_type=client_credentials
     &client_id=billing-service
     &client_secret=my-secret-key
     &scope=read:tenants read:billing

  ─────────────────────────────────────────────────►

                                        3. Validate credentials:
                                           - client_id registered? ✓
                                           - client_secret matches? ✓
                                           - scopes allowed? ✓

                                        4. Issue M2M JWT:
                                           {
                                             "sub": "billing-svc-uuid",
                                             "volta_client": true,
                                             "volta_client_id":
                                               "billing-service",
                                             "volta_tid": "acme-uuid",
                                             "volta_roles": [
                                               "read:tenants",
                                               "read:billing"
                                             ],
                                             "exp": +5 min
                                           }

  ◄─────────────────────────────────────────────────

  5. Use token to call APIs:
     GET /api/v1/tenants/acme-uuid
     Authorization: Bearer eyJhbGci...

  ─────────────────────────────────────────────────►

                                        6. Verify JWT:
                                           - volta_client=true → M2M
                                           - scopes include read:tenants
                                           - Return tenant data

  ◄─────────────────────────────────────────────────
```

### M2M vs human authentication

| Aspect | Human (Authorization Code) | Machine (Client Credentials) |
|--------|---------------------------|------------------------------|
| Who authenticates | A person via browser | A service via API call |
| Credentials | Username + password (+ MFA) | client_id + client_secret |
| [Session](session.md) | Yes, with [cookies](cookie.md) | No session, just short-lived tokens |
| Consent screen | Yes ("Allow this app to...") | No (pre-configured scopes) |
| Token lifetime | 15 min - 1 hour | 5 minutes (volta default) |
| Refresh token | Yes | No (just re-authenticate) |

### Token caching

M2M tokens are short-lived but requesting a new one for every API call is wasteful. Services should cache the token and re-authenticate only when it expires:

```
  ┌─────────────────────────────────────────────┐
  │  Token Cache Strategy                        │
  │                                              │
  │  1. Check cache for valid token              │
  │  2. If token exists and not expired → use it │
  │  3. If expired or missing:                   │
  │     a. POST /oauth/token                     │
  │     b. Cache new token                       │
  │     c. Set cache TTL = expires_in - 30sec    │
  │  4. Use cached token                         │
  └─────────────────────────────────────────────┘
```

The `-30sec` buffer ensures the token is refreshed before it actually expires, avoiding race conditions.

---

## How does volta-auth-proxy use it?

### Phase 1: Static service token (current)

volta currently supports a simple M2M mechanism:

```
  Authorization: Bearer volta-service:<VOLTA_SERVICE_TOKEN>
```

This authenticates as a generic service principal. It works but has limitations: one token for all services, no scoping, no per-service audit trail.

### Phase 2: Full Client Credentials (planned)

volta will add an `oauth_clients` table and a `/oauth/token` endpoint:

```
  Register a client:
    POST /api/v1/tenants/{tid}/oauth-clients
    {
      "name": "billing-service",
      "scopes": ["read:tenants", "read:billing"]
    }

  Authenticate:
    POST /oauth/token
    grant_type=client_credentials
    &client_id=<returned-client-id>
    &client_secret=<returned-secret>
```

Each M2M client is scoped to a [tenant](tenant.md), can only request allowed scopes, and gets a unique identity in audit logs.

### M2M tokens in [webhooks](webhook.md)

When volta's [outbox worker](outbox-pattern.md) delivers webhooks to external services, it may use M2M authentication to call the target endpoint if the receiver requires bearer authentication. The worker authenticates itself, obtains a short-lived token, and includes it in the webhook request.

### How upstream apps detect M2M calls

volta forwards M2M requests to your [upstream](upstream.md) app with special [headers](header.md):

```
  X-Volta-User-Id:    00000000-0000-0000-0000-000000000000
  X-Volta-Email:      m2m@billing-service
  X-Volta-Roles:      read:tenants,read:billing
  X-Volta-M2M:        true
  X-Volta-Client-Id:  billing-service
```

Your app can check `X-Volta-M2M: true` to apply different logic for machine calls (e.g., skip rate limits, adjust response format).

---

## Common mistakes and attacks

### Mistake 1: Using M2M tokens for user operations

An M2M token has no user identity. If you use it to create records, those records have no audit trail back to a human. Always use user tokens for user-initiated actions.

### Mistake 2: Sharing one client across multiple services

If all services share the same `client_id`, you lose per-service scoping and auditing. Register a separate client for each service.

### Mistake 3: Not rotating client secrets

Client secrets should be rotated periodically. volta supports dual-secret rotation: register a new secret, update the service, then revoke the old secret.

### Mistake 4: Caching tokens forever

M2M tokens expire for a reason. If your cache ignores expiry, you will use stale tokens and get 401 errors. Always respect `expires_in`.

### Attack: Stolen client_secret

If an attacker obtains a service's client_secret, they can impersonate that service. Defense: short-lived tokens (5 min), per-service scoping, anomaly detection on M2M usage patterns.

### Attack: Privilege escalation via scope manipulation

An attacker modifies the scope parameter to request more permissions than allowed. Defense: volta validates requested scopes against the client's registered scopes and rejects unauthorized requests.

---

## Further reading

- [client-credentials.md](client-credentials.md) -- The OAuth 2.0 grant type used for M2M.
- [jwt.md](jwt.md) -- Structure of M2M tokens.
- [oauth2.md](oauth2.md) -- OAuth 2.0 overview.
- [internal-api.md](internal-api.md) -- Internal APIs that M2M clients access.
- [webhook.md](webhook.md) -- Server-to-server notifications, often used with M2M auth.
