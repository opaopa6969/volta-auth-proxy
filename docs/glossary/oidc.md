# OIDC (OpenID Connect)

[日本語版はこちら](oidc.ja.md)

---

## What is it?

OpenID Connect (OIDC) is a standard way for one service to verify who you are by asking another service that already knows you. When you click "Sign in with Google" on a website, OIDC is the protocol running behind the scenes. It lets the website confirm your identity (your name, email, etc.) without ever seeing your Google password.

Think of it like a passport at an airport. The airport (the website) does not know you personally. But they trust the government (Google) that issued your passport. OIDC is the agreement about what a valid "passport" looks like and how to check it.

---

## Why does it matter?

Without OIDC (or something like it), every website would need to:

1. Build its own username/password system
2. Store passwords securely (most get this wrong)
3. Handle password resets, account lockouts, brute-force protection
4. Ask users to create yet another account they will forget

With OIDC, the website delegates all of that to a trusted provider (Google, Microsoft, etc.). The user gets a familiar, secure login experience. The developer avoids the hardest parts of authentication.

If OIDC did not exist, the internet would be a patchwork of incompatible login systems, and password reuse would be even worse than it already is.

---

## How does it work?

OIDC is built on top of OAuth 2.0 (see [oauth2.md](oauth2.md)). OAuth 2.0 handles **authorization** ("what are you allowed to do?"), while OIDC adds **authentication** ("who are you?"). OIDC adds a special token called the **id_token** -- a signed piece of data that says "this person is jane@example.com, and Google vouches for it."

### The Authorization Code Flow (step by step)

This is the most common and most secure OIDC flow. It is what volta-auth-proxy uses.

```
  You (Browser)              volta-auth-proxy             Google
  ============               ================             ======

  1. Click "Login"
  ──────────────────────►  2. Generate:
                               - state (random, for CSRF)
                               - nonce (random, for replay)
                               - code_verifier + code_challenge (PKCE)
                              Store them in DB

                           3. Redirect browser to Google:
  ◄────── 302 Redirect ──────
       Location: https://accounts.google.com/o/oauth2/v2/auth
               ?response_type=code
               &client_id=YOUR_CLIENT_ID
               &redirect_uri=http://localhost:7070/callback
               &scope=openid email profile
               &state=RANDOM_STATE
               &nonce=RANDOM_NONCE
               &code_challenge=CHALLENGE
               &code_challenge_method=S256

  4. Browser follows redirect
  ──────────────────────────────────────────────────────►
                                                          5. Google shows
                                                             "Choose account"
                                                             page
  6. User picks their
     Google account
  ──────────────────────────────────────────────────────►
                                                          7. Google verifies
                                                             the user

                                                          8. Google redirects
                                                             back with a CODE:
  ◄──────────────────────────────────────────────────────
       Location: http://localhost:7070/callback
               ?code=AUTH_CODE_HERE
               &state=RANDOM_STATE

  9. Browser follows redirect to volta callback
  ──────────────────────►
                          10. volta receives the code + state
                              - Verify state matches what was stored
                              - Exchange code for tokens (server-to-server):

                              POST https://oauth2.googleapis.com/token
                              ──────────────────────────────────────────►
                                body: code=AUTH_CODE
                                      &client_id=YOUR_CLIENT_ID
                                      &client_secret=YOUR_SECRET
                                      &redirect_uri=CALLBACK_URL
                                      &grant_type=authorization_code
                                      &code_verifier=ORIGINAL_VERIFIER

                                                          11. Google verifies:
                                                              - code is valid
                                                              - code_verifier
                                                                matches challenge
                                                              - client_secret
                                                                is correct

                              ◄──────────────────────────────────────────
                                { "id_token": "eyJhbGci...",
                                  "access_token": "ya29..." }

                          12. volta validates the id_token:
                              - Check signature (RS256, Google's public key)
                              - Check issuer = accounts.google.com
                              - Check audience = our client_id
                              - Check nonce matches what was stored
                              - Check email_verified = true
                              - Check not expired

                          13. Extract identity:
                              email: jane@example.com
                              name: Jane Smith
                              google_sub: 1234567890

                          14. Create or find user in DB
                          15. Create session
                          16. Issue volta JWT

  ◄────── Set session cookie + redirect to app ──────
```

That is a lot of steps. But each one serves a purpose:

- **state** prevents an attacker from tricking you into logging in as someone else (CSRF -- see [csrf.md](csrf.md))
- **nonce** prevents an attacker from replaying an old id_token
- **PKCE** (code_verifier/code_challenge) prevents an attacker from intercepting the authorization code (see [pkce.md](pkce.md))
- **The code exchange happens server-to-server**, so the browser never sees the client_secret
- **The id_token is cryptographically signed**, so it cannot be forged

### Relationship with OAuth 2.0

```
  ┌─────────────────────────────────────────────┐
  │              OAuth 2.0                       │
  │                                              │
  │  "What is this user allowed to do?"          │
  │  (authorization)                             │
  │                                              │
  │  Gives you an access_token                   │
  │  to call APIs on behalf of the user          │
  │                                              │
  │  ┌───────────────────────────────────────┐   │
  │  │         OpenID Connect (OIDC)         │   │
  │  │                                       │   │
  │  │  "WHO is this user?"                  │   │
  │  │  (authentication)                     │   │
  │  │                                       │   │
  │  │  Adds id_token on top of OAuth 2.0    │   │
  │  │  with identity claims (email, name)   │   │
  │  └───────────────────────────────────────┘   │
  └─────────────────────────────────────────────┘
```

OAuth 2.0 alone does not tell you WHO the user is. It only gives you permission to do things. OIDC adds an identity layer on top, using the id_token (a JWT -- see [jwt.md](jwt.md)).

---

## How does volta-auth-proxy use it?

volta-auth-proxy connects **directly** to Google's OIDC endpoints. There is no Keycloak, no Auth0, no oauth2-proxy sitting in between.

### Why direct instead of Keycloak?

| Approach | What happens |
|----------|-------------|
| Keycloak as IdP broker | Browser -> Keycloak -> Google -> Keycloak -> your app. Two extra hops. 512MB+ RAM. 30-second startup. Hundreds of config options. FreeMarker theme hell. |
| volta direct to Google | Browser -> volta -> Google -> volta -> your app. Minimal hops. ~30MB RAM. ~200ms startup. One `.env` file. Full control over every step. |

volta takes the direct approach because:

1. **We only need Google OIDC in Phase 1.** Keycloak's value is federating many IdPs, but that is overkill when you have one.
2. **We want full control over the login UI.** volta uses jte templates -- regular HTML that you can style however you want. Keycloak forces you into FreeMarker themes.
3. **We want to understand every line.** Auth is the most security-critical part of any SaaS. Using a black box you do not fully understand is a risk.

### volta's OIDC implementation

The code lives in `OidcService.java`. Here is what it does:

1. `createAuthorizationUrl()` -- Generates state, nonce, and PKCE values. Stores them in the `oidc_flows` database table. Returns the Google authorization URL with all parameters.

2. `exchangeAndValidate()` -- Called when Google redirects back. Looks up the stored flow by state. Exchanges the authorization code for an id_token (server-to-server). Validates the id_token signature, issuer, audience, nonce, and email_verified.

3. The validated identity (`OidcIdentity` record) is then passed to `AuthService` which creates the user/session and issues a volta JWT.

### Security measures volta applies

| Measure | Purpose |
|---------|---------|
| `state` parameter | Prevent CSRF during login |
| `nonce` in id_token | Prevent replay attacks |
| PKCE (S256) | Prevent authorization code interception |
| `prompt=select_account` | Always show account picker (prevent silent login to wrong account) |
| 10-minute flow expiry | Prevent stale authorization attempts |
| Single-use state | Each state can only be consumed once |
| Server-side token exchange | client_secret never reaches the browser |
| Google JWKS verification | id_token signature verified against Google's public keys |

---

## Common mistakes and attacks

### Mistake 1: Not validating the id_token

Some developers receive the id_token and just decode it without checking the signature. An attacker can craft a fake id_token with any email they want. **Always verify the signature against the provider's JWKS endpoint.**

### Mistake 2: Skipping the state parameter

Without state, an attacker can perform a "login CSRF" attack. They start the OIDC flow with their own account, then trick you into completing it. You end up logged in as the attacker, potentially exposing your data. See [csrf.md](csrf.md) for details.

### Mistake 3: Ignoring the nonce

Without nonce verification, an attacker who captures a valid id_token can replay it later to impersonate the original user.

### Mistake 4: Not checking email_verified

Google accounts can have unverified email addresses. If you skip this check, someone could create a Google account with your email and impersonate you.

### Attack: Token substitution

An attacker intercepts the authorization code (possible on mobile without PKCE) and exchanges it for an id_token before you do. PKCE prevents this -- see [pkce.md](pkce.md).

---

## Further reading

- [OpenID Connect Core 1.0 Specification](https://openid.net/specs/openid-connect-core-1_0.html) -- The official spec. Dense but definitive.
- [Google's OpenID Connect documentation](https://developers.google.com/identity/openid-connect/openid-connect) -- Google-specific details.
- [OAuth 2.0 Simplified](https://www.oauth.com/) by Aaron Parecki -- The most approachable guide to OAuth/OIDC.
- [jwt.md](jwt.md) -- How the id_token (a JWT) works internally.
- [pkce.md](pkce.md) -- Why PKCE is essential for the authorization code flow.
- [csrf.md](csrf.md) -- Why the state parameter matters.
