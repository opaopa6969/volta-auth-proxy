# Session Fixation

## What is it?

Session fixation is an attack where the attacker sets (or "fixes") a victim's session ID to a value the attacker already knows, BEFORE the victim logs in. When the victim then authenticates, the server upgrades that existing session to an authenticated state -- and because the attacker knows the session ID, they can now use it to access the victim's account.

Think of it like this: you go to a hotel, and a stranger hands you a room key before you check in. You use that key at the front desk, and the desk clerk activates it for your room. Now both you and the stranger have a working key to your room.

The attack is sneaky because the victim does everything normally -- they log in with their own credentials, see their own dashboard -- and never realize that someone else also has access to their session.

## Why does it matter?

Session fixation is dangerous because:

1. **It bypasses authentication.** The attacker does not need to know the victim's password. They just need to get the victim to use a session ID the attacker controls.
2. **It is hard to detect.** From the server's perspective, the session looks normal. The user authenticated properly. There is nothing suspicious about the session itself.
3. **It works with otherwise secure systems.** Even if you use strong passwords, multi-factor authentication, and encrypted connections, session fixation can still succeed if the session ID is not regenerated at login.

## How does it work?

Here is the attack step by step:

```
  Attacker                      Server                      Victim
  |                             |                           |
  | 1. Get a valid session ID   |                           |
  |  GET /login                 |                           |
  |---------------------------->|                           |
  |  Set-Cookie: session=EVIL123|                           |
  |<----------------------------|                           |
  |                             |                           |
  | 2. Trick victim into using  |                           |
  |    session=EVIL123          |                           |
  |    (via link, XSS, or      |                           |
  |     meta tag injection)     |                           |
  |----------------------------------------------------->  |
  |                             |                           |
  |                             | 3. Victim logs in         |
  |                             |    with session=EVIL123   |
  |                             |<--------------------------|
  |                             |                           |
  |                             | 4. Server authenticates   |
  |                             |    user and KEEPS the     |
  |                             |    same session=EVIL123   |
  |                             |    (now authenticated)    |
  |                             |                           |
  | 5. Attacker uses            |                           |
  |    session=EVIL123          |                           |
  |---------------------------->|                           |
  |                             |                           |
  |    Server sees valid,       |                           |
  |    authenticated session    |                           |
  |    --> Attacker is in!      |                           |
```

### Ways the attacker can "fix" the session

- **URL parameter:** `https://your-app.com/login?sessionid=EVIL123` (if the app accepts session IDs in URLs)
- **Cookie injection via XSS:** Run `document.cookie = "session=EVIL123"` on the victim's browser
- **Meta tag or header injection:** If the attacker can inject HTML into the page
- **Subdomain cookie setting:** If the attacker controls a subdomain, they can set cookies for the parent domain

### The fix: regenerate session on login

The prevention is straightforward: when a user successfully authenticates, create a completely new session ID. Never reuse a pre-authentication session ID for a post-authentication session.

```
  VULNERABLE:                        SECURE:

  Before login:                      Before login:
  session = EVIL123                  session = EVIL123

  User authenticates                 User authenticates

  After login:                       After login:
  session = EVIL123 (same!)          session = NEW_RANDOM_456
  Attacker's ID still works          EVIL123 is invalidated
                                     Attacker's ID is useless
```

## How does volta-auth-proxy use it?

volta-auth-proxy is designed to be immune to session fixation through its architecture:

**New session on every login.** When a user completes OIDC authentication, volta always creates a brand new session with a fresh UUID:

```java
UUID sessionId = SecurityUtils.newUuid();  // Cryptographically random UUID
store.createSession(sessionId, ...);       // New database record
setSessionCookie(ctx, sessionId, ...);     // New cookie sent to browser
```

The old session (if any) is never carried forward. A new, random session ID is generated and a new database record is created.

**Server-side session storage.** volta's sessions are stored in PostgreSQL, not in the cookie itself. The cookie contains only an opaque UUID. This means:
- An attacker cannot create a valid session by fabricating a cookie value.
- A session ID only becomes valid when volta's code explicitly creates it in the database.
- There is no way to "fix" a session before login because non-existent session IDs are rejected.

**Old session invalidation on tenant switch.** When a user switches tenants, volta revokes the old session and creates a new one:

```java
// Revoke old session
store.revokeSession(UUID.fromString(oldSessionRaw));

// Create new session with new ID
UUID sessionId = authService.issueSession(switched, ...);
setSessionCookie(ctx, sessionId, ...);
```

**Concurrent session limits.** volta limits each user to 5 concurrent sessions. When the limit is exceeded, the oldest sessions are automatically revoked. This provides additional protection: even if an attacker somehow fixed a session, it would be automatically cleaned up if the user has enough other sessions.

The defense layers:

```
  Attacker tries to fix a session:

  Layer 1: Session IDs are cryptographically random UUIDs
           --> Attacker cannot predict or fabricate valid IDs

  Layer 2: Sessions only exist when created by volta's code
           --> No pre-existing session to hijack

  Layer 3: Login always creates a NEW session
           --> Any pre-login session ID becomes irrelevant

  Layer 4: Old sessions are revoked on login/switch
           --> Even if a stale session existed, it is invalidated

  Layer 5: Max 5 concurrent sessions per user
           --> Old sessions are automatically pruned
```

## Common mistakes

**1. Reusing the session ID after authentication.**
This is the root cause of session fixation. The fix is simple: always generate a new session ID when the user's privilege level changes (login, role change, tenant switch).

**2. Accepting session IDs from URL parameters.**
Allowing session IDs in the URL (like `?JSESSIONID=...`) makes it trivial for attackers to fix sessions via crafted links. volta only accepts session IDs from HttpOnly cookies.

**3. Not invalidating the old session.**
Creating a new session is necessary but not sufficient. You should also invalidate (delete or mark as inactive) the old session. Otherwise, both the old and new session IDs work, and the attacker still has access through the old one.

**4. Using sequential or predictable session IDs.**
If session IDs are predictable (like incrementing integers), an attacker does not even need to fix the session -- they can just guess it. volta uses `UUID.randomUUID()`, which provides 122 bits of randomness.

**5. Assuming HTTPS prevents session fixation.**
HTTPS prevents network eavesdropping but does not prevent an attacker from directing the victim to a URL with a pre-set session ID or using other injection techniques. Session fixation is a logic flaw, not a transport flaw.

**6. Only regenerating sessions on login, not on privilege escalation.**
If a regular user becomes an admin, that is also a privilege change that warrants a new session. volta regenerates sessions when switching tenants (which may change roles).
