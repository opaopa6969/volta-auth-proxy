# Authorization Code Flow

[Japanese / 日本語](authorization-code-flow.ja.md)

---

## What is it?

The Authorization Code Flow is the most common and most secure way for a web application to log users in via OIDC/OAuth 2.0. The user is redirected to the identity provider (e.g., Google), authenticates there, and is sent back with a short-lived authorization **code**. The application then exchanges this code for tokens in a direct server-to-server call. The critical design principle: the "code" travels through the browser, but the "token" never does.

---

## Why does it matter?

The browser is a hostile environment. URLs appear in browser history, referrer headers, proxy logs, and browser extensions can read them. If tokens were sent directly through the browser (as in the older Implicit Flow), they could be intercepted. The Authorization Code Flow solves this by splitting the process in two:

1. **Front channel** (browser): Only the short-lived, single-use code passes through.
2. **Back channel** (server-to-server): The real tokens are exchanged privately, with the client secret authenticating the application.

Even if an attacker intercepts the code, they cannot exchange it without the client secret (and with PKCE, they also need the code verifier).

---

## Simple example

```
Browser              volta-auth-proxy            Google
  |                       |                         |
  |--- GET /login ------->|                         |
  |                       |-- generate state,       |
  |                       |   nonce, PKCE verifier  |
  |<-- 302 redirect ------|                         |
  |                       |                         |
  |--- follow redirect -------------------------------->|
  |                       |                     show login
  |                       |                     user signs in
  |<-------------------------------- 302 + ?code=ABC&state=XYZ
  |                       |                         |
  |--- GET /callback?code=ABC&state=XYZ -->|        |
  |                       |                         |
  |                       |--- POST /token -------->|
  |                       |    (code + secret       |
  |                       |     + code_verifier)    |
  |                       |<-- { id_token, ... } ---|
  |                       |                         |
  |                       |-- validate id_token     |
  |                       |-- create session        |
  |<-- Set-Cookie + redirect to app                 |
```

The code `ABC` is useless alone. It can only be exchanged once, expires quickly, and requires the client secret + PKCE verifier.

---

## In volta-auth-proxy

volta implements the Authorization Code Flow in `OidcService`:

**Step 1 -- Start the flow** (`createAuthorizationUrl()`):
- Generates `state` (CSRF protection), `nonce` (replay protection), and PKCE `code_verifier`/`code_challenge`.
- Stores all of these in the `oidc_flows` database table with a 10-minute expiry.
- Returns a redirect URL to Google with `scope=openid email profile`.

**Step 2 -- Handle the callback** (`exchangeAndValidate()`):
- Looks up the stored flow by `state` (single-use: it is consumed immediately).
- Exchanges the code for an `id_token` via a server-to-server POST to Google's token endpoint, including the `code_verifier`.
- Validates the `id_token`: signature (RS256 via Google's JWKS), issuer, audience, nonce, expiry, and `email_verified`.

**Why the code is safe to pass through the browser**:
- It is single-use (Google invalidates it after one exchange).
- It expires in minutes.
- PKCE (`code_challenge_method=S256`) ensures only the party that started the flow can complete it.
- The `client_secret` is required for the exchange and never leaves the server.

See also: [redirect-uri.md](redirect-uri.md), [scopes.md](scopes.md)
