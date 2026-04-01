# Claim

[日本語版はこちら](claim.ja.md)

---

## What is it?

A claim is a piece of information asserted inside a [JWT](jwt.md). When a server issues a token, it includes claims that describe the user, the token's purpose, and its constraints. "This token claims the user is alice, she belongs to the acme tenant, and her role is ADMIN."

Think of it like the information printed on an employee badge. The badge itself is the [token](token.md). The claims are what is written on it: your name, department, employee number, and the date it expires. Anyone who trusts the badge issuer trusts the claims printed on it.

Claims are not encrypted -- they are Base64-encoded and readable by anyone. The [digital signature](digital-signature.md) on the JWT guarantees that the claims have not been tampered with, but it does not hide them. Never put secrets (passwords, credit card numbers) in claims.

---

## Why does it matter?

Claims are how downstream applications learn about the user without calling back to the auth server. A microservice receiving a JWT can read the claims to answer: Who is this user? What tenant do they belong to? What are they allowed to do? When does this token expire?

Without claims, every service would need to make a database call or API call to the auth server on every request. Claims make stateless verification possible -- the information travels with the token.

If claims are missing or wrong, services make wrong authorization decisions. If `exp` is missing, the token never expires. If `volta_roles` is wrong, a regular user gets admin access.

---

## How does it work?

### Standard (registered) claims

The [JWT specification (RFC 7519)](https://datatracker.ietf.org/doc/html/rfc7519) defines a set of standard claims:

| Claim | Name | Purpose | Example |
|-------|------|---------|---------|
| `iss` | Issuer | Who created this token | `"https://volta.example.com"` |
| `sub` | Subject | Who the token is about | `"user-uuid-1234"` |
| `aud` | Audience | Who should accept this token | `"https://api.example.com"` |
| `exp` | Expiration | When the token dies | `1743530700` (Unix timestamp) |
| `iat` | Issued At | When the token was created | `1743530400` |
| `jti` | JWT ID | Unique ID for this token | `"550e8400-e29b-..."` |

### Custom (private) claims

Applications can add their own claims. volta prefixes custom claims with `volta_` to avoid collisions:

```json
{
  "iss": "https://volta.example.com",
  "sub": "user-uuid-1234",
  "aud": "https://api.example.com",
  "exp": 1743530700,
  "iat": 1743530400,
  "jti": "550e8400-e29b-41d4-a716-446655440000",
  "volta_v": 1,
  "volta_tid": "tenant-uuid-5678",
  "volta_roles": ["admin", "editor"],
  "volta_display": "Alice Smith",
  "volta_tname": "Acme Corp",
  "volta_tslug": "acme"
}
```

### Claims are readable, not secret

```
  JWT payload (Base64 decoded):
  ┌──────────────────────────────────────────────────┐
  │  {                                               │
  │    "sub": "alice-uuid",        ← WHO             │
  │    "volta_tid": "acme-uuid",   ← WHICH TENANT    │
  │    "volta_roles": ["admin"],   ← WHAT PERMISSION  │
  │    "exp": 1743530700,          ← WHEN IT DIES     │
  │    "iss": "volta.example.com"  ← WHO ISSUED IT    │
  │  }                                               │
  └──────────────────────────────────────────────────┘
        │
        │  Anyone can decode this with:
        │  echo <payload> | base64 -d
        │
        │  But nobody can CHANGE it without
        │  invalidating the signature.
        ▼
  ┌──────────────────────────────────────────────────┐
  │  Signature (RS256)                               │
  │  Created with private key → verified with public │
  │  If any claim is modified, signature fails.      │
  └──────────────────────────────────────────────────┘
```

### Claim validation flow

```
  Downstream app receives JWT:

  1. Decode header → check alg is RS256       ✓
  2. Verify signature with public key         ✓
  3. Check claims:
     ├── exp > now?                           ✓ Not expired
     ├── iss == "volta.example.com"?          ✓ Correct issuer
     ├── aud contains my service?             ✓ Intended for me
     ├── volta_tid matches request tenant?    ✓ Right tenant
     └── volta_roles contains required role?  ✓ Authorized
  4. Allow request                            ✓
```

---

## How does volta-auth-proxy use it?

volta's `JwtService.issueToken()` builds claims for every JWT:

```java
JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer(config.jwtIssuer())               // iss
        .audience(audience)                        // aud
        .subject(principal.userId().toString())     // sub
        .expirationTime(Date.from(now.plusSeconds(  // exp (5 min)
            config.jwtTtlSeconds())))
        .issueTime(Date.from(now))                 // iat
        .jwtID(UUID.randomUUID().toString())        // jti
        .claim("volta_v", 1)                       // schema version
        .claim("volta_tid", principal.tenantId())   // tenant ID
        .claim("volta_roles", principal.roles())    // user roles
        .claim("volta_display", principal.displayName())
        .claim("volta_tname", principal.tenantName())
        .claim("volta_tslug", principal.tenantSlug())
        .build();
```

### volta's custom claims

| Claim | Type | Purpose |
|-------|------|---------|
| `volta_v` | Integer | Schema version for forward compatibility |
| `volta_tid` | UUID string | Tenant ID for [multi-tenant](multi-tenant.md) isolation |
| `volta_roles` | String array | User roles for authorization (`admin`, `member`, etc.) |
| `volta_display` | String | User display name |
| `volta_tname` | String | Tenant name |
| `volta_tslug` | String | Tenant slug (URL-safe identifier) |
| `volta_client` | Boolean | `true` for M2M (machine-to-machine) tokens |
| `volta_client_id` | String | OAuth client ID for M2M tokens |

### Verification in volta

`JwtService.verify()` checks every required claim:

1. Algorithm must be RS256 (prevents [algorithm confusion attacks](rs256.md))
2. Signature must be valid against the current [signing key](signing-key.md)
3. `iss` must match the configured issuer
4. `aud` must contain the configured audience
5. `exp` must be in the future

---

## Common mistakes and attacks

### Mistake 1: Not validating all claims

Checking the signature but ignoring `iss` or `aud` means you accept tokens issued by other systems or intended for other services. Always validate all registered claims.

### Mistake 2: Putting secrets in claims

Claims are Base64-encoded, not encrypted. Anyone with the token can read them. Never include passwords, API keys, or PII beyond what is necessary.

### Mistake 3: Trusting claims without signature verification

A JWT without a verified signature is just JSON. An attacker can craft any claims they want. Always verify the [cryptographic signature](cryptographic-signature.md) first.

### Attack: Claim injection via algorithm confusion

If a verifier accepts `alg: none` or switches from RS256 to [HS256](hs256.md) using the public key as the secret, attackers can forge tokens with arbitrary claims. volta prevents this by hard-coding RS256 in verification.

### Attack: Privilege escalation via claim tampering

An attacker modifies `volta_roles: ["admin"]` in a token. This fails because the [digital signature](digital-signature.md) invalidates, but only if the verifier actually checks the signature.

---

## Further reading

- [jwt.md](jwt.md) -- The token format that contains claims
- [jwt-payload.md](jwt-payload.md) -- The JWT section where claims live
- [token.md](token.md) -- The broader concept of tokens
- [digital-signature.md](digital-signature.md) -- How claims are protected from tampering
- [rs256.md](rs256.md) -- The specific algorithm volta uses to sign claims
