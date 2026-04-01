# Token

[日本語版はこちら](token.ja.md)

---

## What is it?

A token is a piece of data that represents identity, permission, or proof of authentication. Instead of sending your username and password with every request, you authenticate once and receive a token. From then on, you present the token to prove who you are.

Think of it like a wristband at a concert. At the gate, you show your ticket (your credentials). The staff gives you a wristband (a token). Inside the venue, nobody asks for your ticket again -- they just look at your wristband. If you have a VIP wristband, you get backstage access. If you have a general admission wristband, you stay in the crowd. The wristband carries your permissions.

In web systems, tokens come in many forms: opaque strings (random IDs that mean nothing by themselves), structured tokens like [JWTs](jwt.md) (which carry data inside them), or session IDs stored in [cookies](cookie.md). What they all share is that they substitute for credentials after initial authentication.

---

## Why does it matter?

Without tokens, every request would need to carry full credentials. That means passwords flying over the network constantly, servers re-checking passwords on every click, and no way to give limited access to third parties. Tokens solve all of this:

- **Security**: Credentials are exposed only once (at login). Tokens can expire, be scoped, and be revoked.
- **Performance**: Verifying a token (especially a JWT) is much cheaper than re-authenticating a user.
- **Delegation**: You can issue a token with limited permissions to a third-party service without sharing the user's password.

If tokens are missing or broken, the entire authentication flow collapses. Users cannot stay logged in. APIs cannot verify callers. Microservices cannot trust each other.

---

## How does it work?

### Token lifecycle

```
  User                    Auth Server                App Server
   │                          │                          │
   │  1. Login (email+pass)   │                          │
   │ ─────────────────────────>                          │
   │                          │                          │
   │  2. Here's your token    │                          │
   │ <─────────────────────────                          │
   │                          │                          │
   │  3. Request + token      │                          │
   │ ──────────────────────────────────────────────────> │
   │                          │                          │
   │                          │  4. Verify token         │
   │                          │ <─────────────────────── │
   │                          │  5. Valid!               │
   │                          │ ───────────────────────> │
   │                          │                          │
   │  6. Here's your data     │                          │
   │ <────────────────────────────────────────────────── │
```

### Types of tokens

| Type | What it is | Example | Revocable? |
|------|-----------|---------|------------|
| **Opaque token** | Random string, meaningless without server lookup | `550e8400-e29b-41d4...` | Yes (delete from DB) |
| **JWT** | Self-contained, carries [claims](claim.md) inside | `eyJhbGciOiJSUzI1Ni...` | Not directly (wait for expiry) |
| **Session ID** | Opaque token stored in a [cookie](cookie.md) | `__volta_session=abc123` | Yes (delete session from DB) |
| **Refresh token** | Long-lived token used to get new access tokens | `dGhpcyBpcyBhIHJl...` | Yes (revoke in DB) |

### Opaque vs. self-contained

```
  Opaque token (session ID):
  ┌────────────────────────┐
  │  550e8400-e29b-41d4... │    ← Just an ID. Means nothing alone.
  └────────────────────────┘
           │
           ▼  Server must look up in DB
  ┌────────────────────────────┐
  │  user_id: alice            │
  │  tenant: acme              │
  │  roles: [admin]            │
  │  expires: 2026-04-01T17:00 │
  └────────────────────────────┘

  Self-contained token (JWT):
  ┌──────────────────────────────────────────┐
  │  header.payload.signature                │
  │                                          │
  │  payload = {                             │
  │    "sub": "alice",                       │
  │    "volta_tid": "acme-uuid",             │
  │    "volta_roles": ["admin"],             │
  │    "exp": 1743530400                     │
  │  }                                       │
  │                                          │
  │  ← Data is INSIDE the token.             │
  │  ← No DB lookup needed to read claims.   │
  │  ← Signature proves it wasn't tampered.  │
  └──────────────────────────────────────────┘
```

### Token expiry

Tokens should not live forever. If a token is stolen, expiry limits the damage:

- **Short-lived** (volta JWT: 5 minutes): Minimizes window of abuse. Requires [silent refresh](silent-refresh.md).
- **Medium-lived** (volta session: 8 hours): Balances security with usability. Uses [sliding window](sliding-window-expiry.md).
- **Long-lived** (refresh tokens: days/weeks): Convenient but high risk. Must be stored securely and revocable.

---

## How does volta-auth-proxy use it?

volta uses two kinds of tokens working together:

### 1. Session token (opaque, in cookie)

When a user logs in, volta creates a session and stores a session ID in the `__volta_session` [signed cookie](signed-cookie.md). This is an opaque token -- the cookie value is a UUID that maps to a row in the `sessions` table.

### 2. JWT access token (self-contained, in header)

When a downstream app needs to verify the user, volta issues a short-lived JWT (5-minute expiry) signed with [RS256](rs256.md). The JWT carries [claims](claim.md) like `volta_tid` (tenant ID), `volta_roles`, and `volta_display`.

```java
// JwtService.java -- issuing a token
JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .subject(principal.userId().toString())
        .expirationTime(Date.from(now.plusSeconds(config.jwtTtlSeconds())))
        .claim("volta_tid", principal.tenantId().toString())
        .claim("volta_roles", principal.roles())
        .build();
SignedJWT jwt = new SignedJWT(header, claims);
jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
```

### Why two tokens?

- The **session cookie** is revocable (delete the DB row = instant logout) but requires a server round-trip.
- The **JWT** is not revocable but is self-verifiable (no DB needed) and short-lived (5 min max damage).
- Together, they give volta both instant revocation (via session) and stateless verification (via JWT).

---

## Common mistakes and attacks

### Mistake 1: Tokens that never expire

A token with no expiry is a permanent skeleton key. If stolen, the attacker has access forever. Always set an expiry. volta's JWTs expire in 5 minutes; sessions expire after 8 hours of inactivity.

### Mistake 2: Storing tokens in localStorage

`localStorage` is accessible to any JavaScript on the page. An XSS attack can steal all tokens. volta stores session tokens in HttpOnly cookies (invisible to JavaScript) and JWTs are never persisted in the browser.

### Mistake 3: Not validating token claims

Accepting a valid token without checking `iss` (issuer), `aud` (audience), or `exp` (expiry) means accepting tokens from other systems or expired tokens. volta's `JwtService.verify()` checks all three.

### Attack: Token theft

If an attacker obtains a valid token, they can impersonate the user. Defenses: short expiry, HTTPS only, HttpOnly cookies, [token-theft](token-theft.md) detection. See also [session-hijacking](session-hijacking.md).

### Attack: Token forgery

Without proper [verification](verification.md), an attacker can craft fake tokens. JWTs prevent this with [cryptographic signatures](cryptographic-signature.md) -- but only if the verifier checks the signature algorithm and uses the correct [signing key](signing-key.md).

---

## Further reading

- [jwt.md](jwt.md) -- The specific token format volta uses for access tokens
- [session.md](session.md) -- How volta manages session tokens
- [claim.md](claim.md) -- The data carried inside JWT tokens
- [token-theft.md](token-theft.md) -- What happens when tokens are stolen
- [silent-refresh.md](silent-refresh.md) -- How volta-sdk-js renews tokens automatically
