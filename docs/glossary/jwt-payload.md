# JWT Payload

[日本語版はこちら](jwt-payload.ja.md)

---

## What is it?

The payload is the second part of a JWT (`header.payload.signature`). It contains the **claims** -- key-value pairs of information about the user and the token itself. Think of it as the body of a letter: the actual content that matters.

Claims come in two flavors:
- **Registered claims:** Standardized names defined by the JWT specification (e.g., `iss`, `sub`, `exp`).
- **Custom claims:** Application-specific data you add yourself (e.g., `volta_tid`, `volta_roles`).

---

## Why does it matter?

The payload is what makes JWTs useful. Instead of just saying "this user is authenticated," a JWT can say "this is user X, in tenant Y, with roles Z, and this token expires at time T." The receiving service gets all the context it needs in a single token without making a database call.

Important: **the payload is NOT encrypted.** It's only base64url-encoded, which means anyone can decode and read it. Never put passwords, credit card numbers, or other secrets in a JWT payload.

---

## A simple example

A decoded volta JWT payload:

```json
{
  "iss": "volta-auth",
  "aud": ["volta-apps"],
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "exp": 1711875900,
  "iat": 1711875600,
  "jti": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "volta_v": 1,
  "volta_tid": "660e8400-e29b-41d4-a716-446655440000",
  "volta_roles": ["ADMIN"],
  "volta_display": "Tanaka Taro",
  "volta_tname": "Acme Corp",
  "volta_tslug": "acme-corp"
}
```

### Registered claims

| Claim | Full name | Meaning |
|-------|-----------|---------|
| `iss` | Issuer | Who created this token |
| `aud` | Audience | Who this token is for |
| `sub` | Subject | The user's unique ID |
| `exp` | Expiration | When the token expires (Unix timestamp) |
| `iat` | Issued At | When the token was created |
| `jti` | JWT ID | A unique ID for this specific token |

### volta custom claims

| Claim | Meaning |
|-------|---------|
| `volta_v` | Schema version (currently `1`). Allows future changes without breaking old tokens |
| `volta_tid` | Tenant ID. Which workspace this token is scoped to |
| `volta_roles` | User's roles in this tenant (e.g., `["MEMBER"]`, `["ADMIN"]`) |
| `volta_display` | User's display name |
| `volta_tname` | Tenant name |
| `volta_tslug` | Tenant slug (URL-safe identifier) |

---

## In volta-auth-proxy

volta builds the payload in `JwtService.issueToken()`:

```java
new JWTClaimsSet.Builder()
    .issuer(config.jwtIssuer())           // "volta-auth"
    .audience(List.of(config.jwtAudience()))  // ["volta-apps"]
    .subject(principal.userId().toString())
    .expirationTime(Date.from(now.plusSeconds(config.jwtTtlSeconds())))  // 5 min
    .issueTime(Date.from(now))
    .jwtID(UUID.randomUUID().toString())
    .claim("volta_v", 1)
    .claim("volta_tid", principal.tenantId().toString())
    .claim("volta_roles", principal.roles())
    .claim("volta_display", principal.displayName())
    .claim("volta_tname", principal.tenantName())
    .claim("volta_tslug", principal.tenantSlug())
    .build();
```

The `volta_v` claim is a forward-thinking design choice. If the claim schema changes in v2 (say, adding nested permissions), downstream services can check `volta_v` and handle both formats gracefully.

---

## See also

- [jwt-header.md](jwt-header.md) -- The first part: algorithm and key ID
- [jwt-signature.md](jwt-signature.md) -- The third part: the proof it hasn't been tampered with
- [jwt-decode-howto.md](jwt-decode-howto.md) -- How to read the payload yourself
