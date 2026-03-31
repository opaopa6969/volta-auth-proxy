# Backchannel Logout

## What is it?

Backchannel logout is a mechanism where an identity provider (IdP) sends a server-to-server notification to all applications (relying parties) when a user logs out. The word "backchannel" means the notification goes directly between servers, not through the user's browser.

Compare this to "frontchannel" logout, where the user's browser is redirected to each application's logout URL one by one. Backchannel is more reliable because it does not depend on the browser -- if the user closes their browser or loses network connectivity, the logout notifications still reach the applications.

```
  Frontchannel logout:              Backchannel logout:

  User clicks "Logout" at IdP      User clicks "Logout" at IdP

  Browser:                          IdP (server-side):
  1. Redirect to App A /logout      1. POST to App A /backchannel-logout
  2. Redirect to App B /logout      2. POST to App B /backchannel-logout
  3. Redirect to App C /logout      3. POST to App C /backchannel-logout
  4. Show "Logged out" page         4. Show "Logged out" page

  Problem: if browser closes        All apps notified regardless
  at step 2, App C never knows      of what the browser does
```

The backchannel logout notification is typically a signed JWT called a "Logout Token" that the IdP sends via an HTTP POST to a pre-registered URL on each application.

## Why does it matter?

In an SSO (Single Sign-On) environment, a user has sessions at multiple applications. When they log out of one application (or the IdP), the expectation is that they are logged out of ALL applications. Without a mechanism to propagate the logout, the user's sessions at other applications remain active.

This creates security risks:

- **Shared computers.** A user logs out of the IdP but their session at App B is still active. The next person who uses the computer can access App B as the previous user.
- **Compromised accounts.** An admin disables a user's account at the IdP, but the user's existing sessions at various apps keep working until they expire.
- **Compliance.** Some regulations require that when a user's access is revoked, it takes effect immediately across all systems.

## How does it work?

The OIDC Back-Channel Logout specification (OpenID Connect Back-Channel Logout 1.0) defines the protocol:

```
  User            IdP              App A            App B
  |               |                |                |
  | Logout        |                |                |
  |-------------->|                |                |
  |               |                |                |
  |               | POST /backchannel-logout        |
  |               | Body: logout_token (JWT)        |
  |               |--------------->|                |
  |               |                |                |
  |               | POST /backchannel-logout        |
  |               | Body: logout_token (JWT)        |
  |               |------------------------------->|
  |               |                |                |
  |               |                | Invalidate     |
  |               |                | user's session |
  |               |                |                |
  |               |                |    Invalidate  |
  |               |                |    user's      |
  |               |                |    session     |
  |               |                |                |
  | "Logged out"  |                |                |
  |<--------------|                |                |
```

The Logout Token is a JWT that contains:

```json
{
  "iss": "https://idp.example.com",
  "sub": "user-123",
  "aud": "app-a-client-id",
  "iat": 1711900000,
  "jti": "unique-logout-event-id",
  "events": {
    "http://schemas.openid.net/event/backchannel-logout": {}
  },
  "sid": "session-id-at-idp"
}
```

The application must:
1. Verify the JWT signature using the IdP's JWKS.
2. Check that the issuer and audience are valid.
3. Look up the local session associated with the `sub` (user ID) or `sid` (session ID).
4. Invalidate that session.
5. Return HTTP 200 to acknowledge the logout.

## How does volta-auth-proxy use it?

volta-auth-proxy currently does NOT implement backchannel logout, and here is why it does not need to (yet):

**Short-lived JWTs.** volta issues JWTs with a 5-minute TTL (default `JWT_TTL_SECONDS=300`). Even if a user's access should be revoked, any existing JWT becomes useless within 5 minutes. There is no long-lived token that keeps granting access.

**Server-side session with instant revocation.** volta's sessions are stored in PostgreSQL. When a user logs out or an admin revokes access:

```
  User logs out                    Admin revokes access
  |                                |
  v                                v
  store.revokeSession(sessionId)   store.deactivateMembership(userId, tenantId)
  |                                |
  v                                v
  Session.invalidatedAt = now      membership.active = false
  |                                |
  v                                v
  Next request with this cookie:   Next request with this user:
  Session is rejected              Membership check fails
  User must re-authenticate        User must re-authenticate
```

Because every request goes through volta's `/auth/verify` endpoint (ForwardAuth), the session is checked against the database on every request. Revocation takes effect immediately -- on the very next request.

**Single-gateway architecture.** volta is the sole authentication gateway for all downstream apps. Unlike a traditional SSO setup where each app has its own session with the IdP, in volta's architecture:

```
  Traditional SSO:                   volta's architecture:

  IdP session  --> App A session     volta session --> All apps
               --> App B session     (checked per request)
               --> App C session
                                     No per-app sessions to invalidate
  Need backchannel logout to         Revoking volta's session
  invalidate App A, B, C sessions    instantly affects all apps
```

Since downstream apps do not maintain their own sessions with volta (they receive identity headers per request via ForwardAuth), there is nothing to "log out of" at the downstream app level.

### When would volta need backchannel logout?

volta would need to implement backchannel logout in these scenarios:

**1. Downstream apps that cache JWTs.**
If a downstream app receives a JWT via `X-Volta-JWT` and caches it (using it for multiple requests without rechecking with volta), that cached JWT remains valid until it expires. Backchannel logout could notify the app to discard the cached JWT.

**2. Multi-IdP with upstream IdP logout.**
In Phase 2-3, when volta supports multiple IdPs (Google, Azure AD, Okta), those IdPs might send backchannel logout notifications to volta. If a user logs out of Azure AD, Azure AD would POST a logout token to volta, and volta should invalidate the user's session. volta would be a receiver of backchannel logout, not a sender.

**3. Distributed deployment.**
If volta were deployed as multiple instances with independent session stores (not the current design), one instance revoking a session would not affect other instances. Backchannel logout (or a shared session store like Redis) would be needed.

## Common mistakes

**1. Assuming logout at the IdP logs out everywhere.**
Without backchannel logout (or an equivalent mechanism), logging out of the IdP only ends the IdP session. Application sessions may persist. volta avoids this by checking sessions per-request.

**2. Not verifying the logout token.**
The logout token is a JWT that must be verified just like any other token: check the signature, issuer, audience, and that the `events` claim contains the backchannel logout event. An unverified logout token could be used by an attacker to log out arbitrary users.

**3. Relying on frontchannel logout as the only mechanism.**
Frontchannel logout depends on the user's browser loading hidden iframes for each application. It fails if the browser is closed, if JavaScript is blocked, or if there are too many applications. Backchannel is more reliable.

**4. Not implementing a timeout for backchannel calls.**
If one application is slow to respond to the logout notification, it should not delay the logout flow for all other applications. Use short timeouts and fire-and-forget semantics.

**5. Thinking you always need backchannel logout.**
If your architecture checks sessions on every request (like volta's ForwardAuth pattern), session revocation is immediate and backchannel logout adds unnecessary complexity. Evaluate whether your architecture already provides the guarantees you need.

**6. Not handling partial failures.**
In a backchannel logout flow, some applications might be down or unreachable. The IdP should still complete the logout for the applications it can reach and log failures for retry or investigation.
