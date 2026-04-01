# Session Expiry

[日本語版はこちら](session-expiry.ja.md)

---

## What is it?

Session expiry is the point at which a login session is no longer valid and the user must re-authenticate. Every session has a limited lifetime -- when that time runs out, the session "dies" and the user is redirected to the login page.

Think of it like a parking meter. You pay for 2 hours. After 2 hours, your parking is expired -- you either feed the meter again (re-authenticate) or get a ticket (401 error). Some meters reset the timer every time you add a coin (sliding window). Others expire at a fixed time regardless (absolute timeout).

Session expiry is a balance between security and usability. Too short, and users are constantly re-logging in. Too long, and a forgotten session (on a shared computer, for example) stays accessible for hours or days.

---

## Why does it matter?

Without session expiry:

- **Shared computers**: A user logs in at a library, walks away, and someone else uses their session indefinitely.
- **Stolen sessions**: A session cookie stolen via network interception works forever.
- **Resource exhaustion**: The server accumulates sessions that will never be used again but consume memory and database rows.
- **Compliance failures**: Regulations like PCI DSS require session timeouts.

Session expiry is the safety net. Even if every other defense fails (HttpOnly bypassed, HTTPS compromised, XSS exploited), the damage is bounded in time.

---

## How does it work?

### Two types of expiry

```
  Absolute timeout:
  ┌──────────────────────────────────────────────┐
  │  Session created at 09:00                    │
  │  Absolute timeout: 8 hours                   │
  │  Dies at: 17:00 (no matter what)             │
  │                                              │
  │  09:00 ═══════════════════════════ 17:00     │
  │  Login                              Dead     │
  │                                              │
  │  Even if user is actively using the app,     │
  │  the session expires at 17:00.               │
  └──────────────────────────────────────────────┘

  Sliding window (idle timeout):
  ┌──────────────────────────────────────────────┐
  │  Session created at 09:00                    │
  │  Sliding window: 8 hours                     │
  │                                              │
  │  09:00  Request → expires resets to 17:00    │
  │  11:00  Request → expires resets to 19:00    │
  │  14:00  Request → expires resets to 22:00    │
  │  ...user goes home...                        │
  │  22:00  No request since 14:00 → EXPIRED     │
  │                                              │
  │  Timer resets on EVERY activity.             │
  │  Expires only when user is IDLE.             │
  └──────────────────────────────────────────────┘
```

### volta uses sliding window

```
  volta's session expiry: 8-hour sliding window

  Time ──────────────────────────────────────────►

  09:00  Login → session.expires_at = 17:00
         │
  10:30  API call → session.expires_at = 18:30
         │                (slid forward by 8h from now)
  12:00  Page load → session.expires_at = 20:00
         │
  14:00  Form submit → session.expires_at = 22:00
         │
  ...user leaves for the day...
         │
  22:00  No activity → SESSION EXPIRED
         │
  Next morning: user visits site → 401 → login page
```

### Expiry check flow

```
  Request arrives with __volta_session cookie
         │
         ▼
  Look up session in database
         │
         ├── Not found? → 401 (session deleted or never existed)
         │
         ├── Found. Check expires_at:
         │   │
         │   ├── expires_at < now → EXPIRED → delete session → 401
         │   │
         │   └── expires_at > now → VALID
         │       │
         │       ▼
         │   Update expires_at = now + 8h  (slide the window)
         │       │
         │       ▼
         │   Continue processing request
         │
         └── Set-Cookie with updated Max-Age
```

---

## How does volta-auth-proxy use it?

### Configuration

volta's session lifetime is 8 hours (28800 seconds), configured as `Max-Age=28800` on the `__volta_session` cookie.

### Sliding window implementation

On every authenticated request, volta:

1. Reads the session from the `sessions` table
2. Checks if `expires_at` is in the past (expired)
3. If valid, updates `expires_at` to `now + 8 hours`
4. Re-sends the cookie with a fresh `Max-Age=28800`

```
  sessions table:
  ┌────────────────────────────────────────────────┐
  │ id:         550e8400-e29b-41d4-a716-...        │
  │ user_id:    alice-uuid                         │
  │ tenant_id:  acme-uuid                          │
  │ expires_at: 2026-04-01T22:00:00Z  ← slides    │
  │ created_at: 2026-04-01T09:00:00Z  ← fixed     │
  └────────────────────────────────────────────────┘
```

### Interaction with JWT expiry

volta has two expiry clocks running simultaneously:

```
  ┌─────────────────────────────────────────────┐
  │  Session:  8h sliding window                │
  │  JWT:      5 min absolute                   │
  │                                             │
  │  Session expires → no new JWTs can be       │
  │  issued → user must re-login                │
  │                                             │
  │  JWT expires → client does silent refresh   │
  │  → new JWT issued (if session still valid)  │
  │                                             │
  │  Session expiry: "are you still logged in?" │
  │  JWT expiry: "is this specific token fresh?"│
  └─────────────────────────────────────────────┘
```

### Cleanup

Expired sessions should be periodically deleted from the database to prevent accumulation. volta can clean up expired sessions with:

```sql
DELETE FROM sessions WHERE expires_at < NOW();
```

---

## Common mistakes and attacks

### Mistake 1: No session expiry at all

Sessions that live forever are a permanent security hole. An attacker who steals a session cookie has indefinite access. Always set an expiry.

### Mistake 2: Only client-side expiry

Setting `Max-Age` on the cookie without checking `expires_at` server-side means the session lives on even if the cookie is manually re-sent with a modified expiry. Always validate server-side.

### Mistake 3: Absolute timeout too long

A 30-day absolute timeout on a banking application is dangerous. Match the timeout to the sensitivity of the data. volta's 8-hour sliding window is appropriate for business applications.

### Mistake 4: Not sliding the window

An absolute timeout of 8 hours means a user who logged in at 09:00 gets kicked out at 17:00, even if they have been active all day. A [sliding window](sliding-window-expiry.md) is more user-friendly for interactive applications.

### Attack: Session persistence after logout

If the session record is not deleted on logout, and only the cookie is cleared, an attacker who captured the session ID can continue using it until expiry. Always delete the session from the database on logout. See [invalidation](invalidation.md).

---

## Further reading

- [sliding-window-expiry.md](sliding-window-expiry.md) -- The sliding window mechanism in detail
- [absolute-timeout.md](absolute-timeout.md) -- Fixed-time session expiry
- [session.md](session.md) -- Sessions in general
- [cookie.md](cookie.md) -- How expiry interacts with cookie Max-Age
- [silent-refresh.md](silent-refresh.md) -- How JWT expiry triggers token renewal
- [invalidation.md](invalidation.md) -- Active session termination (vs. passive expiry)
