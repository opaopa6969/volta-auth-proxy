# OIDC Provider

[日本語版はこちら](oidc-provider.ja.md)

---

## What is it?

An OIDC Provider (also called an OpenID Provider, or OP) is a service that verifies a person's identity and issues proof of that verification to other applications.

Think of it like a notary public. When you need to prove who you are for a legal document, you go to a notary. The notary checks your ID, confirms you are who you say you are, and stamps the document as certified. Other parties trust the notary's stamp without needing to verify your identity themselves. An OIDC Provider is the notary of the internet -- it checks your identity (usually via a password or biometric) and gives a signed token that says "yes, this person is who they claim to be."

---

## Why does it matter?

Without OIDC Providers, every application would need to manage its own user database with passwords. This means:

- Every app has to store passwords securely (many get this wrong)
- Users have to create a new account for every service
- Users reuse passwords across sites (a massive security risk)
- Each app is a potential target for password breaches

OIDC Providers solve this by centralizing identity. Google, for example, already knows who you are. Instead of your app asking users for a password, it can ask Google: "Hey, can you confirm this person is real?" Google handles the password (or passkey, or 2FA) and sends back a signed confirmation.

---

## Common OIDC Providers

| Provider | Type | Used by |
|----------|------|---------|
| **Google** | Public (anyone can use) | Consumer apps, SaaS |
| **Microsoft (Azure AD)** | Public + Enterprise | Office 365, corporate apps |
| **Apple** | Public | iOS apps, consumer services |
| **Okta** | Enterprise | Corporate workforce identity |
| **Keycloak** | Self-hosted | Organizations wanting control |
| **Auth0** | Cloud-hosted | Developers wanting convenience |

volta-auth-proxy talks to **Google** as its OIDC Provider. When a user clicks "Sign in with Google" in volta's login page, the following happens:

```
  User clicks                volta-auth-proxy         Google (OIDC Provider)
  "Sign in with Google"
  ─────────────────────►
                             1. Generate state, nonce, PKCE
                             2. Redirect user to Google
                             ─────────────────────────────►

                                                      3. Google shows login page
                                                      4. User enters password / passkey
                                                      5. Google verifies identity
                                                      6. Google redirects back with code

                             ◄─────────────────────────────
                             7. Exchange code for tokens
                             ─────────────────────────────►

                                                      8. Google returns:
                                                         - id_token (who is this person)
                                                         - access_token

                             ◄─────────────────────────────
                             9. Verify id_token signature
                             10. Extract email, name, picture
                             11. Create/update user in DB
                             12. Create session
  ◄─────────────────────
  Logged in!
```

In this flow, Google is the OIDC Provider. volta never sees the user's Google password. volta trusts Google's signed id_token as proof of identity.

---

## Key concepts

### Discovery document

Every OIDC Provider publishes a "discovery document" at a well-known URL. This tells other systems how to talk to the provider:

```
Google's discovery document:
https://accounts.google.com/.well-known/openid-configuration

Contains:
{
  "issuer": "https://accounts.google.com",
  "authorization_endpoint": "https://accounts.google.com/o/oauth2/v2/auth",
  "token_endpoint": "https://oauth2.googleapis.com/token",
  "jwks_uri": "https://www.googleapis.com/oauth2/v3/certs",
  ...
}
```

volta reads Google's discovery document to know where to send users for login and where to exchange authorization codes for tokens.

### id_token

The id_token is a [JWT](jwt.md) that the OIDC Provider signs to say "I verified this person." It contains claims like:

```json
{
  "iss": "https://accounts.google.com",
  "sub": "1234567890",
  "email": "taro@gmail.com",
  "name": "Taro Yamada",
  "picture": "https://...",
  "email_verified": true,
  "exp": 1711900000
}
```

volta verifies this token's signature using Google's public keys (from the `jwks_uri`), then uses the email and name to create or look up the user.

---

## volta as a "consumer" of OIDC Providers

volta is NOT an OIDC Provider itself. volta is an OIDC **consumer** (also called a Relying Party or RP). It trusts Google (the Provider) to verify users, and then volta handles everything else: sessions, multi-tenancy, roles, JWTs for downstream apps.

```
  The identity chain:

  Google (OIDC Provider)
    │ "This person is taro@gmail.com"
    ▼
  volta-auth-proxy (OIDC Consumer / Relying Party)
    │ "taro@gmail.com belongs to ACME Corp tenant, has ADMIN role"
    ▼
  Your app (reads volta headers)
    │ "Show the admin dashboard for ACME Corp"
    ▼
  User sees their dashboard
```

Google certifies identity. volta adds business context (tenant, role). Your app just reads headers.

---

## Can volta work with other OIDC Providers?

volta is built with Google OIDC as the primary provider. The IdP configuration (Phase 4) allows configuring additional OIDC providers and SAML identity providers per tenant, so enterprise customers can use their own corporate identity systems.

---

## Further reading

- [oidc.md](oidc.md) -- The OIDC protocol itself.
- [discovery-document.md](discovery-document.md) -- How OIDC Providers advertise their capabilities.
- [id-token.md](id-token.md) -- The token the OIDC Provider issues.
- [idp.md](idp.md) -- Identity Providers in general (OIDC Providers are one type).
- [pkce.md](pkce.md) -- How volta secures the code exchange with the OIDC Provider.
