# Access Token (OAuth 2.0)

## What is it?

An access token is a credential that represents authorization -- it grants the holder permission to access specific resources or perform specific actions. When you log in to a service and that service gives you a token to use with its API, that token is an access token.

Think of it like a conference badge. At the registration desk, you prove who you are (show your ID). They give you a badge. From then on, you show the badge to enter sessions, access the exhibition hall, and get lunch. The badge is your access token. You don't show your ID at every door -- you show the badge.

Access tokens come in two forms:

**Opaque tokens** look like a random string (e.g., `ya29.a0AfH6SMA...`). They carry no readable information. To find out what they mean, you have to send them to the issuer's introspection endpoint, which is like calling the registration desk to ask "Is badge #4523 valid?"

**JWT-based tokens** are structured and self-contained. They carry information (claims) inside them, signed by the issuer. Anyone with the issuer's public key can read and verify them without making a network call. This is like a badge with your name, role, and permissions printed on it, stamped with a holographic seal.

```
  Opaque token:           JWT access token:
  +-----------------+     +---------------------------+
  | ya29.a0AfH6SMA  |     | Header: {alg, kid}        |
  | (meaningless to |     | Payload: {sub, iss, aud,  |
  |  the reader)    |     |   exp, roles, tenant}     |
  |                 |     | Signature: (RSA-SHA256)   |
  +-----------------+     +---------------------------+
  Must call issuer        Can be verified locally
  to validate             using public key (JWKS)
```

## Why does it matter?

Access tokens are the backbone of API security. Without them, every API call would need to include a username and password, which would be:

- **Insecure** -- passwords would be transmitted constantly and stored in more places.
- **Inflexible** -- you could not limit what an application can do (scoping).
- **Irrevocable** -- you could not cut off access without changing the password.

Access tokens solve these problems:

1. **Limited lifetime.** A token expires after a set period (minutes, hours, or days). Even if stolen, the damage is limited.
2. **Scoped permissions.** A token can be restricted to specific actions (e.g., "read emails" but not "delete emails").
3. **Revocable.** You can invalidate a token without changing the user's password.
4. **Delegated.** A third-party application can get a token on behalf of the user without ever seeing the user's password.

## How does it work?

In a typical OAuth 2.0 flow:

```
  User          App           Auth Server       API
  |             |             |                  |
  | Login       |             |                  |
  |------------>|             |                  |
  |             | Auth request|                  |
  |             |------------>|                  |
  |             |             |                  |
  | Enter creds |             |                  |
  |------------>|------------>|                  |
  |             |             |                  |
  |             | access_token|                  |
  |             |<------------|                  |
  |             |             |                  |
  |             | API call with                  |
  |             | Authorization: Bearer <token>  |
  |             |-------------------------------->|
  |             |             |                  |
  |             |             |    Verify token  |
  |             |             |    Check scopes  |
  |             |             |                  |
  |             | Response    |                  |
  |             |<--------------------------------|
```

The API server verifies the token in one of two ways:
- **Opaque tokens:** Call the auth server's introspection endpoint.
- **JWT tokens:** Verify the signature locally using the auth server's public keys (JWKS).

### Lifetime considerations

The lifetime of an access token is a trade-off:

| Short lifetime (minutes) | Long lifetime (hours/days) |
|---|---|
| Less damage if stolen | Fewer authentication round-trips |
| Requires frequent refresh | Better user experience |
| More secure | Higher risk if stolen |

A common pattern is short-lived access tokens (5-15 minutes) paired with longer-lived refresh tokens. The access token is used for API calls, and when it expires, the refresh token is used to get a new one without requiring the user to log in again.

## How does volta-auth-proxy use it?

volta-auth-proxy uses JWT-based access tokens for its internal ecosystem.

**volta issues its own JWTs.** When a user authenticates (via Google OIDC), volta creates a session and can issue a JWT access token. This JWT is signed with volta's RSA private key using RS256, and downstream applications verify it using volta's JWKS endpoint at `/.well-known/jwks.json`.

A volta JWT access token contains:

```json
{
  "iss": "volta-auth",
  "aud": ["volta-apps"],
  "sub": "user-uuid",
  "exp": 1711896700,
  "iat": 1711896400,
  "jti": "unique-token-id",
  "volta_v": 1,
  "volta_tid": "tenant-uuid",
  "volta_roles": ["ADMIN"],
  "volta_display": "Alice Smith",
  "volta_tname": "Acme Corp",
  "volta_tslug": "acme"
}
```

Key characteristics:

- **Short-lived.** Default TTL is 300 seconds (5 minutes), configured via `JWT_TTL_SECONDS`.
- **Contains authorization data.** Tenant ID, roles, and display name are embedded, so downstream apps don't need to make additional calls to volta.
- **Unique per issuance.** Each token has a unique `jti` (JWT ID) for audit trailing.
- **Schema versioned.** The `volta_v` claim tracks the token schema version.

**Two paths for token delivery:**

1. **ForwardAuth path.** When Traefik calls volta's `/auth/verify` endpoint, volta validates the user's session cookie and issues a fresh JWT. This JWT is passed to the downstream application via the `X-Volta-JWT` response header. The browser never sees the JWT -- it travels server-to-server.

2. **Direct API path.** Applications can call `POST /auth/refresh` with a valid session cookie to receive a JWT in the JSON response. The volta-sdk-js (browser SDK) uses this to get tokens for direct API calls.

```
  ForwardAuth path:                    Direct API path:

  Browser -> Traefik -> volta          Browser -> volta
                         |                         |
                   /auth/verify             /auth/refresh
                         |                         |
                   Session cookie            Session cookie
                   validated                 validated
                         |                         |
                   JWT issued in             JWT returned
                   X-Volta-JWT header        in JSON body
                         |                         |
                   Downstream app            Browser uses
                   receives JWT              JWT for API calls
```

**Machine-to-machine tokens.** volta also issues M2M (machine-to-machine) access tokens for server-to-server communication. These tokens include `volta_client: true` and `volta_client_id` claims to distinguish them from user tokens.

## Common mistakes

**1. Using long-lived access tokens.**
A token that lives for days or weeks creates a wide window of vulnerability if stolen. Keep access tokens short-lived (minutes) and use refresh tokens or session cookies for renewal.

**2. Storing access tokens in localStorage.**
localStorage is accessible to any JavaScript on the page. If an XSS vulnerability exists, the token can be stolen. volta avoids this by using HttpOnly session cookies in the browser and only issuing JWTs server-to-server.

**3. Not validating tokens on the API side.**
Some developers trust tokens blindly. Always verify the signature, check the issuer, audience, and expiration. volta's `JwtService.verify()` checks all of these.

**4. Putting sensitive data in JWT claims.**
JWTs are signed but not encrypted. Anyone who has the token can decode the payload. Don't put passwords, credit card numbers, or other secrets in JWT claims. volta's JWT contains only identity and authorization claims.

**5. Confusing access tokens with id_tokens.**
They serve different purposes. The id_token proves identity (for your app). The access token grants access (for APIs). See the id_token article for details.

**6. Not including an audience claim.**
Without an `aud` claim, a token could be used at any API. This is dangerous if multiple services trust the same issuer. volta always includes the audience `volta-apps` and checks it during verification.
