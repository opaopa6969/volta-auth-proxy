# Sliding Window Expiry

[日本語版はこちら](sliding-window-expiry.ja.md)

---

## What is it?

Sliding window expiry is a session timeout strategy where the expiration time resets every time the user does something. Imagine a library that says "you can stay, but if you sit still for 2 hours without touching a book, we'll ask you to leave." Every time you pick up a book, the 2-hour clock restarts.

In technical terms: the session has a timeout of N hours from the **last activity**, not from the original login time.

---

## Why does it matter?

Without sliding expiry, users would get kicked out at a fixed time regardless of what they're doing. Imagine writing a long document and suddenly getting logged out because your session started 8 hours ago -- even though you've been actively working the whole time. Sliding expiry prevents this frustrating experience.

The tradeoff is security. A session that keeps extending could theoretically live forever if the user (or an attacker) keeps it active. That's why many systems combine sliding expiry with an **absolute timeout** (see [absolute-timeout.md](absolute-timeout.md)) as a hard ceiling.

---

## A simple example

```
09:00  User logs in.         Session expires at 17:00 (8h later)
11:30  User clicks a page.   Session expires at 19:30 (8h from NOW)
14:00  User clicks a page.   Session expires at 22:00 (8h from NOW)
14:01  User goes home.
22:00  Session expires. Next visit requires login.
```

Compare this to a fixed timeout where the session would always die at 17:00 regardless of activity.

---

## In volta-auth-proxy

volta uses sliding window expiry with an **8-hour window** (`SESSION_TTL_SECONDS=28800`).

Every time a request is authenticated via session cookie, volta calls `touchSession()`, which pushes the `expires_at` column forward by another 8 hours from the current time:

```java
store.touchSession(session.id(), Instant.now().plusSeconds(config.sessionTtlSeconds()));
```

This means an active user can stay logged in throughout their entire workday without interruption, but an idle session will expire after 8 hours of inactivity.

volta does not currently enforce an absolute timeout ceiling. For most SaaS applications, the sliding window alone provides a good balance between security and usability.

---

## See also

- [absolute-timeout.md](absolute-timeout.md) -- The other timeout strategy
- [session-storage-strategies.md](session-storage-strategies.md) -- Where sessions live
