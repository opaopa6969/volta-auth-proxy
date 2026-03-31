# Session Management

[English](session-management.md) | [日本語](session-management.ja.md)

> It's OK not to know this! Everyone uses sessions every day without thinking about it.

## What is it?

**Session management is the entire system for remembering "this person is logged in" across multiple page loads.** It's not just the session itself — it's creating, storing, validating, expiring, and destroying sessions.

Think of it like a hotel front desk system:
- **Check-in** = creating a session (login)
- **Room key** = session cookie (proves you're a guest)
- **Guest registry** = session store (database of active guests)
- **Key expiration** = session timeout (key stops working after checkout time)
- **Check-out** = destroying a session (logout)
- **Master key revocation** = admin forcing all sessions to expire

## Why does it matter?

Without session management, users would have to log in on **every single page load**. That's obviously terrible UX. But doing it wrong is a security disaster:

- Sessions that never expire → stolen sessions work forever
- Sessions stored insecurely → anyone can impersonate users
- No concurrent session limit → attacker logs in undetected
- No session revocation → can't force-logout compromised accounts

## How does it work?

```
Login:
  User authenticates → Server creates session → Cookie sent to browser
                            ↓
                       Session stored in DB
                       (id, user_id, tenant_id, expires_at)

Every request:
  Browser sends cookie → Server validates session
                              ↓
                         Still valid? → Extend expiry (sliding window)
                         Expired? → 401, redirect to login
                         Revoked? → 401, redirect to login

Logout:
  Server marks session as invalidated → Cookie deleted
```

## In volta-auth-proxy

volta's session management includes all of these:

| Feature | Implementation |
|---------|---------------|
| **Creation** | On successful Google OIDC callback |
| **Storage** | Postgres `sessions` table |
| **Cookie** | `__volta_session`, HttpOnly, Secure, SameSite=Lax |
| **Signing** | HMAC-SHA256 (prevents cookie tampering) |
| **Expiry** | 8-hour [sliding window](sliding-window-expiry.md) |
| **Concurrent limit** | Max 5 sessions per user |
| **Revocation** | `sessions.invalidated_at` field |
| **Fixation prevention** | Session ID regenerated on every login |
| **Multi-device** | Users can view and revoke sessions at `/settings/sessions` |
| **Tenant binding** | Each session is bound to one [tenant](tenant.md) |

## Related

- [Session](session.md) — What a session IS
- [Cookie](cookie.md) — How sessions are tracked in the browser
- [Session Fixation](session-fixation.md) — An attack on session management
- [Session Hijacking](session-hijacking.md) — Another attack
- [Sliding Window Expiry](sliding-window-expiry.md) — How volta's timeout works
- [Concurrent Session Limit](concurrent-session-limit.md) — Max 5 sessions
