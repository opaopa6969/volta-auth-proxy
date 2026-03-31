# IdP (Identity Provider)

## What is it?

An Identity Provider (IdP) is a service that stores and verifies user identities. When you click "Sign in with Google" on a website, Google is acting as the IdP. The website does not know your password. It trusts Google to confirm that you are who you say you are.

Think of an IdP like a passport office. The passport office verifies your identity (birth certificate, photo, biometrics) and issues you a passport. When you travel internationally, the destination country does not investigate your identity from scratch -- they trust the passport because they trust the issuing authority.

```
  User          Application         IdP (Google)
  |             |                   |
  | "I'm Alice" |                   |
  |------------>|                   |
  |             | "I don't know     |
  |             |  Alice. Let me    |
  |             |  ask Google."     |
  |             |------------------>|
  |             |                   |
  |   Google login page             |
  |<--------------------------------|
  |   Enters credentials            |
  |-------------------------------->|
  |             |                   |
  |             | "Yes, this is     |
  |             |  alice@gmail.com. |
  |             |  Here's proof."   |
  |             |<------------------|
  |             |                   |
  | "Welcome,   |                   |
  |  Alice!"    |                   |
  |<------------|                   |
```

Common Identity Providers include:

| IdP | Used by | Protocol |
|-----|---------|----------|
| Google | Consumer and business apps | OIDC |
| Microsoft Azure AD (Entra ID) | Enterprise, Microsoft 365 | OIDC, SAML |
| Okta | Enterprise | OIDC, SAML |
| Auth0 | Developer-focused apps | OIDC |
| Keycloak | Self-hosted, open source | OIDC, SAML |
| GitHub | Developer tools | OAuth 2.0 |

## Why does it matter?

**Your app does not need to manage passwords.** Passwords are hard to store securely. Data breaches happen. By delegating authentication to an IdP, you avoid storing passwords entirely. If Google's security is good enough for billions of users, it is probably good enough for your app.

**Users get a better experience.** Instead of creating yet another account with yet another password, users click one button and are logged in. They don't need to remember anything new.

**Enterprise requirements.** Large organizations want to manage their employees' access centrally. They use IdPs like Azure AD or Okta to control who can access which applications. If your app does not support their IdP, they cannot use your product.

**Security improves.** IdPs specialize in security: they implement MFA, detect suspicious logins, enforce password policies, and handle account recovery. Your app gets all of this for free.

## How does it work?

The interaction between your application (the "Relying Party" or "Service Provider") and the IdP follows a standard protocol (OIDC or SAML):

```
  1. User tries to access your app
  2. Your app redirects user to the IdP
  3. User authenticates at the IdP (password, MFA, etc.)
  4. IdP creates a signed token/assertion proving the user's identity
  5. IdP redirects user back to your app with the proof
  6. Your app validates the proof and creates a local session
```

### IdP trust model

Your app trusts the IdP based on cryptographic verification:

- **OIDC:** The IdP signs an id_token (JWT) with its private key. Your app verifies the signature using the IdP's public key (from their JWKS endpoint).
- **SAML:** The IdP signs a SAML assertion with its X.509 certificate. Your app verifies the signature using the IdP's public certificate.

Your app never sees the user's password. It only sees the signed proof from the IdP.

### IdP broker pattern

Sometimes an application needs to support multiple IdPs. Instead of integrating with each one directly, you can use an "IdP broker" -- an intermediate service that integrates with multiple IdPs and presents a unified interface to your app.

```
  Without broker:                 With broker:

  App --> Google (OIDC)           App --> Broker --> Google
  App --> Azure AD (SAML)                       --> Azure AD
  App --> Okta (OIDC)                           --> Okta
  App --> GitHub (OAuth)                        --> GitHub

  4 integrations to maintain      1 integration to maintain
  4 different protocols           Broker handles protocol differences
```

## How does volta-auth-proxy use it?

volta-auth-proxy talks to Google as its primary IdP and has a roadmap for multi-IdP support.

**Phase 1 (current): Google as the IdP.**

volta integrates with Google using OIDC:

1. volta redirects the user to Google's authorization endpoint (`https://accounts.google.com/o/oauth2/v2/auth`) with the required parameters (client_id, redirect_uri, scope, state, nonce, PKCE challenge).
2. The user authenticates at Google.
3. Google redirects back to volta's `/callback` endpoint with an authorization code.
4. volta exchanges the code for an id_token at Google's token endpoint.
5. volta verifies the id_token using Google's JWKS (`https://www.googleapis.com/oauth2/v3/certs`).
6. volta extracts the user's identity (email, name, Google subject ID) from the verified id_token.
7. volta creates or updates the user in its own database and establishes a session.

```
  volta-auth-proxy                    Google (IdP)
  +------------------+               +------------------+
  |                  |  1. Auth URL   |                  |
  |  OIDC Service    |--------------->|  Authorization   |
  |                  |                |  Endpoint        |
  |                  |  2. Callback   |                  |
  |  Callback        |<---------------|  Token Endpoint  |
  |  Handler         |                |                  |
  |                  |  3. id_token   |                  |
  |  Token           |<---------------|  JWKS Endpoint   |
  |  Validation      |  4. JWKS      |                  |
  |                  |--------------->|                  |
  |                  |                |                  |
  +------------------+               +------------------+
  |
  | 5. Extract identity
  | 6. Create/update user
  | 7. Create session
  v
  PostgreSQL
```

volta's configuration for Google as IdP:
- `GOOGLE_CLIENT_ID` -- The OAuth client ID from Google Cloud Console
- `GOOGLE_CLIENT_SECRET` -- The OAuth client secret
- `GOOGLE_REDIRECT_URI` -- Where Google sends the user after authentication

**SAML support (foundation laid).**

volta has a basic SAML login endpoint (`/auth/saml/login`) and callback (`/auth/saml/callback`). The current implementation redirects to the SAML entry point with a RelayState parameter. This is the foundation for Phase 2-3 multi-IdP support.

**The IdP broker pattern in volta.**

volta itself acts as an IdP broker for downstream applications. From the perspective of `app-wiki` or `app-admin`, volta IS the IdP. They don't know or care whether the user authenticated via Google, Azure AD, or SAML -- they just receive the `X-Volta-*` headers with verified identity information.

```
  Downstream apps see:          volta handles:

  +--------+                    +--------+     +--------+
  |  Wiki  |--- trusts volta ---| volta  |---->| Google |
  +--------+                    |        |     +--------+
                                |        |
  +--------+                    | (IdP   |     +----------+
  | Admin  |--- trusts volta ---| Broker)|---->| Azure AD |
  +--------+                    |        |     +----------+
                                |        |
  +--------+                    |        |     +--------+
  |  Chat  |--- trusts volta ---|        |---->|  Okta  |
  +--------+                    +--------+     +--------+

  Apps integrate with             volta integrates with
  1 IdP (volta)                   multiple IdPs
```

**Phase 2-3 multi-IdP plan.** volta stores IdP configuration per tenant in the database (via `idp_config` records). Each tenant can configure its own SAML or OIDC provider. When a user from that tenant logs in, volta routes them to the correct IdP. The tenant's IT admin configures the IdP details (issuer URL, certificates, client IDs) through volta's admin interface.

## Common mistakes

**1. Hard-coding a single IdP.**
Building your app to work with only Google (or only Okta) limits your market. Enterprise customers will require their own IdP. Design for multi-IdP support from the start, even if you only implement one initially.

**2. Not validating the IdP's response.**
Just because a token came from the IdP's redirect does not mean it is valid. Always verify the signature, check the issuer, audience, expiration, and nonce. volta performs all of these checks.

**3. Trusting the email claim without checking email_verified.**
Some IdPs allow users to sign up with unverified email addresses. If you trust the email without checking `email_verified`, an attacker could create an account at the IdP with someone else's email and gain access. volta requires `email_verified: true`.

**4. Not handling IdP outages.**
If Google's auth servers are down, users cannot log in. Consider whether your app needs to gracefully degrade (show a maintenance page) or support multiple IdPs as fallback.

**5. Confusing IdP with directory service.**
An IdP authenticates users (proves who they are). A directory service (like Active Directory or LDAP) stores user information and organizational structure. Often they are combined (Azure AD does both), but they are conceptually different.

**6. Not thinking about IdP-to-local-user mapping.**
When a user authenticates via an IdP, you need to map their IdP identity to a local user record. What if they change their email at the IdP? What if they sign in with Google one day and Azure AD another? volta maps users by Google subject ID (`sub` claim) to avoid issues with email changes.
