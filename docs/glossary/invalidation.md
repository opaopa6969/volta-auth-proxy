# Invalidation

[日本語版はこちら](invalidation.ja.md)

---

## What is it?

Invalidation is the act of making something no longer valid. In authentication, it means declaring that a session, token, or credential should no longer be accepted -- even if it has not technically expired yet. It is the active form of "this is done."

Think of it like voiding a check. The check itself still exists on paper, it still has a valid date, and it still looks legitimate. But you have written "VOID" across it, and the bank will refuse to process it. Invalidation is writing "VOID" on a session, token, or key.

Invalidation differs from [expiry](session-expiry.md) (which is passive -- time runs out) and from [revocation](revoke.md) (which is a specific form of invalidation, typically for keys and tokens). Invalidation is the broader concept: any mechanism that makes something no longer accepted.

---

## Why does it matter?

Without invalidation, the only way for something to stop being valid is to wait for it to expire. This creates problems:

- **Logout is a lie**: Clicking "logout" clears the cookie but the session record lives on. An attacker who copied the session ID can still use it.
- **Compromise recovery is slow**: A leaked key or token remains valid until natural expiry.
- **Access control changes lag**: A user demoted from admin still has admin JWTs until they expire.

Invalidation gives you active control over the authentication lifecycle. It is the difference between "this will eventually stop working" and "this stops working right now."

---

## How does it work?

### Types of invalidation

```
  ┌────────────────────────────────────────────────────┐
  │  Type              How                  Speed      │
  │  ──────────────────────────────────────────────── │
  │  Session delete    DELETE FROM sessions  Instant    │
  │  Session logout    Set status=revoked    Instant    │
  │  Key rotation      Mark key retired      Instant*   │
  │  JWT expiry        Wait for exp claim    Up to TTL  │
  │  Cookie clear      Remove from browser   Client-side│
  │  Token blocklist   Add jti to blocklist  Instant    │
  └────────────────────────────────────────────────────┘
  * Key rotation is instant for new tokens, but existing
    tokens remain valid until their exp.
```

### Session invalidation flow

```
  Active session:
  ┌─────────────────┐     ┌──────────────────────┐
  │  Browser        │     │  Database             │
  │  Cookie: abc123 │ ──► │  sessions:            │
  │                 │     │  id=abc123 ✓ ACTIVE   │
  └─────────────────┘     └──────────────────────┘
  Request accepted.

  After invalidation:
  ┌─────────────────┐     ┌──────────────────────┐
  │  Browser        │     │  Database             │
  │  Cookie: abc123 │ ──► │  sessions:            │
  │  (still exists) │     │  (abc123 DELETED)     │
  └─────────────────┘     └──────────────────────┘
  Request rejected → 401 → login page.
```

### Cascade invalidation

```
  User invalidation:
  Invalidate user account
       │
       ├── Delete ALL sessions for this user
       │   └── All devices logged out instantly
       │
       └── Existing JWTs still valid for up to 5 min
           └── Then all access fully terminated

  Tenant invalidation:
  Invalidate entire tenant
       │
       ├── Delete ALL sessions for ALL users in tenant
       │   └── Every user in tenant logged out
       │
       └── Existing JWTs expire within 5 min
           └── Tenant fully isolated
```

---

## How does volta-auth-proxy use it?

### Session invalidation (logout)

When a user logs out, volta deletes the session from the database:

```
  POST /api/v1/auth/logout
       │
       ▼
  1. Read __volta_session cookie → session ID
  2. DELETE FROM sessions WHERE id = session_id
  3. Set-Cookie: __volta_session=; Max-Age=0  (clear cookie)
  4. Return 200 OK
```

This invalidation is instant on the server side. Even if the browser still has the cookie, the next request will fail because the session no longer exists in the database.

### JWT invalidation (indirect)

JWTs cannot be directly invalidated (they are self-contained). volta handles this through the combination of:

1. **Session invalidation**: No new JWTs can be issued once the session is deleted
2. **Short TTL**: Existing JWTs expire within 5 minutes maximum
3. **[Key rotation](key-rotation.md)**: In emergency, rotate the signing key to invalidate ALL tokens

```
  Invalidation propagation timeline:
  ┌──────────────────────────────────────────────┐
  │  T+0:00  Session invalidated (logout)        │
  │  T+0:00  New JWT requests → 401 (no session) │
  │  T+0:01  Old JWT still works (4:59 left)     │
  │  T+5:00  Old JWT expired. Fully invalidated. │
  │                                              │
  │  Gap: 0-5 minutes of residual JWT validity   │
  │  This is volta's trade-off: stateless JWT    │
  │  verification vs. instant invalidation.      │
  └──────────────────────────────────────────────┘
```

### Cookie invalidation

Clearing the browser cookie is done by setting `Max-Age=0`:

```
Set-Cookie: __volta_session=; Max-Age=0; Path=/; HttpOnly; Secure
```

But this is client-side only. The server must also delete the session record. Both are required for complete invalidation.

---

## Common mistakes and attacks

### Mistake 1: Only clearing the cookie (client-side invalidation)

If you only clear the cookie without deleting the server-side session, the session remains valid. An attacker who captured the session ID before logout can continue using it.

### Mistake 2: Soft-deleting instead of hard-deleting

Marking a session as `status=inactive` but still accepting it in queries is not invalidation. Ensure your session lookup query filters out inactive sessions. Or better, hard-delete them.

### Mistake 3: Not invalidating all sessions on password change

When a user changes their password, all existing sessions should be invalidated (except the current one). Otherwise, an attacker who compromised the old password still has active sessions.

### Mistake 4: Forgetting to invalidate on role changes

If a user is demoted from admin, their existing JWTs still carry `volta_roles: ["admin"]` until they expire. For sensitive operations, always verify roles against the database, not just the JWT.

### Attack: Race condition exploitation

An attacker uses a valid JWT to initiate a long-running operation. The session is invalidated during the operation. If the operation does not re-check authorization at completion, the attacker's action succeeds despite invalidation.

---

## Further reading

- [revoke.md](revoke.md) -- A specific form of invalidation for keys and tokens
- [session-expiry.md](session-expiry.md) -- Passive invalidation by time
- [session.md](session.md) -- The primary target of invalidation in volta
- [propagation.md](propagation.md) -- How invalidation spreads through the system
- [token-theft.md](token-theft.md) -- When immediate invalidation is critical
