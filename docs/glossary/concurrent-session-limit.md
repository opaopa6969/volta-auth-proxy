# Concurrent Session Limit

[日本語版はこちら](concurrent-session-limit.ja.md)

---

## What is it?

A concurrent session limit caps how many active sessions a single user can have at the same time. Think of a streaming service that lets you stream on 3 devices simultaneously -- the 4th device gets blocked or the oldest stream gets kicked off.

For authentication, this means a user logged in on their laptop, phone, and tablet might hit a wall when they try to log in on a fourth device.

---

## Why does it matter?

**Security:** If an attacker steals credentials, a session limit means they can't silently open sessions on dozens of machines. The legitimate user will likely notice when one of their existing sessions gets terminated.

**License enforcement:** Some B2B products charge per seat. Unlimited concurrent sessions let one account serve an entire team, defeating the pricing model.

**Resource management:** Each session consumes server memory and database rows. Unbounded sessions from a single user can waste resources.

### What happens on the Nth+1 login?

There are two common strategies:

| Strategy | Behavior | UX impact |
|----------|----------|-----------|
| **Block new login** | "You have too many sessions. Log out elsewhere first." | Frustrating -- user may not remember where they're logged in |
| **Evict oldest session** | Oldest session is terminated, new one proceeds | Smoother, but surprising if you're using the old device |

Most modern products choose **evict oldest** because it's less frustrating.

---

## A simple example

```
Session 1: Laptop (created 09:00)
Session 2: Phone (created 10:00)
Session 3: Tablet (created 11:00)
Session 4: Work PC (created 12:00)
Session 5: Home PC (created 13:00)

Limit = 5. All five are active.

Session 6: Coffee shop laptop (created 14:00)
  -> Session 1 (Laptop) is evicted. User must log in again on that device.
```

---

## In volta-auth-proxy

volta does **not** currently enforce a concurrent session limit. The `sessions` table allows unlimited active sessions per user. This is a common approach for Phase 1 of a SaaS product where simplicity is prioritized.

When a session limit is added (target: 5 sessions per user), the implementation would look like:

1. On login, count active sessions for this user
2. If count >= 5, invalidate the oldest session (`invalidated_at = NOW()`)
3. Create the new session normally

The `sessions` table already tracks `created_at` and `invalidated_at`, making this straightforward to add.

**UX consideration:** When a session is evicted, the user on that device should see a clear message like "You were signed out because you signed in on another device" rather than a generic error. volta's error page system already supports custom error codes like `SESSION_REVOKED` that could serve this purpose.

---

## See also

- [session-storage-strategies.md](session-storage-strategies.md) -- Where sessions are stored
- [session-hijacking.md](session-hijacking.md) -- Why limiting sessions helps security
