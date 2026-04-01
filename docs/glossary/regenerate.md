# Regenerate

[日本語版はこちら](regenerate.ja.md)

---

## What is it?

Regeneration is creating a new version of something to replace the old one, specifically to prevent the old value from being reused. In authentication, the most important example is session ID regeneration -- generating a new session ID after login to prevent [session fixation](session-fixation.md) attacks.

Think of it like getting a new apartment key after a roommate moves out. The apartment is the same, your stuff is still inside, but the lock is changed. The old key no longer works. Anyone who copied the old key is locked out. The lock change is regeneration -- the apartment (session data) stays the same, but the identifier (key) is brand new.

Regeneration is different from rotation (which replaces cryptographic keys for the whole system) and from creation (which starts something new). Regeneration replaces an identifier while preserving the associated data and state.

---

## Why does it matter?

Without session ID regeneration, an attacker can exploit a well-known attack called [session fixation](session-fixation.md):

```
  Session fixation attack (without regeneration):
  ┌──────────────────────────────────────────────────┐
  │  1. Attacker visits site → gets session ID "abc" │
  │  2. Attacker sends victim a link with sid=abc    │
  │  3. Victim clicks link → browser uses sid=abc    │
  │  4. Victim logs in → session abc is now          │
  │     authenticated as the victim                  │
  │  5. Attacker uses session abc → IS the victim    │
  └──────────────────────────────────────────────────┘

  With regeneration:
  ┌──────────────────────────────────────────────────┐
  │  1-3. Same as above                              │
  │  4. Victim logs in → server regenerates          │
  │     session ID from "abc" to "xyz"               │
  │     → victim's browser gets new cookie "xyz"     │
  │  5. Attacker uses session "abc" → INVALID        │
  │     → Attack prevented!                          │
  └──────────────────────────────────────────────────┘
```

Regeneration is a critical defense that costs almost nothing to implement but prevents an entire class of attacks.

---

## How does it work?

### Session ID regeneration

```
  Before login:
  ┌──────────────┐     ┌───────────────────────┐
  │  Browser     │     │  Server                │
  │  Cookie: abc │ ──► │  session abc:          │
  │              │     │  authenticated: false   │
  │              │     │  user: null             │
  └──────────────┘     └───────────────────────┘

  User submits credentials (email + password)...
  Server validates credentials → SUCCESS

  REGENERATE session ID:
  ┌──────────────┐     ┌───────────────────────┐
  │  Browser     │     │  Server                │
  │  Cookie: xyz │ ──► │  session xyz:          │  ← NEW ID
  │  (new!)      │     │  authenticated: true   │
  │              │     │  user: alice            │
  └──────────────┘     └───────────────────────┘
                       │  session abc: DELETED   │  ← OLD ID gone
                       └───────────────────────┘
```

### When to regenerate

| Event | Regenerate? | Why |
|-------|------------|-----|
| Login (authentication) | Yes | Prevents session fixation |
| Privilege escalation | Yes | Prevents session elevation attacks |
| Password change | Yes | Old sessions may be compromised |
| Role change | Recommended | Different trust level = new session |
| After idle timeout | Yes (on re-auth) | Fresh session for fresh auth |

### The regeneration process

```
  1. Generate new session ID (crypto-random UUID)
  2. Copy session data to new ID
  3. Delete old session ID from database
  4. Set new cookie with new session ID
  5. Old session ID is now invalid

  ┌─────────────────────────────────────────────┐
  │  Old:  abc → { user: alice, tenant: acme }  │
  │                     │                       │
  │                     │ copy data              │
  │                     ▼                       │
  │  New:  xyz → { user: alice, tenant: acme }  │
  │                                             │
  │  Delete: abc → (gone)                       │
  │  Cookie: __volta_session=xyz                │
  └─────────────────────────────────────────────┘
```

---

## How does volta-auth-proxy use it?

### Session regeneration on login

When a user successfully authenticates, volta creates a brand-new session rather than reusing any existing session ID:

```
  Login flow:
  1. User submits credentials
  2. volta validates credentials (password check)
  3. volta generates new session ID: SecurityUtils.newUuid()
  4. volta inserts new row in sessions table
  5. volta sets __volta_session cookie with new ID
  6. Any pre-existing session for that browser is not reused
```

### Secure random generation

volta uses `java.util.UUID.randomUUID()` for session IDs, which uses `SecureRandom` internally -- ensuring the new session ID is unpredictable:

```java
public static UUID newUuid() {
    return UUID.randomUUID();
}
```

This means an attacker cannot predict what the new session ID will be after regeneration.

### Invite code regeneration

volta also uses regeneration for invite codes. Each invite generates a [crypto-random](crypto-random.md) code via `SecurityUtils.inviteCode()`:

```java
public static String inviteCode() {
    return randomUrlSafe(24);  // 24 bytes of SecureRandom, base64url encoded
}
```

If an invite needs to be re-sent, a new code is generated rather than reusing the old one. This prevents previously-intercepted codes from being used.

---

## Common mistakes and attacks

### Mistake 1: Not regenerating on login

The most common mistake. If the session ID survives login, session fixation is possible. Always generate a new session ID after successful authentication.

### Mistake 2: Regenerating the ID but not deleting the old one

If the old session ID still exists in the database, the attacker can still use it. The old record must be deleted (or marked invalid) when the new one is created.

### Mistake 3: Predictable new IDs

If the regenerated session ID is predictable (sequential counter, timestamp-only, weak random), an attacker can guess the new ID. Always use [crypto-random](crypto-random.md) generation.

### Mistake 4: Not regenerating on privilege changes

A user who escalates from member to admin should get a new session ID. Otherwise, a session ID obtained when the user was less privileged may have been observed by a less-trusted party.

### Attack: Race condition during regeneration

An attacker sends rapid requests during the brief moment between "old ID invalidated" and "new ID set." If the server is not careful, both IDs might work simultaneously for a brief period. Use atomic operations (database transaction) for regeneration.

---

## Further reading

- [session-fixation.md](session-fixation.md) -- The attack that regeneration prevents
- [session.md](session.md) -- Session lifecycle in volta
- [crypto-random.md](crypto-random.md) -- How new session IDs are generated
- [nonce.md](nonce.md) -- Another use of one-time random values
- [invalidation.md](invalidation.md) -- How old session IDs are invalidated during regeneration
