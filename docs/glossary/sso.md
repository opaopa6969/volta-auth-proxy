# SSO (Single Sign-On)

## What is it?

Single Sign-On (SSO) means that a user logs in once and then gets access to multiple applications without having to log in again for each one. One login, many apps.

If you have ever logged into Gmail and then opened Google Drive, YouTube, and Google Calendar without being asked for your password again -- that is SSO. Your single Google login gave you access to all of Google's services.

```
  Without SSO:                    With SSO:

  Wiki  --> Login                 Wiki  --> Already logged in!
  Admin --> Login again           Admin --> Already logged in!
  Chat  --> Login again           Chat  --> Already logged in!
  CRM   --> Login again           CRM   --> Already logged in!

  4 passwords to remember         1 login, 4 apps accessible
  4 login forms to fill           1 login form total
```

## Why does it matter?

**For users:**
- Less friction. One login, access everywhere.
- Fewer passwords to manage (fewer to forget, fewer to reuse).
- Better experience, especially in organizations with many internal tools.

**For security:**
- Centralized authentication means one place to enforce strong passwords, MFA, and account policies.
- When an employee leaves, disabling one account cuts access to everything.
- Fewer passwords means less password reuse, which means fewer accounts compromised by data breaches.

**For IT teams:**
- One identity system to maintain instead of many.
- Easier compliance (who has access to what is tracked in one place).
- Simpler onboarding and offboarding.

## How does it work?

SSO relies on a central authentication service (the Identity Provider, or IdP) that all applications trust. The two main patterns are:

### IdP-initiated SSO

The user starts at the IdP (e.g., an Okta dashboard or Google Workspace launcher). They click on an app icon, and the IdP sends a signed assertion directly to the application.

```
  User            IdP (Okta)          App (Wiki)
  |               |                   |
  | Logged into   |                   |
  | Okta portal   |                   |
  |               |                   |
  | Click "Wiki"  |                   |
  |-------------->|                   |
  |               |                   |
  |               | Send SAML         |
  |               | assertion to Wiki |
  |               |------------------>|
  |               |                   |
  |               |        Wiki sees  |
  |               |        valid      |
  |               |        assertion  |
  |               |                   |
  |   Redirected to Wiki, logged in   |
  |<----------------------------------|
```

### SP-initiated SSO (more common)

The user goes directly to the application (the Service Provider, or SP). The application redirects the user to the IdP for authentication. After login, the IdP redirects back to the application with proof of identity.

```
  User            App (Wiki)          IdP (Google)
  |               |                   |
  | Visit wiki    |                   |
  |-------------->|                   |
  |               |                   |
  |               | Not logged in.    |
  |               | Redirect to IdP   |
  |               |                   |
  | Redirect to Google login          |
  |<--------------|                   |
  |---------------------------------->|
  |               |                   |
  | Already logged|in at Google?      |
  | Yes! (session |exists)            |
  |               |                   |
  | Redirect back |with proof         |
  |<----------------------------------|
  |-------------->|                   |
  |               | Valid! Create     |
  |               | local session     |
  |               |                   |
  | Wiki is now   |                   |
  | accessible    |                   |
  |<--------------|                   |
```

The "magic" of SSO is in the second step: "Already logged in at Google? Yes!" Because the user already has a session at the IdP from logging into a previous app, the IdP does not ask for credentials again. It just issues a new proof of identity and redirects back.

### Protocols

- **SAML 2.0** -- The enterprise standard. XML-based. Used by Okta, Azure AD, ADFS.
- **OIDC** -- The modern web standard. JSON/JWT-based. Used by Google, Auth0, Keycloak.
- **ForwardAuth** -- A reverse proxy pattern. Traefik or Nginx calls an auth endpoint before forwarding requests.

## How does volta-auth-proxy use it?

volta-auth-proxy achieves SSO across multiple downstream applications using the **ForwardAuth** pattern with Traefik.

**The architecture:**

```
  Browser
  |
  | Request to wiki.example.com/page
  |
  v
  Traefik (reverse proxy)
  |
  | Before forwarding, call volta's /auth/verify
  |
  v
  volta-auth-proxy
  |
  | Check session cookie (__volta_session)
  | Session valid? Issue JWT, return 200 + headers
  | Session invalid? Return 401
  |
  v
  Traefik
  |
  | 200? Forward request + X-Volta-* headers to Wiki
  | 401? Redirect user to volta login page
  |
  v
  Wiki app receives request with identity headers
```

**How SSO works across apps:**

1. User visits `wiki.example.com`. Traefik checks with volta. No session. User is redirected to volta's login page.
2. User logs in via Google OIDC. volta creates a session and sets the `__volta_session` cookie.
3. User is redirected back to wiki. Traefik checks with volta. Session is valid. Wiki loads.
4. User visits `admin.example.com`. Traefik checks with volta. **Same session cookie is sent** (because all apps are behind the same Traefik instance and the cookie's domain covers them). Session is valid. Admin panel loads.

No second login required. That is SSO.

```
  Time --->

  Visit Wiki   --> volta: no session --> Login with Google
                                          |
                                          v
                                     Session created
                                     Cookie: __volta_session=UUID
                                          |
  Wiki loads  <-- volta: session valid <---+
                                          |
  Visit Admin --> volta: session valid <---+  (same cookie!)
                                          |
  Admin loads <-- volta: session valid <---+
                                          |
  Visit Chat  --> volta: session valid <---+  (still the same cookie!)
                                          |
  Chat loads  <-- volta: session valid <---+
```

**Role-based access control within SSO.** Even though all apps share one login, volta can restrict access based on roles. The `volta-config.yaml` file defines which roles can access which apps:

```yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
  - id: app-admin
    url: https://admin.example.com
    allowed_roles: [ADMIN, OWNER]
```

A user with the MEMBER role gets SSO into wiki but is blocked from admin. The `/auth/verify` endpoint checks the user's roles against the app's `allowed_roles` before returning 200.

**Identity information passed to apps.** volta passes identity data to downstream applications via headers:
- `X-Volta-User-Id` -- the user's UUID
- `X-Volta-Email` -- the user's email
- `X-Volta-Tenant-Id` -- the current tenant
- `X-Volta-Roles` -- the user's roles
- `X-Volta-JWT` -- a short-lived JWT for API calls

Downstream apps do not need their own login systems. They receive identity from volta on every request.

## Common mistakes

**1. Implementing separate login for each app.**
If each app has its own user database and login form, you don't have SSO -- you have "same password for everything," which is worse (one breach exposes all apps).

**2. Not centralizing session management.**
If SSO sessions are managed per-app rather than centrally, logging out of one app doesn't log you out of others. volta centralizes sessions in one database. Revoking a session immediately affects all apps.

**3. Giving all SSO users the same permissions.**
SSO means one login, not one permission level. Always combine SSO with role-based access control. volta's per-app `allowed_roles` configuration handles this.

**4. Forgetting about SSO logout.**
If a user logs out of one app but is still logged into others, that is confusing and potentially insecure. volta's logout revokes the central session, which affects all apps on the next request.

**5. Not validating the identity information from the SSO provider.**
When an app receives identity headers from a reverse proxy, it should only trust them if the request actually came through the proxy. If the app is directly accessible (bypassing Traefik), an attacker could forge the headers. Ensure apps are only accessible behind the reverse proxy.

**6. Assuming SSO eliminates the need for MFA.**
SSO makes login more convenient, which is great. But it also means that one compromised set of credentials gives access to everything. SSO should always be paired with multi-factor authentication to compensate for this increased blast radius.
