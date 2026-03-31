# Client Credentials (OAuth 2.0 Grant Type)

[日本語版はこちら](client-credentials.ja.md)

---

## What is it?

The Client Credentials grant is an OAuth 2.0 flow designed for server-to-server (machine-to-machine, or M2M) communication. Unlike other OAuth flows where a user is involved, Client Credentials lets a service authenticate as itself -- no human in the loop. The service presents its own credentials (client_id and client_secret) and receives an access token.

Think of it like an employee badge for a robot. The robot does not have a personal identity like a human employee. But it has been registered in the system, given its own badge, and authorized to perform specific tasks. When the robot enters a room, it swipes its badge -- it does not pretend to be a human.

---

## Why does it matter?

In modern architectures, services talk to each other constantly:

```
  ┌────────────┐     ┌────────────┐     ┌────────────┐
  │ Billing    │────►│ User       │────►│ Notification│
  │ Service    │     │ Service    │     │ Service     │
  └────────────┘     └────────────┘     └────────────┘
```

Each service needs to verify that the calling service is authorized. Without proper M2M authentication:

- Any service (or attacker) could call any internal API
- There is no way to restrict what each service can do
- There is no audit trail of service-to-service calls
- Rotating credentials requires manual coordination

Client Credentials provides a standardized way for services to authenticate with each other, with proper scoping, expiry, and rotation.

---

## How does it work?

### The flow

```
  Service A (the client)              volta-auth-proxy (the auth server)
  ========================              ================================

  1. Service A needs to call volta's API to list tenant members.
     It does not act on behalf of a user -- it needs its own identity.

  2. Service A sends its credentials:

     POST /oauth/token
     Content-Type: application/x-www-form-urlencoded

     grant_type=client_credentials
     &client_id=billing-service
     &client_secret=s3cr3t-k3y-for-billing
     &scope=read:members write:billing

  ──────────────────────────────────────────────────────────►

                                        3. volta checks:
                                           - Is client_id registered?
                                           - Does client_secret match?
                                           - Are requested scopes allowed
                                             for this client?

                                        4. volta issues a JWT:
                                           {
                                             "sub": "billing-service",
                                             "volta_client": true,
                                             "volta_client_id": "billing-service",
                                             "volta_tid": "acme-uuid",
                                             "volta_roles": ["read:members",
                                                             "write:billing"],
                                             "exp": <5 minutes from now>
                                           }

  ◄──────────────────────────────────────────────────────────

  5. Service A receives:
     {
       "access_token": "eyJhbGci...",
       "token_type": "bearer",
       "expires_in": 300
     }

  6. Service A uses the token to call APIs:

     GET /api/v1/tenants/acme-uuid/members
     Authorization: Bearer eyJhbGci...

  ──────────────────────────────────────────────────────────►

                                        7. volta verifies the JWT
                                           - Check volta_client=true
                                           - Check scopes include
                                             "read:members"
                                           - Return member list

  ◄──────────────────────────────────────────────────────────

  8. Service A receives the member list and processes it.
```

### When to use Client Credentials vs Authorization Code

| Scenario | Grant type | Why |
|----------|-----------|-----|
| User logs in via browser | Authorization Code | A human is present. They can approve permissions. |
| Mobile app accesses user data | Authorization Code + PKCE | A human is present. PKCE protects the code. |
| Backend service calls another service | **Client Credentials** | No human involved. Service authenticates as itself. |
| Cron job processes data | **Client Credentials** | No human involved. Job runs on a schedule. |
| Webhook handler | **Client Credentials** | No human involved. Triggered by external event. |

The rule is simple: **if a human is involved, use Authorization Code. If only machines are involved, use Client Credentials.**

---

## How does volta-auth-proxy handle M2M authentication?

### Phase 1: Static service token (current)

In Phase 1, volta provides a simple M2M mechanism using a static token:

```
  Configuration:
    VOLTA_SERVICE_TOKEN=my-very-long-random-secret-token

  Usage:
    Authorization: Bearer volta-service:my-very-long-random-secret-token

  volta returns a service principal:
    userId:    00000000-0000-0000-0000-000000000000
    email:     service@volta.local
    roles:     ["SERVICE"]
```

This is functional but limited:

| Limitation | Impact |
|-----------|--------|
| One token for all services | Cannot restrict what each service can do |
| No expiry | Token is valid forever (until env var changes) |
| No audit per service | Cannot distinguish Service A from Service B in logs |
| No scoping | Token has full access |
| No rotation without downtime | Changing the token requires redeployment |

### Phase 2: Client Credentials (planned)

The planned implementation adds proper M2M authentication:

```
  Database: oauth_clients table
  ┌─────────────────────────────────────────────────────┐
  │  client_id:     "billing-service"                    │
  │  client_secret: <bcrypt hash>                        │
  │  tenant_id:     acme-uuid                            │
  │  scopes:        ["read:members", "write:billing"]    │
  │  name:          "Billing Service"                    │
  │  active:        true                                 │
  │  created_by:    admin-uuid                           │
  │  created_at:    2026-03-31T09:00:00Z                 │
  └─────────────────────────────────────────────────────┘
```

Benefits over static token:

| Feature | Static Token | Client Credentials |
|---------|-------------|-------------------|
| Per-service identity | No | Yes (client_id) |
| Scoped permissions | No | Yes (scopes) |
| Token expiry | No | Yes (5-minute JWTs) |
| Audit trail | Generic "service" | Per-client logging |
| Secret rotation | Requires redeployment | Rotate per client |
| Tenant scoping | No | Yes (per-tenant clients) |

### volta's M2M JWT

When a service authenticates via Client Credentials, volta issues a JWT with special M2M claims:

```json
{
  "iss": "volta-auth",
  "aud": ["volta-apps"],
  "sub": "billing-service-principal-uuid",
  "exp": 1711900000,
  "iat": 1711899700,
  "volta_v": 1,
  "volta_client": true,
  "volta_client_id": "billing-service",
  "volta_tid": "acme-uuid",
  "volta_roles": ["read:members", "write:billing"]
}
```

The `volta_client: true` flag tells volta (and apps) that this is a machine token, not a human token. This enables different handling:

- M2M tokens might have different rate limits
- Audit logs record "billing-service" instead of a user name
- Some operations might be restricted to human users only

### The JwtService.issueM2mToken() method

volta already has the M2M token issuance method ready:

```java
// JwtService.java
public String issueM2mToken(UUID clientPrincipalId, UUID tenantId,
                            List<String> scopes, List<String> audience,
                            String clientId) {
    AuthPrincipal principal = new AuthPrincipal(
        clientPrincipalId,
        "m2m@" + clientId,
        clientId,
        tenantId, "machine", "machine",
        scopes, true
    );
    return issueToken(principal, audience,
        Map.of("volta_client", true, "volta_client_id", clientId));
}
```

---

## Common mistakes and attacks

### Mistake 1: Using Client Credentials for user-facing flows

If you use Client Credentials when a user should be present, you lose:
- The ability to know WHICH user made the request
- User consent (the user never approved anything)
- Per-user audit trails

### Mistake 2: Hardcoding client_secret in source code

Client secrets in source code end up in version control. Use environment variables or a secrets manager.

### Mistake 3: Not scoping client permissions

Giving every service full access ("admin" scope) defeats the purpose. Each service should have the minimum scopes it needs (principle of least privilege).

### Mistake 4: Long-lived M2M tokens

M2M tokens should expire (volta: 5 minutes). Services should re-authenticate when the token expires. This limits the damage if a token is leaked.

### Attack: Credential stuffing on the token endpoint

An attacker tries many client_id/client_secret combinations against `/oauth/token`. Defense: rate limiting on the token endpoint (volta applies per-IP rate limiting).

### Attack: Stolen client_secret

If a service's client_secret is stolen (from logs, env vars, etc.), the attacker can impersonate that service. Defense: rotate secrets regularly, use short-lived tokens, monitor for unusual M2M activity.

---

## Further reading

- [RFC 6749 Section 4.4](https://tools.ietf.org/html/rfc6749#section-4.4) -- Client Credentials grant specification.
- [oauth2.md](oauth2.md) -- OAuth 2.0 overview and grant types.
- [jwt.md](jwt.md) -- How the M2M JWT is structured.
- [forwardauth.md](forwardauth.md) -- How M2M tokens are verified in the request flow.
