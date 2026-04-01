# Silent Refresh

[日本語版はこちら](silent-refresh.ja.md)

---

## What is it?

Silent refresh is the process of automatically obtaining a new access [token](token.md) without requiring the user to re-enter their credentials or even notice that anything happened. When the current token expires, the client-side code quietly requests a fresh one in the background.

Think of it like an automatic toll tag renewal. Your toll tag expires every month, but the toll company automatically charges your card and activates a new tag without you stopping at the counter. You keep driving through the toll booth without interruption. The tag renewal happens "silently" -- you never see a login screen or a "please re-authenticate" message.

In volta's architecture, volta-sdk-js detects when a JWT is about to expire (or has already expired, triggering a 401 response) and automatically calls the token endpoint to get a fresh JWT -- all without the user seeing a loading spinner or being redirected to a login page.

---

## Why does it matter?

Short-lived tokens (volta's JWT: 5 minutes) are a security best practice -- they limit the damage if a token is stolen. But without silent refresh, a 5-minute token means the user would be interrupted every 5 minutes to re-authenticate. That is unacceptable for any real application.

Silent refresh bridges the gap between security and usability:

- **Security**: Tokens stay short-lived (5 min). Stolen tokens are useful for at most 5 minutes.
- **Usability**: Users stay logged in for 8 hours (session lifetime) without interruption.
- **Transparency**: The token refresh is invisible to the user and to the application code.

Without silent refresh, developers face a bad choice: long-lived tokens (insecure) or constant re-authentication (unusable).

---

## How does it work?

### The refresh cycle

```
  Time ──────────────────────────────────────────────────►

  0:00   User logs in
         volta issues JWT (expires at 0:05)
         volta-sdk-js stores JWT in memory

  0:04   SDK detects JWT expires in < 1 minute
         SDK calls POST /api/v1/auth/token
         (with __volta_session cookie)
         volta issues new JWT (expires at 0:09)
         SDK replaces old JWT with new one

  0:08   SDK detects JWT expires in < 1 minute
         SDK calls POST /api/v1/auth/token
         volta issues new JWT (expires at 0:13)
         ...

  This repeats every ~4 minutes, invisibly,
  for the entire 8-hour session.
```

### The 401-based refresh

```
  API call with expired JWT:
  ┌──────────────────────────────────────────────────┐
  │  App code:  fetch("/api/data", { jwt })          │
  │                                                  │
  │  Server: JWT expired → 401 Unauthorized          │
  │                                                  │
  │  volta-sdk-js intercepts the 401:               │
  │  1. Call POST /api/v1/auth/token                │
  │     (cookie-based, no JWT needed)               │
  │  2. Receive new JWT                             │
  │  3. Retry the original request with new JWT     │
  │  4. Return the response to app code             │
  │                                                  │
  │  App code never sees the 401.                    │
  │  It just gets the data, slightly delayed.        │
  └──────────────────────────────────────────────────┘
```

### Proactive vs. reactive refresh

```
  Proactive (before expiry):
  ┌──────────────────────────────────────────┐
  │  JWT expires at T+5:00                   │
  │  SDK refreshes at T+4:00 (1 min early)  │
  │  No 401 ever occurs                     │
  │  No retry needed                        │
  │  Best user experience                   │
  └──────────────────────────────────────────┘

  Reactive (after expiry):
  ┌──────────────────────────────────────────┐
  │  JWT expires at T+5:00                   │
  │  SDK calls API at T+5:30                 │
  │  Server returns 401                      │
  │  SDK refreshes, retries                  │
  │  Slight delay, but user doesn't notice   │
  └──────────────────────────────────────────┘

  volta-sdk-js supports both strategies.
```

### What the session cookie enables

```
  Why silent refresh works without a refresh token:

  ┌─────────────────────────────────────────────────┐
  │  Traditional approach:                          │
  │  Access token (5 min) + Refresh token (7 days)  │
  │  Refresh token stored in... where?              │
  │  localStorage? (XSS risk)                       │
  │  httpOnly cookie? (then why not just use it?)   │
  │                                                 │
  │  volta's approach:                              │
  │  JWT (5 min) + Session cookie (8h)              │
  │  Session cookie is httpOnly, Secure, SameSite   │
  │  It IS the refresh mechanism.                   │
  │  No separate refresh token needed.              │
  └─────────────────────────────────────────────────┘

  POST /api/v1/auth/token
  Cookie: __volta_session=abc123  ← This proves identity
  Response: { "token": "eyJhbG..." }  ← New JWT
```

---

## How does volta-auth-proxy use it?

### Server side: token endpoint

volta exposes `POST /api/v1/auth/token` (or equivalent) that:

1. Reads the `__volta_session` [signed cookie](signed-cookie.md)
2. Validates the session (exists, not expired, signature valid)
3. Issues a fresh JWT with updated `iat` and `exp` [claims](claim.md)
4. Returns the JWT in the response body

The session's [sliding window](sliding-window-expiry.md) is also extended on this call.

### Client side: volta-sdk-js

The SDK handles silent refresh transparently:

```
  volta-sdk-js lifecycle:
  ┌────────────────────────────────────────────┐
  │  1. Initialize SDK                        │
  │  2. Fetch initial JWT from token endpoint │
  │  3. Start refresh timer (JWT exp - 60s)   │
  │  4. On timer fire:                        │
  │     └── Call token endpoint               │
  │     └── Update in-memory JWT              │
  │     └── Reset timer                       │
  │  5. On 401 response:                      │
  │     └── Call token endpoint               │
  │     └── Retry failed request              │
  │  6. On token endpoint failure:            │
  │     └── Session expired → redirect login  │
  └────────────────────────────────────────────┘
```

### What happens when the session expires

```
  JWT expires → SDK calls token endpoint
  Session also expired → Server returns 401
  SDK cannot get new JWT → Session is over
  SDK triggers: redirect to login page

  The user sees: "Your session has expired. Please log in again."
  This only happens after 8 hours of inactivity.
```

---

## Common mistakes and attacks

### Mistake 1: Storing JWTs in localStorage for refresh

`localStorage` persists across page loads but is accessible to XSS. volta-sdk-js stores JWTs in memory only (JavaScript variable). On page reload, the SDK fetches a fresh JWT from the token endpoint.

### Mistake 2: Not handling refresh failure

If the token endpoint returns an error (session expired, server down), the SDK must handle it gracefully -- redirect to login, show an error message, not loop infinitely.

### Mistake 3: Race conditions on concurrent requests

If 5 API calls all get 401 simultaneously, the SDK should not make 5 refresh calls. Use a single in-flight refresh promise that all waiting requests share.

### Mistake 4: Refresh without session validation

The token endpoint must validate the session on every call. A refresh endpoint that issues JWTs without checking session validity defeats the purpose of session expiry.

### Attack: Session fixation via refresh

If the session ID is not [regenerated](regenerate.md) appropriately, an attacker who set up a session beforehand can wait for the victim to authenticate into that session, then use silent refresh to maintain access. volta prevents this by regenerating session IDs on login.

---

## Further reading

- [token.md](token.md) -- The tokens being refreshed
- [session-expiry.md](session-expiry.md) -- When silent refresh stops working (session timeout)
- [sliding-window-expiry.md](sliding-window-expiry.md) -- How the session window extends on refresh
- [signed-cookie.md](signed-cookie.md) -- The cookie that enables refresh without a refresh token
- [jwt.md](jwt.md) -- The token format being refreshed
- [propagation.md](propagation.md) -- How JWT expiry bounds the propagation delay
