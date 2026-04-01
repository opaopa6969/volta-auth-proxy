# Sliding Window

[日本語版はこちら](sliding-window.ja.md)

---

The sliding window is one of those concepts that sounds complicated until you hear the right analogy. Then it becomes obvious.

---

## The library book analogy

Imagine you borrow a book from the library. The library has two possible policies:

**Policy A: Absolute timeout**
"The book is due in 14 days. Period. It does not matter if you just started reading it on day 13. Return it on day 14."

**Policy B: Sliding window**
"The book is due 14 days after you last opened it. Every time you read a chapter, the due date pushes forward 14 days. If you stop reading for 14 days, we assume you are done and want it back."

Policy A is predictable but frustrating. You might be in the middle of a chapter when the book is due. Policy B is forgiving -- it rewards active use. As long as you keep reading, the book stays with you. But if you forget about it, it comes back to the library.

volta-auth-proxy uses Policy B for sessions. That is the sliding window.

---

## How volta's 8-hour sliding window works

When a user logs in, volta creates a session that expires 8 hours from now:

```
  09:00  User logs in.
         Session created.
         Expires at: 17:00 (8 hours from now)
```

Every time the user does something (loads a page, makes an API call, clicks a link), volta's ForwardAuth handler "touches" the session and pushes the expiry forward:

```
  09:00  Login.            Expires at 17:00
  10:30  Load /dashboard.  Expires at 18:30  (8h from NOW)
  12:00  Load /settings.   Expires at 20:00  (8h from NOW)
  14:00  Load /team.       Expires at 22:00  (8h from NOW)
  14:15  Load /billing.    Expires at 22:15  (8h from NOW)
```

The clock resets with every interaction. An active user can work all day without being interrupted by a session timeout. The session only dies if the user stops interacting for 8 consecutive hours.

```
  14:15  Last interaction.
  14:16  User goes home.
  ...
  22:15  Session expires. Nobody renewed it.
  22:16  Next visit: "Please log in again."
```

---

## Sliding window vs absolute timeout

These are the two fundamental timeout strategies. Most systems use one or both:

```
  Sliding window:
  ─────────────────────────────────────────────►
  Login    Activity  Activity  Activity    8h idle → expires
  09:00    10:30     12:00     14:00       22:00

  The window SLIDES forward with each interaction.


  Absolute timeout:
  ─────────────────────────────────────────────►
  Login                                   Always expires at 17:00
  09:00                                   17:00
  (no matter how active the user is)

  The deadline is FIXED at login time.
```

| Aspect | Sliding window | Absolute timeout |
|--------|---------------|-----------------|
| Expires after... | 8 hours of **inactivity** | 8 hours from **login** |
| Active user | Can work all day | Gets kicked out at the fixed time |
| Inactive user | Expires after 8h idle | Expires after 8h regardless |
| UX | Better (no surprise logouts during work) | Worse (logout during active use) |
| Security | Slightly less strict (session can live "forever" if always active) | Strictly bounded (maximum lifetime guaranteed) |

### Why sliding is better for UX

Imagine a developer working in a wiki application protected by volta. They are writing documentation. They type a paragraph, preview it, type another paragraph, save. Every few minutes, they interact with the app.

With a sliding window, their session keeps extending. They never see a login prompt during their work session. Their flow is uninterrupted.

With an absolute timeout, at exactly 8 hours after login, they are suddenly logged out. Maybe they were in the middle of writing a paragraph. The form data might be lost. They have to log in again, navigate back to the page, and hope their work was saved.

For a SaaS tool meant for daily use, the sliding window is the right choice.

### The security consideration

The concern with sliding windows is that a session can theoretically live forever if the user (or an attacker with a stolen session) keeps it active. In practice, volta mitigates this through:

1. **Maximum concurrent sessions** (5 per user) -- limits the number of active sessions
2. **Session revocation** -- users can see and kill active sessions
3. **IP/user-agent tracking** -- suspicious sessions can be identified
4. **Admin session revocation** -- admins can revoke any user's sessions

volta does not currently enforce an absolute timeout ceiling on top of the sliding window. For most SaaS applications, the sliding window with concurrent session limits provides a good balance. If your security requirements demand an absolute ceiling (e.g., "no session can live longer than 24 hours regardless of activity"), this can be added as a code change -- see [absolute-timeout.md](absolute-timeout.md).

---

## The implementation

In volta's code, the sliding window is implemented by `touchSession()`:

```java
// Every time ForwardAuth validates a session:
store.touchSession(session.id(), Instant.now().plusSeconds(config.sessionTtlSeconds()));
// This pushes expires_at forward by SESSION_TTL_SECONDS (default: 28800 = 8 hours)
```

The SQL behind this is a simple UPDATE:

```sql
UPDATE sessions SET expires_at = $2, last_active_at = NOW() WHERE id = $1
```

Every authenticated request "touches" the session. The touch is lightweight -- a single UPDATE statement on an indexed primary key. This adds negligible overhead to the ForwardAuth check.

---

## Choosing the window size

volta defaults to 8 hours (`SESSION_TTL_SECONDS=28800`) because it matches a typical workday:

- A user logs in at 9 AM
- They work until 5 PM (8 hours of activity)
- They go home
- The session expires at 1 AM (8 hours after their last interaction around 5 PM)
- Next morning, they log in again

If you need a different window, change the `SESSION_TTL_SECONDS` environment variable. Shorter windows (e.g., 1 hour for banking) are more secure but require more frequent logins. Longer windows (e.g., 24 hours) are more convenient but mean sessions persist longer after the user walks away.

---

## Further reading

- [sliding-window-expiry.md](sliding-window-expiry.md) -- The technical details of volta's implementation.
- [absolute-timeout.md](absolute-timeout.md) -- The alternative timeout strategy.
- [session.md](session.md) -- How sessions work in volta.
- [session-storage-strategies.md](session-storage-strategies.md) -- Where sessions are stored.
