# Refresh Token

## What is it?

A refresh token is a long-lived credential used to obtain new access tokens without requiring the user to log in again. When an access token expires, instead of sending the user back to the login page, your application presents the refresh token to the auth server and receives a fresh access token.

Think of it like a membership card at a gym. Your day pass (access token) gets you through the door today. When it expires, you show your membership card (refresh token) at the front desk, and they give you a new day pass. You don't have to re-register as a member every time.

```
  Time --->

  [  access_token (5 min)  ]  EXPIRED
                                |
                                | Use refresh_token
                                v
  [  new access_token (5 min)  ]  EXPIRED
                                    |
                                    | Use refresh_token
                                    v
  [  new access_token (5 min)  ]
```

## Why does it matter?

Access tokens are intentionally short-lived to limit damage if stolen. But short-lived tokens create a problem: if your access token expires every 5 minutes, the user would have to log in again every 5 minutes. That's a terrible experience.

Refresh tokens solve this by separating two concerns:

| Access Token | Refresh Token |
|---|---|
| Used with every API call | Used only when refreshing |
| Short-lived (minutes) | Long-lived (hours, days, or weeks) |
| Sent to many services | Sent only to the auth server |
| Higher exposure | Lower exposure |
| If stolen: limited window | If stolen: longer window, but harder to steal |

Because the refresh token is only sent to the auth server (not to every API), it is exposed to fewer attack surfaces. And because the access token expires quickly, even if intercepted at an API, the damage window is small.

### Token rotation

Modern best practice is **refresh token rotation**: every time a refresh token is used, the auth server issues both a new access token AND a new refresh token, invalidating the old refresh token.

```
  Refresh token rotation:

  refresh_token_A  -->  auth server  -->  access_token_2
                                     -->  refresh_token_B (A is now invalid)

  refresh_token_B  -->  auth server  -->  access_token_3
                                     -->  refresh_token_C (B is now invalid)

  If attacker steals refresh_token_A and tries to use it:
  auth server sees it was already used --> REVOKE EVERYTHING
```

If a stolen refresh token is replayed, the auth server detects the reuse (because the token was already consumed) and can revoke the entire token family -- logging the user out as a safety measure.

## How does it work?

The standard refresh flow:

```
  App                    Auth Server                 API
  |                      |                           |
  | API call with        |                           |
  | access_token         |                           |
  |----------------------------------------------------->
  |                      |                           |
  |                      |      401 Token Expired    |
  |<-----------------------------------------------------|
  |                      |                           |
  | POST /token          |                           |
  | grant_type=          |                           |
  |   refresh_token      |                           |
  | refresh_token=       |                           |
  |   <old_refresh>      |                           |
  |--------------------->|                           |
  |                      |                           |
  | {                    |                           |
  |   access_token: new  |                           |
  |   refresh_token: new |  (rotation)               |
  | }                    |                           |
  |<---------------------|                           |
  |                      |                           |
  | Retry API call with  |                           |
  | new access_token     |                           |
  |----------------------------------------------------->
  |                      |                           |
  |                      |      200 OK               |
  |<-----------------------------------------------------|
```

### Where to store refresh tokens

Refresh tokens need secure storage because they are long-lived and powerful:

- **Server-side applications:** Store in the backend. The browser never sees it.
- **Single-page apps (SPA):** This is the hard part. localStorage is vulnerable to XSS. HttpOnly cookies are the safest option.
- **Mobile apps:** Store in the platform's secure storage (Keychain on iOS, Keystore on Android).

## How does volta-auth-proxy use it?

volta-auth-proxy does NOT use refresh tokens. Instead, it uses a different pattern that achieves the same goal: **server-side sessions with short-lived JWTs.**

Here is why, and how it works:

**The problem refresh tokens solve:** Keep the user logged in without re-authentication while keeping access tokens short-lived.

**volta's alternative solution:**

```
  Traditional approach:           volta's approach:

  Browser stores:                 Browser stores:
  - access_token (short)          - session cookie (HttpOnly)
  - refresh_token (long)
                                  Server stores:
                                  - session record in PostgreSQL
                                  - (user, tenant, expiry, etc.)

  When access_token expires:      When JWT expires:
  Send refresh_token to           Send session cookie to
  auth server, get new            /auth/verify or /auth/refresh
  access_token                    volta validates session,
                                  issues fresh JWT
```

The key insight: volta's session cookie is functionally equivalent to a refresh token, but with several advantages:

1. **HttpOnly and SameSite.** The session cookie has `HttpOnly` and `SameSite=Lax` flags, making it inaccessible to JavaScript and resistant to cross-site attacks. Refresh tokens stored in localStorage would not have these protections.

2. **Server-side state.** The session ID is a meaningless UUID. All session data (who the user is, which tenant, when it expires, whether it has been revoked) lives in the database. This means volta can:
   - Revoke a session instantly (database update)
   - Track all active sessions per user
   - Enforce a maximum concurrent session limit (volta limits to 5)
   - Detect suspicious session usage patterns

3. **No token to steal from the browser.** With refresh tokens, if an attacker exploits an XSS vulnerability, they can steal the refresh token from localStorage and use it from their own machine. With volta's HttpOnly session cookie, XSS cannot read the cookie at all.

4. **Simpler mental model.** The browser only needs one credential (the session cookie). The JWT is issued per-request by volta in the ForwardAuth flow and never touches the browser.

The flow:

```
  Browser          volta            Downstream App
  |                |                |
  | Request page   |                |
  | Cookie: __volta_session=uuid    |
  |--------------->|                |
  |                |                |
  |  Session valid? Check DB.       |
  |  Session.expiresAt > now? Yes.  |
  |  Extend session (touch).        |
  |  Issue fresh JWT.               |
  |                |                |
  |                | X-Volta-JWT: eyJ... (5 min TTL)
  |                |--------------->|
  |                |                |
  |                | Response       |
  |<-------------------------------|
  |                |                |
  | (5 min later, new request)     |
  | Cookie: __volta_session=uuid   |
  |--------------->|                |
  |                | New JWT issued |
  |                |--------------->|
```

**Session lifetime.** volta's sessions last 8 hours by default (`SESSION_TTL_SECONDS=28800`). Each request extends the session's expiration ("sliding expiration"), so an active user stays logged in.

**Session revocation.** When a user logs out, volta calls `store.revokeSession()`, which marks the session as invalidated in the database. Even if the cookie still exists in the browser, the session is rejected on the next request.

## Common mistakes

**1. Storing refresh tokens in localStorage.**
This is the most common mistake with traditional refresh token architectures. localStorage has zero protection against XSS. volta sidesteps this entirely by using HttpOnly cookies.

**2. Not rotating refresh tokens.**
If a refresh token works forever (or until manually revoked), a stolen token gives the attacker persistent access. Always rotate refresh tokens on each use.

**3. Making refresh tokens too long-lived.**
A 30-day refresh token means a stolen token gives 30 days of access. Balance convenience against risk. volta's 8-hour session with sliding expiration is a reasonable middle ground.

**4. Not limiting concurrent sessions.**
Without a limit, an attacker who steals a refresh token (or session) can use it alongside the legitimate user indefinitely. volta caps concurrent sessions at 5 per user and revokes the oldest when the limit is exceeded.

**5. Thinking you always need refresh tokens.**
Refresh tokens are one solution to the "keep the user logged in" problem. Server-side sessions with HttpOnly cookies are another. For web applications behind a reverse proxy (like volta's architecture), the session-cookie approach is often simpler and more secure.

**6. Not providing instant revocation.**
With pure JWT-based refresh tokens and no server-side state, revocation requires waiting for the token to expire. volta's server-side sessions allow instant revocation by updating the database.
