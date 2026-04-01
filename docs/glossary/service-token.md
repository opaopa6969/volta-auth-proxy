# Service Token

[日本語版はこちら](service-token.ja.md)

---

## What is it?

A service token is a static, pre-shared secret used for machine-to-machine authentication. Unlike a user's [session](session.md) (tied to a browser) or a [JWT](jwt.md) (short-lived, signed), a service token is a long-lived string that background jobs, cron tasks, and internal services use to authenticate themselves with volta-auth-proxy's API.

Think of it like a master key for building maintenance. Human employees get individual key cards (sessions) that expire and can be revoked. But the cleaning crew has a master key that opens all doors, does not expire on its own, and is shared among the crew. It is less secure than individual keys, but necessary for the building to function when no human is actively swiping a card.

In volta-auth-proxy, the service token is set via the `VOLTA_SERVICE_TOKEN` environment variable. Requests authenticate by sending `Authorization: Bearer volta-service:{token}`. Service token requests bypass normal user authentication but still require a tenant context via the `X-Volta-Tenant-Id` header.

---

## Why does it matter?

Not all API calls come from logged-in users sitting at browsers. Some come from:

- **Background jobs**: Nightly cleanup of expired sessions
- **Cron tasks**: Sending invitation reminder emails
- **Internal services**: A billing service checking membership status
- **CI/CD pipelines**: Automated tenant provisioning

Without service tokens, these jobs would need to:
- Fake a user login (insecure, fragile)
- Bypass auth entirely (dangerous)
- Store a user's credentials in plaintext (terrible)

Service tokens provide a clean, auditable, revocable way for machines to call the API.

---

## How does it work?

### Authentication flow

```
  Background job                          volta-auth-proxy
  ┌──────────────┐                       ┌──────────────────┐
  │              │  Authorization:        │                  │
  │ GET /api/v1/ │  Bearer volta-service: │  1. Extract token│
  │ tenants/{id}/│  {VOLTA_SERVICE_TOKEN} │  2. Compare with │
  │ members      │  ─────────────────────►│     env var      │
  │              │  X-Volta-Tenant-Id:    │  3. Match? →     │
  │              │  {tenant-uuid}         │     AuthPrincipal│
  │              │                        │     (service)    │
  │              │◄─── 200 [{members}] ───│                  │
  └──────────────┘                       └──────────────────┘
```

### Service AuthPrincipal

When a service token authenticates successfully, volta creates a special `AuthPrincipal`:

```java
// AuthService.java
return Optional.of(new AuthPrincipal(
    new UUID(0L, 0L),           // userId: all zeros (not a real user)
    "service@volta.local",       // email: synthetic
    "service-token",             // displayName
    new UUID(0L, 0L),           // tenantId: from X-Volta-Tenant-Id header
    "service",                   // tenantName
    "service",                   // tenantSlug
    List.of("SERVICE"),          // roles: SERVICE (special role)
    true                         // serviceToken: true
));
```

The `serviceToken: true` flag lets downstream code distinguish between user requests and service requests.

### Token format

```
  Authorization: Bearer volta-service:abc123xyz789...
                        ├──────────────┤├────────────┤
                        prefix          actual token
                        (identifies     (matches
                         token type)    VOLTA_SERVICE_TOKEN env var)
```

The `volta-service:` prefix distinguishes service tokens from JWTs, which do not have a prefix.

### Security constraints

```
  ┌─────────────────────────────────────────────────────┐
  │ Service token capabilities:                         │
  │                                                     │
  │ ✓ Call any /api/v1/* endpoint                       │
  │ ✓ Act on behalf of any tenant (via header)          │
  │ ✓ Bypass session/cookie requirements                │
  │                                                     │
  │ Service token limitations:                          │
  │                                                     │
  │ ✗ Cannot access browser-only endpoints (/login, etc)│
  │ ✗ Must be on Docker internal network                │
  │ ✗ Cannot be used from the public internet           │
  │ ✗ No role hierarchy — SERVICE role is flat           │
  └─────────────────────────────────────────────────────┘
```

---

## How does volta-auth-proxy use it?

### Configuration

```bash
# Environment variable
VOLTA_SERVICE_TOKEN=your-very-long-random-secret-here
```

The token must be:
- At least 32 characters long
- Cryptographically random
- Never committed to version control
- Rotated periodically

### Implementation in AuthService.java

```java
// AuthService.java — authenticate()
String auth = ctx.header("Authorization");
if (auth != null && auth.startsWith("Bearer ")) {
    String token = auth.substring("Bearer ".length()).trim();
    if (token.startsWith("volta-service:")) {
        String provided = token.substring("volta-service:".length());
        if (!config.serviceToken().isBlank()
            && config.serviceToken().equals(provided)) {
            return Optional.of(/* service AuthPrincipal */);
        }
        return Optional.empty();  // Invalid service token → 401
    }
    // Otherwise, try JWT verification...
}
```

### Tenant context requirement

Service tokens must include `X-Volta-Tenant-Id` header because the token itself is not tenant-scoped:

```yaml
# dsl/protocol.yaml
service_context:
  header: "Authorization: Bearer volta-service:{VOLTA_SERVICE_TOKEN}"
  requires_tenant_header: "X-Volta-Tenant-Id"
```

### Audit logging

Service token requests are logged with the synthetic `service@volta.local` actor:

```
  ┌────────────────────────────────────────────────┐
  │ event_type:  MEMBER_ROLE_CHANGED               │
  │ actor_id:    00000000-0000-0000-0000-0000...   │
  │ actor_ip:    172.18.0.5 (Docker internal)      │
  │ detail:      { "via": "service-token" }        │
  └────────────────────────────────────────────────┘
```

---

## Common mistakes and attacks

### Mistake 1: Short or predictable tokens

Using `service-token-123` as your service token is asking to be brute-forced. Use `openssl rand -base64 48` to generate a proper token.

### Mistake 2: Committing tokens to version control

Service tokens in `docker-compose.yaml` or `.env` files that are committed to Git are immediately compromised. Use secrets management (Docker secrets, Vault, cloud provider secret stores).

### Mistake 3: Not rotating tokens

A service token that has been in use for 3 years has been seen by every developer who ever had access to the environment. Rotate tokens regularly and revoke old ones.

### Mistake 4: Exposing the service token endpoint to the internet

Service tokens should only work from the Docker internal network. If an attacker on the internet can use a service token, they have full API access to every tenant.

### Attack: Token theft via logs

If the service token appears in application logs (e.g., logged request headers), an attacker who accesses the logs gains service token access. volta never logs Authorization header values.

---

## Further reading

- [jwt.md](jwt.md) -- User JWTs vs service tokens.
- [internal-api.md](internal-api.md) -- The API that service tokens authenticate against.
- [delegation.md](delegation.md) -- Machine-to-machine delegation via service tokens.
- [audit-log.md](audit-log.md) -- How service token actions are audited.
- [header.md](header.md) -- The Authorization header format.
- [tenant.md](tenant.md) -- Tenant context required for service tokens.
