# JWT (JSON Web Token)

[日本語版はこちら](jwt.ja.md)

---

## What is it?

A JSON Web Token (JWT, pronounced "jot") is a compact, self-contained piece of data that one system can give to another to prove something -- usually "this person is authenticated and has these properties." It is a string of text that looks like random characters, but it is actually structured data that has been digitally signed.

Think of it like a wristband at a concert. The security guard at the entrance checks your ticket and gives you a wristband. After that, every staff member inside the venue can look at your wristband and know you are allowed to be there, without calling the entrance guard again. A JWT is the digital equivalent of that wristband.

---

## Why does it matter?

Without JWTs (or something similar), every time an app receives a request, it would need to call back to the authentication server and ask "is this person really logged in?" That creates a bottleneck -- one server that every request must go through.

With JWTs, the authentication server issues a signed token once. After that, any service can verify the token on its own by checking the signature. No callback needed. This is especially important in distributed systems where multiple services need to verify identity.

If JWTs did not exist, every microservice would need direct access to the session database, creating tight coupling and a single point of failure.

---

## How does it work?

### The 3 parts

A JWT has three parts, separated by dots:

```
eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6ImtleS0yMDI2LTAzLTMxVDA5LTAwIn0.eyJpc3MiOiJ2b2x0YS1hdXRoIiwiYXVkIjpbInZvbHRhLWFwcHMiXSwic3ViIjoiNTUwZTg0MDAtZTI5Yi00MWQ0LWE3MTYtNDQ2NjU1NDQwMDAwIiwiZXhwIjoxNzExOTAwMDAwLCJpYXQiOjE3MTE4OTk3MDAsImp0aSI6IjEyMzQ1Njc4LTEyMzQtMTIzNC0xMjM0LTEyMzQ1Njc4OTAxMiIsInZvbHRhX3YiOjEsInZvbHRhX3RpZCI6ImFiY2QxMjM0LTU2NzgtOTAxMi0zNDU2LTc4OTAxMjM0NTY3OCIsInZvbHRhX3JvbGVzIjpbIkFETUlOIl0sInZvbHRhX2Rpc3BsYXkiOiJUYXJvIFlhbWFkYSIsInZvbHRhX3RuYW1lIjoiQUNNRSBDb3JwIiwidm9sdGFfdHNsdWciOiJhY21lIn0.SIGNATURE_HERE
```

Let's break it apart:

```
HEADER.PAYLOAD.SIGNATURE

  Part 1: Header          Part 2: Payload         Part 3: Signature
  (metadata)              (the actual data)        (proof of integrity)
  ┌──────────────┐        ┌──────────────────┐     ┌──────────────────┐
  │ {            │        │ {                │     │                  │
  │  "alg":"RS256│        │  "sub":"user-id" │     │  (binary data    │
  │  "typ":"JWT" │        │  "exp":171190..  │     │   that proves    │
  │  "kid":"key-"│        │  "volta_tid":... │     │   parts 1+2      │
  │ }            │        │  ...             │     │   were not        │
  └──────────────┘        │ }                │     │   tampered with)  │
                          └──────────────────┘     └──────────────────┘
```

Each part is **Base64URL-encoded** (a way to represent binary data as URL-safe text). They are NOT encrypted. Anyone can decode them.

### How to decode a JWT

You can decode the header and payload right in your terminal:

```bash
# Take the first part (header) and decode it
echo 'eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6ImtleS0yMDI2LTAzLTMxVDA5LTAwIn0' | base64 -d

# Output:
# {"alg":"RS256","typ":"JWT","kid":"key-2026-03-31T09-00"}
```

Or use a website like [jwt.io](https://jwt.io) to paste a JWT and see all three parts decoded.

### Important: JWTs are NOT encrypted

This is the most common misconception. A JWT is **signed**, not encrypted. Signing means "I can prove this data has not been modified." Encryption means "nobody can read this data." These are different things.

```
  Signed (JWT):         Anyone can READ the data.
                        Nobody can MODIFY it without the signature breaking.

  Encrypted:            Nobody can READ the data.
                        Only the key holder can decrypt it.
```

This is why you should **never put secrets** (passwords, credit card numbers, etc.) inside a JWT. Anyone who intercepts it can decode it. What they cannot do is modify it -- if they change even one character of the payload, the signature will not match.

### What are claims?

The data inside the payload is called "claims." A claim is a piece of information that the token "claims" to be true. There are three types:

**Registered claims** (standard, defined by the JWT specification):

| Claim | Name | Example | Purpose |
|-------|------|---------|---------|
| `iss` | Issuer | `"volta-auth"` | Who created this token |
| `aud` | Audience | `["volta-apps"]` | Who this token is intended for |
| `sub` | Subject | `"550e8400-..."` | Who this token is about (usually user ID) |
| `exp` | Expiration | `1711900000` | When this token expires (Unix timestamp) |
| `iat` | Issued At | `1711899700` | When this token was created |
| `jti` | JWT ID | `"12345678-..."` | Unique identifier for this specific token |

**Public claims** (defined by the IANA JWT registry or by URI to avoid collisions):

These are claims that have well-known meanings, like `email` or `name`.

**Private claims** (custom, agreed between parties):

These are your own claims. volta uses the `volta_` prefix to avoid collisions:

| Claim | Example | Purpose |
|-------|---------|---------|
| `volta_v` | `1` | Schema version (for future compatibility) |
| `volta_tid` | `"abcd1234-..."` | Tenant ID |
| `volta_tname` | `"ACME Corp"` | Tenant display name |
| `volta_tslug` | `"acme"` | Tenant URL slug |
| `volta_roles` | `["ADMIN"]` | User's roles in this tenant |
| `volta_display` | `"Taro Yamada"` | User's display name |

### Real example of a volta JWT

Here is what a decoded volta JWT looks like:

**Header:**
```json
{
  "alg": "RS256",
  "typ": "JWT",
  "kid": "key-2026-03-31T09-00"
}
```

**Payload:**
```json
{
  "iss": "volta-auth",
  "aud": ["volta-apps"],
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "exp": 1711900000,
  "iat": 1711899700,
  "jti": "12345678-1234-1234-1234-123456789012",
  "volta_v": 1,
  "volta_tid": "abcd1234-5678-9012-3456-789012345678",
  "volta_tname": "ACME Corp",
  "volta_tslug": "acme",
  "volta_roles": ["ADMIN"],
  "volta_display": "Taro Yamada"
}
```

Notice what is **not** in the payload: the user's email. volta deliberately excludes it. If you need the email, call `/api/v1/users/me`. This limits exposure if a JWT is leaked.

**Signature:**

The signature is created by taking the header + "." + payload, and signing it with the private RSA key. Only volta-auth-proxy has the private key. Anyone with the public key (available at `/.well-known/jwks.json`) can verify the signature.

---

## How does volta-auth-proxy use it?

### JWT lifecycle in volta

```
  1. User logs in via Google OIDC
                │
                ▼
  2. volta creates a session (cookie-based, 8 hours)
                │
                ▼
  3. volta issues a JWT (RS256, 5-minute expiry)
     ┌────────────────────────────────────┐
     │  JWT contains: user ID, tenant ID, │
     │  roles, display name, tenant slug  │
     └────────────────────────────────────┘
                │
                ▼
  4. JWT is sent to apps via:
     - X-Volta-JWT header (ForwardAuth)
     - Authorization: Bearer <jwt> (Internal API)
                │
                ▼
  5. App verifies JWT locally using JWKS public key
     (no callback to volta needed)
                │
                ▼
  6. JWT expires after 5 minutes
                │
                ▼
  7. volta-sdk-js detects 401 -> calls /auth/refresh
     -> gets new JWT from valid session -> retries
```

### Why 5-minute expiry?

Short-lived tokens are a security trade-off:

- **Short expiry (volta's approach):** If a JWT is stolen, the attacker has at most 5 minutes to use it. If a user's role changes or they are removed, it takes at most 5 minutes to take effect.
- **Long expiry (e.g., 24 hours):** Less refresh overhead, but a stolen token is useful for a long time, and permission changes are delayed.

volta chose 5 minutes as the balance point. The volta-sdk-js handles refresh automatically, so users never notice.

### JWT issuance code

The `JwtService.java` class handles JWT creation and verification:

- `issueToken()` -- Creates a new JWT with all volta claims, signs it with the active RSA private key.
- `verify()` -- Parses a JWT, checks that the algorithm is RS256 (rejects HS256 and none), verifies the signature, checks issuer, audience, and expiration.
- `jwksJson()` -- Returns the public key in JWKS format for `/.well-known/jwks.json`.

---

## Common mistakes and attacks

### Attack 1: The `alg:none` attack

The JWT spec allows an algorithm value of `"none"`, meaning "this token is not signed." If a server blindly trusts the `alg` header, an attacker can:

1. Take a valid JWT
2. Modify the payload (change roles to OWNER, change user ID)
3. Set the header to `{"alg":"none"}`
4. Remove the signature
5. Send it to the server

If the server respects `alg:none`, it skips signature verification and accepts the modified token.

**volta's defense:** `JwtService.verify()` explicitly checks that the algorithm is RS256. Any other algorithm is rejected.

```java
if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
    throw new IllegalArgumentException("Unsupported JWT algorithm");
}
```

### Attack 2: Algorithm confusion (RS256 -> HS256)

This is a subtle attack. RS256 uses a public/private key pair. HS256 uses a shared secret. The public key is... public. If an attacker:

1. Takes a valid JWT
2. Modifies the payload
3. Changes `alg` from RS256 to HS256
4. Signs it using the **public key** as the HS256 secret

Some JWT libraries will see `alg:HS256`, use the "verification key" (which is the public key, intended for RS256 verification) as the HMAC secret, and the signature will match.

**volta's defense:** Same check as above -- only RS256 is accepted. See [rs256.md](rs256.md) and [hs256.md](hs256.md) for details.

### Attack 3: Signature stripping

Some systems have both authenticated and unauthenticated endpoints. An attacker might:

1. Take a JWT
2. Remove the signature (everything after the second dot)
3. Hope the server treats it as an unsigned token and still reads the claims

**volta's defense:** The nimbus-jose-jwt library requires a valid signature for `SignedJWT`. An empty or missing signature causes an exception.

### Mistake 1: Storing sensitive data in JWTs

Because JWTs are not encrypted, anything in the payload is readable by anyone. Do not put passwords, API keys, or personal data (beyond what is needed for authorization) in a JWT.

volta keeps the JWT payload minimal -- no email address, no phone number. Just what apps need for authorization decisions.

### Mistake 2: Not checking expiration

If you decode a JWT but skip the `exp` check, expired tokens will still work. Always verify expiration.

### Mistake 3: Storing JWTs in localStorage

JWTs stored in the browser's localStorage are accessible to any JavaScript on the page. If there is an XSS vulnerability, the attacker can steal the JWT. volta does not store JWTs in localStorage -- they are obtained fresh from the session cookie via `/auth/refresh` and used only in memory.

---

## Further reading

- [RFC 7519 - JSON Web Token](https://tools.ietf.org/html/rfc7519) -- The official JWT specification.
- [jwt.io](https://jwt.io/) -- Interactive JWT decoder and library list.
- [Critical vulnerabilities in JSON Web Token libraries](https://auth0.com/blog/critical-vulnerabilities-in-json-web-token-libraries/) -- The `alg:none` and RS256/HS256 confusion attacks explained.
- [rs256.md](rs256.md) -- Why volta uses RS256 specifically.
- [hs256.md](hs256.md) -- Why HS256 is dangerous in distributed systems.
- [oidc.md](oidc.md) -- How OIDC uses JWTs as id_tokens.
