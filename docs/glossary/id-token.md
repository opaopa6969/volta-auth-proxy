# id_token (OIDC Identity Token)

## What is it?

An id_token is a special kind of JWT (JSON Web Token) defined by the OIDC (OpenID Connect) standard. It answers one simple question: "Who is this person?" The identity provider (like Google) creates and signs the id_token after the user logs in, and your application receives it as proof of the user's identity.

Think of an id_token like a government-issued ID card. It has your name, your photo, and an official stamp from the government. Anyone who trusts the government can look at the ID card and know who you are. But the ID card does not give you permission to do anything specific -- it just proves your identity.

Here is what an id_token looks like when you decode it:

```
Header:
{
  "alg": "RS256",
  "kid": "google-key-abc123",
  "typ": "JWT"
}

Payload:
{
  "iss": "https://accounts.google.com",
  "sub": "110248495921238986420",
  "aud": "your-google-client-id.apps.googleusercontent.com",
  "exp": 1711900000,
  "iat": 1711896400,
  "nonce": "random-nonce-value",
  "email": "alice@example.com",
  "email_verified": true,
  "name": "Alice Smith",
  "picture": "https://lh3.googleusercontent.com/..."
}

Signature:
(RSA-SHA256 digital signature by Google's private key)
```

The important fields (called "claims") are:

| Claim | Meaning |
|-------|---------|
| `iss` | Issuer -- who created this token (Google) |
| `sub` | Subject -- the unique ID of the user at Google |
| `aud` | Audience -- which application this token was issued for |
| `exp` | Expiration -- when this token stops being valid |
| `iat` | Issued At -- when this token was created |
| `nonce` | A random value your app sent, to prevent replay attacks |
| `email` | The user's email address |
| `email_verified` | Whether Google has confirmed the email is real |
| `name` | The user's display name |

## Why does it matter?

Before OIDC and id_tokens, there was no standard way to get user identity information from an OAuth provider. With plain OAuth 2.0, you would get an access_token and then make a separate API call to a "userinfo" endpoint to find out who the user is. This extra step was slow, unreliable (what if the userinfo endpoint is down?), and inconsistent across providers.

The id_token solves this by putting identity information directly into a signed token that your application can verify locally, without making any additional network calls.

### id_token vs access_token

This is a critical distinction:

| | id_token | access_token |
|---|---|---|
| **Purpose** | Proves who the user is (authentication) | Grants access to resources (authorization) |
| **Audience** | Your application | An API or resource server |
| **Format** | Always a JWT | Can be a JWT or an opaque string |
| **Should you send it to APIs?** | No | Yes |
| **Contains** | User identity claims | Scopes and permissions |
| **Defined by** | OIDC | OAuth 2.0 |

A common analogy: the id_token is your ID card (proves who you are), and the access_token is your hotel room key (grants access to a specific room, regardless of who you are).

## How does it work?

The id_token is part of the OIDC authorization code flow:

```
  Browser          Your App           Google
  |                |                  |
  | Click Login    |                  |
  |--------------->|                  |
  |                |                  |
  | Redirect to    |                  |
  | Google login   |                  |
  |<---------------|                  |
  |                                   |
  | User logs in   |                  |
  |---------------------------------->|
  |                                   |
  | Redirect back with code           |
  |<----------------------------------|
  |                                   |
  | code            |                 |
  |---------------->|                 |
  |                 |                 |
  |                 | Exchange code   |
  |                 | for tokens      |
  |                 |---------------->|
  |                 |                 |
  |                 | Receives:       |
  |                 | - id_token      |
  |                 | - access_token  |
  |                 |<----------------|
  |                 |                 |
  |                 | Validate        |
  |                 | id_token:       |
  |                 | 1. Verify sig   |
  |                 |    (via JWKS)   |
  |                 | 2. Check iss    |
  |                 | 3. Check aud    |
  |                 | 4. Check exp    |
  |                 | 5. Check nonce  |
  |                 |                 |
  | Session created |                 |
  |<----------------|                 |
```

### Validating an id_token

Proper validation includes these checks (in order):

1. **Parse the JWT** and separate the header, payload, and signature.
2. **Verify the signature** using the issuer's public key (fetched from their JWKS endpoint).
3. **Check the algorithm** -- only accept expected algorithms (like RS256). Never accept `none`.
4. **Check the issuer (`iss`)** -- must match the expected identity provider.
5. **Check the audience (`aud`)** -- must contain your application's client ID.
6. **Check the expiration (`exp`)** -- the token must not be expired.
7. **Check the nonce** -- must match the nonce your app sent during the login flow.
8. **Additional checks** -- like `email_verified`, depending on your requirements.

## How does volta-auth-proxy use it?

volta-auth-proxy receives Google's id_token during the OIDC callback flow and validates it thoroughly.

**Token exchange.** After Google redirects the user back with an authorization code, volta exchanges that code for tokens at Google's token endpoint (`https://oauth2.googleapis.com/token`). The response includes an id_token. volta extracts only the id_token -- it does not use Google's access_token at all.

**Validation.** volta uses the Nimbus JOSE+JWT library to verify Google's id_token. The verification includes:

1. **Signature verification** using Google's JWKS endpoint (`https://www.googleapis.com/oauth2/v3/certs`). The library fetches Google's public keys and verifies the RSA-SHA256 signature.
2. **Issuer check** -- accepts both `https://accounts.google.com` and `accounts.google.com` (Google uses both formats).
3. **Audience check** -- the `aud` claim must contain volta's configured Google Client ID.
4. **Expiration check** -- the token must not be expired.
5. **Nonce check** -- the `nonce` claim must match the one volta saved when the login flow started.
6. **Email verification check** -- `email_verified` must be `true`. volta refuses to accept unverified email addresses.

**Identity extraction.** After validation, volta extracts the user's identity from the id_token claims:
- `sub` -- Google's unique user identifier
- `email` -- the user's email address
- `name` -- the user's display name

This information is used to find or create the user in volta's database and establish a session.

**volta's own JWT is NOT an id_token.** volta then issues its own JWT for downstream applications. This volta-issued JWT is technically an access token (it authorizes access to downstream services), not an id_token. It contains volta-specific claims like `volta_tid` (tenant ID), `volta_roles`, and `volta_display`, which are authorization-related.

The full flow:

```
  Google's id_token                volta's JWT
  (identity token)                 (access token for downstream)
  +---------------------+         +------------------------+
  | iss: accounts.google |  --->   | iss: volta-auth         |
  | sub: google-user-id  |  used   | sub: volta-user-uuid    |
  | email: alice@...     |  to     | volta_tid: tenant-uuid  |
  | email_verified: true |  create | volta_roles: [ADMIN]    |
  | nonce: xyz           |         | volta_display: Alice    |
  +---------------------+         +------------------------+
  Proves who Alice is              Authorizes Alice in volta
```

## Common mistakes

**1. Using the id_token as an access token.**
Never send an id_token as a Bearer token to APIs. The id_token is meant for your application to consume, not for APIs. Use an access_token for API calls.

**2. Not validating the id_token at all.**
Some developers trust the id_token just because it came from the token endpoint over HTTPS. But if an attacker compromises your redirect URI handling, they could inject a forged token. Always validate the signature, issuer, audience, expiration, and nonce.

**3. Skipping the audience check.**
Without checking that `aud` matches your client ID, you might accept an id_token that was issued for a completely different application. An attacker could obtain a valid Google id_token for their own app and present it to yours.

**4. Not checking `email_verified`.**
Google can issue id_tokens with unverified email addresses. If you trust the email without checking `email_verified`, an attacker could create a Google account with someone else's email address and use it to log in as them.

**5. Storing id_tokens long-term.**
id_tokens are meant to be validated once at login time and then discarded. They are not meant to be stored and re-validated later. Create a session after validation and use that session going forward.

**6. Confusing Google's id_token with volta's JWT.**
Google's id_token proves identity. volta's JWT proves authorization within the volta ecosystem. They serve different purposes and contain different claims.
