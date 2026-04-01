# Revoke

[日本語版はこちら](revoke.ja.md)

---

## What is it?

Revocation is the act of canceling a key, token, or session before its natural expiry. Instead of waiting for something to expire on its own, you actively declare "this is no longer valid, effective immediately."

Think of it like canceling a credit card. Your card has an expiry date printed on it (2028/12), but if it gets stolen, you call the bank and cancel it right now. The card still physically exists, the expiry date has not changed, but the bank's system rejects it on the next swipe. That phone call is revocation.

Revocation is the emergency brake of authentication systems. Normal operation relies on expiry (tokens expire, sessions time out), but when something goes wrong -- a key is leaked, an account is compromised, an employee leaves -- you need the ability to cut access immediately.

---

## Why does it matter?

Without revocation, you are at the mercy of time. If a JWT has a 24-hour expiry and the signing key leaks, an attacker has 24 hours of unrestricted access. If a session has no revocation mechanism, a fired employee stays logged in until the session naturally expires.

Revocation provides:

- **Immediate response**: Cut access the moment a compromise is detected.
- **Damage limitation**: Reduce the window of exploitation from hours/days to seconds.
- **Compliance**: Many regulations require the ability to terminate access immediately (GDPR right to erasure, SOC 2 access controls).

The difficulty is that not everything is equally revocable. Opaque tokens (session IDs) are easy to revoke -- delete the record. Self-contained tokens (JWTs) are hard to revoke because they carry their own validity.

---

## How does it work?

### Revocability spectrum

```
  Easy to revoke                          Hard to revoke
  ◄─────────────────────────────────────────────────────►
  │                      │                              │
  Session ID             Refresh token                  JWT
  (delete from DB)       (delete from DB)        (self-contained,
                                                  no DB to delete
                                                  from -- must wait
                                                  for expiry)
```

### Revoking sessions (server-side state)

```
  Before revocation:
  ┌─────────────────────────────────────────┐
  │  sessions table                         │
  │  ┌───────────────────────────────────┐  │
  │  │ id: abc-123        status: ACTIVE │  │
  │  │ user_id: alice     expires: 17:00 │  │
  │  └───────────────────────────────────┘  │
  └─────────────────────────────────────────┘
       ↑ Cookie "abc-123" → found → access granted

  After revocation (DELETE from sessions):
  ┌─────────────────────────────────────────┐
  │  sessions table                         │
  │  (empty -- row deleted)                 │
  └─────────────────────────────────────────┘
       ↑ Cookie "abc-123" → NOT found → 401
```

### Revoking JWTs (the hard problem)

JWTs are self-contained. There is no database row to delete. Options:

```
  Option 1: Short expiry (volta's approach)
  ┌──────────────────────────────────────────┐
  │  JWT expires in 5 minutes.               │
  │  Revoke the session → no new JWTs issued │
  │  Existing JWTs die in max 5 min.         │
  │  Acceptable propagation delay.           │
  └──────────────────────────────────────────┘

  Option 2: Token blocklist (not used by volta)
  ┌──────────────────────────────────────────┐
  │  Maintain a list of revoked JTIs.        │
  │  Check every JWT against the list.       │
  │  Adds latency and state to every request.│
  │  Defeats the purpose of stateless JWTs.  │
  └──────────────────────────────────────────┘

  Option 3: Key rotation
  ┌──────────────────────────────────────────┐
  │  Rotate the signing key.                 │
  │  ALL tokens signed with old key become   │
  │  unverifiable once old key is removed.   │
  │  Nuclear option -- affects all users.    │
  └──────────────────────────────────────────┘
```

### Revoking signing keys

```
  POST /api/v1/admin/keys/rotate

  Before:  Key A (active) → signing JWTs
  After:   Key A (retired) → Key B (active) → signing JWTs
           Key A tokens still valid until 5 min expiry
           After 5 min, Key A can be fully removed
```

---

## How does volta-auth-proxy use it?

volta implements revocation at multiple levels:

### 1. Session revocation (logout)

Deleting a session row from the `sessions` table immediately invalidates the session. The user's `__volta_session` cookie becomes a dangling pointer to nothing:

```
  User clicks "Logout"
       │
       ▼
  DELETE FROM sessions WHERE id = ?
       │
       ▼
  Cookie still exists in browser, but points to no session
       │
       ▼
  Next request: session lookup fails → 401 → redirect to login
```

### 2. JWT propagation delay

JWTs cannot be revoked directly, but volta's 5-minute expiry means revocation [propagates](propagation.md) quickly:

```
  Time 0:00  Admin revokes user session
  Time 0:00  No new JWTs can be issued (session gone)
  Time 0:01  Existing JWT still works (4 min left)
  Time 5:00  JWT expires. User fully locked out.

  Maximum propagation delay: 5 minutes
```

### 3. Key revocation via rotation

If a [signing key](signing-key.md) is suspected compromised:

```
  POST /api/v1/admin/keys/rotate
       │
       ▼
  New key generated, old key retired
       │
       ▼
  JWTs signed with old key expire in max 5 min
       │
       ▼
  System fully recovered with new key
```

### 4. Tenant-level revocation

Revoking all sessions for a tenant (e.g., tenant compromise):

```
  DELETE FROM sessions WHERE tenant_id = ?
       │
       ▼
  All users in tenant logged out immediately
  All existing JWTs expire within 5 minutes
```

---

## Common mistakes and attacks

### Mistake 1: No revocation mechanism at all

Systems that rely solely on token expiry cannot respond to emergencies. If JWTs last 24 hours and a key leaks, you have a 24-hour vulnerability window. volta keeps JWTs at 5 minutes to minimize this.

### Mistake 2: Revoking the cookie but not the session

Clearing the browser cookie does not invalidate the session server-side. An attacker who copied the session ID can still use it. Always delete the session from the database.

### Mistake 3: Slow revocation propagation

If downstream services cache JWTs or JWKS aggressively, revocation may not take effect for hours. Keep cache TTLs short. volta's 5-minute JWT expiry is the maximum propagation delay.

### Attack: Using a revoked token before propagation

An attacker with a stolen JWT has up to 5 minutes (volta's JWT TTL) to use it even after the session is revoked. This is why volta combines session revocation (instant for server-side checks) with short JWT expiry (limits damage for stateless checks).

---

## Further reading

- [invalidation.md](invalidation.md) -- The broader concept of making things invalid
- [session.md](session.md) -- Session lifecycle including revocation
- [key-rotation.md](key-rotation.md) -- Revoking and replacing signing keys
- [propagation.md](propagation.md) -- How revocation spreads through the system
- [token-theft.md](token-theft.md) -- Why revocation is critical when tokens are stolen
- [graceful-transition.md](graceful-transition.md) -- How key revocation avoids disruption
