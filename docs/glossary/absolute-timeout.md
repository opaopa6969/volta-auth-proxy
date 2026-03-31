# Absolute Timeout

[日本語版はこちら](absolute-timeout.ja.md)

---

## What is it?

Absolute timeout means a session dies after a fixed amount of time from when it was created, no matter how active the user is. Think of a movie theater ticket -- it's valid for the 7 PM show regardless of whether you arrive at 6:30 or 6:59. Once the show ends, the ticket is done.

This is the opposite of **sliding window expiry** (see [sliding-window-expiry.md](sliding-window-expiry.md)), where activity resets the clock.

---

## Why does it matter?

Absolute timeout provides a hard security boundary. Even if an attacker hijacks a session and keeps it active, the session will eventually die. This limits the damage window.

It's especially important in high-security environments like banking, healthcare, and government systems where regulations often require sessions to expire within a fixed period (e.g., 15 minutes for banking, 8 hours for enterprise).

### When to use which?

| Strategy | Best for | Example |
|----------|----------|---------|
| Sliding only | Low-risk apps, user convenience | Blog platform, wiki |
| Absolute only | High-security, strict compliance | Banking, healthcare portal |
| Both combined | Best practice for most SaaS | Sliding = 30min, Absolute = 8h |

The combined approach gives the best of both worlds: users aren't interrupted during active work (sliding), but no session can live forever (absolute).

---

## A simple example

```
09:00  User logs in.         Absolute deadline = 17:00 (8h max)
11:30  User clicks a page.   Absolute deadline still = 17:00
14:00  User clicks a page.   Absolute deadline still = 17:00
16:59  User clicks a page.   Absolute deadline still = 17:00
17:00  Session dies. Must log in again.
```

No amount of activity can push past the 17:00 deadline.

---

## In volta-auth-proxy

volta currently uses **sliding window expiry only** (8 hours). It does not enforce an absolute timeout ceiling. The `sessions` table has a `created_at` column that could be used to add absolute timeout in the future, but it is not checked today.

For most SaaS products, volta's approach is reasonable. The 8-hour sliding window means sessions effectively expire at the end of a workday of inactivity. If your security requirements demand absolute timeout (e.g., SOC 2 with strict session controls), you would add a check like:

```java
if (session.createdAt().plusSeconds(absoluteMaxSeconds).isBefore(Instant.now())) {
    // force re-authentication
}
```

This is a candidate for a future enhancement when volta targets enterprise compliance certifications.

---

## See also

- [sliding-window-expiry.md](sliding-window-expiry.md) -- The activity-based timeout strategy
- [session-hijacking.md](session-hijacking.md) -- Why absolute timeout limits attacker damage
