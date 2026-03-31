# Session

[日本語版はこちら](session.ja.md)

---

## What is it?

A session is a way for a server to remember who you are across multiple requests. When you log in, the server creates a session (a record of your login state) and gives your browser a session ID (usually in a cookie). On every subsequent request, the browser sends the session ID, and the server looks up your session to know who you are.

Think of it like a hotel key card. When you check in, the front desk creates a record (room number, checkout date, your name) and gives you a key card. The key card itself does not contain your personal information -- it is just a number. But when you swipe it at a door, the hotel's system looks up that number and decides whether to let you in.

---

## Why does it matter?

HTTP is "stateless" -- each request is independent. Without sessions, the server would forget you between every page load. Sessions are what make persistent login possible.

Without proper session management:

- Users would have to log in on every request
- Logout would not work (there is nothing to invalidate)
- Concurrent session limits would be impossible to enforce
- You could not track who is currently logged in or revoke access

---

## How does it work?

### Server-side sessions (what volta uses)

```
  Login:
  ══════
  1. User authenticates (Google OIDC)
  2. Server creates a session record in the database:
     ┌─────────────────────────────────────────────┐
     │  id:           550e8400-e29b-41d4-...        │
     │  user_id:      user-uuid                     │
     │  tenant_id:    tenant-uuid                   │
     │  created_at:   2026-03-31T09:00:00Z          │
     │  expires_at:   2026-03-31T17:00:00Z (8 hrs)  │
     │  ip_address:   192.168.1.100                 │
     │  user_agent:   Chrome/120 on macOS           │
     │  csrf_token:   Kj8mX2pQ...                   │
     └─────────────────────────────────────────────┘
  3. Server sends the session ID as a cookie:
     Set-Cookie: __volta_session=550e8400-e29b-41d4-...

  Subsequent requests:
  ═══════════════════
  1. Browser sends: Cookie: __volta_session=550e8400-...
  2. Server looks up session in database
  3. If valid and not expired:
     - Extend expiry (sliding window)
     - Return user identity
  4. If invalid or expired:
     - Return 401 Unauthorized

  Logout:
  ═══════
  1. Server marks session as invalidated (sets invalidated_at)
  2. Server removes the cookie from the browser
  3. Subsequent requests with the old session ID get 401
```

### Server-side vs cookie-based sessions

There are two fundamentally different approaches:

```
  Server-side sessions (volta's approach):
  ┌─────────────────┐     ┌──────────────────────────────┐
  │ Cookie:          │     │ Database (sessions table):    │
  │ session_id=UUID  │ ──► │ - user_id                    │
  │                  │     │ - tenant_id                  │
  │ (just a key,     │     │ - expires_at                 │
  │  no data)        │     │ - ip_address                 │
  └─────────────────┘     │ - csrf_token                 │
                          └──────────────────────────────┘

  Pros: ✓ Can revoke instantly (delete from DB)
        ✓ Session data is private (not in cookie)
        ✓ Cookie is tiny (just a UUID)
        ✓ Can list/manage active sessions

  Cons: ✗ Requires database lookup on every request
        ✗ Database is a dependency


  Cookie-based sessions (NOT what volta uses):
  ┌──────────────────────────────────────────────┐
  │ Cookie:                                       │
  │ session=BASE64(ENCRYPTED({                    │
  │   user_id: "user-uuid",                      │
  │   tenant_id: "tenant-uuid",                  │
  │   expires_at: "2026-03-31T17:00:00Z",        │
  │   roles: ["ADMIN"]                           │
  │ }))                                           │
  │                                               │
  │ (all data in cookie, signed or encrypted)     │
  └──────────────────────────────────────────────┘

  Pros: ✓ No database needed for session lookup
        ✓ Scales easily (no shared state)

  Cons: ✗ Cannot revoke without a blocklist
        ✗ Cookie size limits (4KB)
        ✗ Data visible to client (if only signed, not encrypted)
        ✗ Cannot list active sessions
```

volta chose server-side sessions because revocation and session management are essential features.

### Sliding window expiry

volta uses a "sliding window" for session expiry:

```
  Initial login (9:00 AM):
  ├── Session created
  └── Expires at: 5:00 PM (8 hours later)

  User makes a request at 10:30 AM:
  ├── Session found and valid
  ├── Expiry extended to: 6:30 PM (8 hours from NOW)
  └── Response sent

  User makes a request at 2:00 PM:
  ├── Session found and valid
  ├── Expiry extended to: 10:00 PM (8 hours from NOW)
  └── Response sent

  User goes home, does nothing for 8+ hours:
  ├── Session expires at 10:00 PM
  └── Next request gets 401 → redirect to login
```

This means active users are never interrupted by session expiry, while inactive sessions expire after 8 hours of inactivity.

---

## How does volta-auth-proxy use sessions?

### Session lifecycle

```
  Login (Google OIDC callback)
  │
  ├── Check: Does user have >= 5 active sessions?
  │   └── Yes: Revoke the oldest session(s)
  │
  ├── Generate new session ID (UUID)
  ├── Generate CSRF token (random)
  ├── Store in sessions table with:
  │   - user_id, tenant_id
  │   - IP address, user agent
  │   - 8-hour expiry
  │   - CSRF token
  │
  ├── Set cookie: __volta_session=<uuid>
  └── Redirect to app

  Every request:
  │
  ├── Read __volta_session cookie
  ├── Look up session in DB
  ├── Check: valid? not expired? not invalidated?
  │   ├── No: Return 401
  │   └── Yes: Continue
  │
  ├── Check: Is the user still a member of the session's tenant?
  │   ├── No: Return 401
  │   └── Yes: Continue
  │
  ├── Extend session expiry (sliding window)
  └── Return user identity (AuthPrincipal)

  Logout:
  │
  ├── Mark session as invalidated in DB
  ├── Remove cookie from browser
  └── Redirect to login
```

### Concurrent session limit

volta limits each user to 5 concurrent sessions. This prevents:

- Credential sharing (too many devices = shared account)
- Session accumulation (old sessions lingering forever)

When a user logs in with 5 existing sessions, the oldest session is revoked:

```java
// AuthService.java
int current = store.countActiveSessions(principal.userId());
if (current >= MAX_CONCURRENT_SESSIONS) {
    int revokeCount = (current - MAX_CONCURRENT_SESSIONS) + 1;
    store.revokeOldestActiveSessions(principal.userId(), revokeCount);
}
```

### Session management UI

Users can view and manage their sessions at `/settings/sessions`:

- See all active sessions (device, IP, last active time)
- Revoke individual sessions (e.g., "I lost my phone")
- Revoke all sessions (emergency "log out everywhere")

### Session fixation attack and prevention

**What is session fixation?**

```
  1. Attacker visits volta, gets a session ID: session=ATTACKER_SESSION
  2. Attacker tricks the victim into using this session ID
     (e.g., by setting the cookie via XSS or a specially crafted link)
  3. Victim logs in. The server upgrades ATTACKER_SESSION to authenticated.
  4. Attacker uses ATTACKER_SESSION -- they are now logged in as the victim.
```

**volta's prevention:**

volta generates a completely new session ID on every login. The old session ID (if any) is never reused:

```java
// AuthService.java - issueSession()
UUID sessionId = SecurityUtils.newUuid();  // Always a new UUID
```

Even if an attacker plants a session ID, it gets replaced when the victim logs in. The attacker's old session ID becomes invalid.

---

## Common mistakes and attacks

### Mistake 1: Predictable session IDs

If session IDs are sequential (1, 2, 3...) or predictable, an attacker can guess other users' session IDs. volta uses `UUID.randomUUID()` which is cryptographically random.

### Mistake 2: Not expiring sessions

Sessions that never expire mean a stolen session is useful forever. volta uses 8-hour sliding expiry.

### Mistake 3: Not invalidating on logout

Some systems delete the cookie but leave the session valid in the database. If the attacker has the session ID (copied before logout), they can still use it. volta marks the session as invalidated in the database.

### Mistake 4: Not regenerating session on login

See "Session fixation" above. Always create a new session on login.

### Attack: Session hijacking

If an attacker obtains a valid session ID (via XSS, network sniffing, etc.), they can impersonate the user. Defenses include:

- **HttpOnly** cookies (prevent XSS exfiltration)
- **Secure** flag (prevent HTTP sniffing)
- **IP/User-Agent tracking** (detect suspicious changes)
- **Short expiry** (limit the window of opportunity)
- **Session revocation** (let users kill compromised sessions)

volta implements all of these.

---

## Further reading

- [OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html) -- Comprehensive security guide.
- [cookie.md](cookie.md) -- Cookie attributes that protect sessions.
- [csrf.md](csrf.md) -- CSRF tokens stored in sessions.
- [jwt.md](jwt.md) -- How volta uses JWTs alongside sessions (not instead of).
