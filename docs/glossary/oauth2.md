# OAuth 2.0

[日本語版はこちら](oauth2.ja.md)

---

## What is it?

OAuth 2.0 is a standard protocol for authorization -- it lets one application access resources on behalf of a user, without the user sharing their password. When a website says "Allow this app to access your Google Drive?" that is OAuth 2.0 at work.

Think of it like a valet key for your car. A valet key lets the parking attendant drive your car (limited access), but not open the trunk or glove box (full access). You are granting specific, limited permissions without handing over your master key (password).

Important distinction: OAuth 2.0 handles **authorization** ("what are you allowed to do?"), not **authentication** ("who are you?"). Authentication is handled by OpenID Connect (OIDC), which is built on top of OAuth 2.0. See [oidc.md](oidc.md).

---

## Why does it matter?

Before OAuth, if an app wanted to access your data on another service, you had to give the app your username and password for that service. This was terrible:

- The app could do anything with your credentials (not just what you approved)
- You could not revoke the app's access without changing your password
- If the app was hacked, your password was exposed
- You had to trust every third-party app with your most sensitive credential

OAuth solved this by introducing token-based delegated authorization. The user approves specific permissions, the app gets a limited token, and the user can revoke it at any time.

---

## How does it work?

### The four grant types

OAuth 2.0 defines several "grant types" -- different flows for different situations:

| Grant Type | Used by | How it works |
|-----------|---------|-------------|
| **Authorization Code** | Web apps, SPAs | User approves in browser, app gets a code, exchanges for token. Most secure for user-facing apps. |
| **Client Credentials** | Server-to-server | No user involved. The app authenticates with its own credentials. See [client-credentials.md](client-credentials.md). |
| **Device Code** | TVs, CLI tools | Device shows a code, user enters it on another device. |
| **Refresh Token** | Any client with a refresh token | Exchange a refresh token for a new access token. |

(Note: The "Implicit" and "Resource Owner Password Credentials" grant types are deprecated and should not be used.)

### Authorization Code Flow (for browsers)

This is the most common flow and the one volta uses (via OIDC). See [oidc.md](oidc.md) for the full step-by-step diagram.

```
  Browser ──► "Login with Google" ──► Google ──► "Allow?" ──► Yes
                                                                │
    Google gives a CODE to the browser (in the URL)             │
    Browser sends CODE to your server                           │
    Your server exchanges CODE for tokens (server-to-server)    │
    Your server gets: access_token + id_token                   │
                                                                │
  ◄─────────────────────── Logged in! ──────────────────────────┘
```

Key points:
- The **authorization code** is short-lived and single-use
- The code exchange happens **server-to-server** (the browser never sees the access token in the URL)
- **PKCE** should be used to protect the code from interception (see [pkce.md](pkce.md))

### Client Credentials Flow (for servers)

This flow is for machine-to-machine (M2M) communication. No user is involved. See [client-credentials.md](client-credentials.md) for a full explanation.

```
  Service A ──► "Here are my client_id and client_secret"
            ──► Auth server
            ◄── "Here is your access_token"

  Service A ──► "Bearer <access_token>"
            ──► Service B
            ◄── Response
```

### Tokens in OAuth 2.0

| Token | Purpose | Lifetime | volta equivalent |
|-------|---------|----------|-----------------|
| **Access Token** | Authorize API calls | Short (minutes to hours) | volta JWT (5 min) |
| **Refresh Token** | Get a new access token | Long (hours to days) | volta session cookie (8 hr) |
| **ID Token** | Prove identity (OIDC only) | Short | Google's id_token, verified by volta |

volta does not use OAuth 2.0 access tokens directly. Instead, it verifies the Google id_token (OIDC) and issues its own JWT. The volta JWT serves a similar purpose to an access token but is self-contained and project-specific.

---

## How does volta-auth-proxy use OAuth 2.0?

### As an OAuth client (Phase 1)

volta acts as an OAuth 2.0 / OIDC **client** to Google:

```
  volta-auth-proxy                          Google
  (OAuth Client)                            (OAuth Provider / IdP)

  1. Redirect user to Google's auth endpoint
  2. User authenticates with Google
  3. Google redirects back with authorization code
  4. volta exchanges code for id_token (server-to-server)
  5. volta verifies id_token
  6. volta creates its own session and JWT
```

volta does not act as an OAuth provider in Phase 1. Apps do not perform OAuth flows against volta. Instead, apps use ForwardAuth headers or the Internal API.

### As an OAuth provider (Phase 2 - planned)

In Phase 2, volta plans to implement the Client Credentials grant type for M2M authentication. This will allow backend services to authenticate directly with volta:

```
  Backend Service                         volta-auth-proxy
  (OAuth Client)                          (OAuth Provider)

  1. POST /oauth/token
     grant_type=client_credentials
     &client_id=my-service
     &client_secret=secret123
     &scope=read:members

  2. volta verifies client credentials
  3. volta issues a JWT with M2M claims

  4. Service uses JWT to call other services or volta's API
```

### Phase 1: Static service token (bridge solution)

Until the Client Credentials grant is implemented, volta provides a simpler M2M mechanism:

```
  Backend Service                         volta-auth-proxy

  1. Send request with:
     Authorization: Bearer volta-service:<VOLTA_SERVICE_TOKEN>

  2. volta verifies the static token
  3. Returns a service principal (not a user principal)
```

This is configured via the `VOLTA_SERVICE_TOKEN` environment variable. It is simple but has limitations:
- One token for all services (no per-service scoping)
- No token expiry (revocation requires redeployment)
- No audit trail per service

The Client Credentials grant (Phase 2) solves all of these.

---

## Common mistakes and attacks

### Mistake 1: Using the Implicit grant

The Implicit grant (where the access token is returned directly in the URL fragment) is deprecated. It exposes the token in browser history, referrer headers, and logs. Use Authorization Code + PKCE instead.

### Mistake 2: Not validating the redirect_uri

If the OAuth provider does not strictly validate the `redirect_uri`, an attacker can redirect the authorization code to their own server. Always register exact redirect URIs and match them strictly.

### Mistake 3: Using access tokens as identity proof

An OAuth access token says "this token has permission to do X." It does NOT say "the holder of this token is user Y." For identity, use OIDC's id_token. Trusting access tokens for identity leads to confused deputy attacks.

### Mistake 4: Long-lived access tokens

If an access token has a 24-hour lifetime and is stolen, the attacker has 24 hours of access. Use short-lived tokens (volta: 5 minutes) with refresh capability.

### Attack: Authorization code interception

Without PKCE, an attacker can intercept the authorization code and exchange it for tokens. See [pkce.md](pkce.md).

### Attack: Open redirect via redirect_uri

If the OAuth provider allows wildcards or partial matching in redirect URIs, an attacker can craft a URL that redirects the authorization code to their server. volta uses exact redirect URI matching.

---

## Further reading

- [RFC 6749 - The OAuth 2.0 Authorization Framework](https://tools.ietf.org/html/rfc6749) -- The official OAuth 2.0 specification.
- [OAuth 2.0 Simplified](https://www.oauth.com/) by Aaron Parecki -- The best practical guide.
- [oidc.md](oidc.md) -- How OIDC adds authentication to OAuth 2.0.
- [client-credentials.md](client-credentials.md) -- The M2M grant type.
- [pkce.md](pkce.md) -- Protecting the authorization code flow.
