# JWT vs Session

[日本語版はこちら](jwt-vs-session.ja.md)

---

## What is it?

These are two different approaches to remembering who a user is across requests:

- **Session:** The server stores user state (in a database or cache) and gives the browser a short ID (cookie) to look it up. The session ID is meaningless on its own -- it's just a pointer.
- **JWT:** The token itself contains all the user information, signed cryptographically. The server doesn't need to look anything up. It just verifies the signature and reads the claims.

Think of it as a library card vs. a letter of introduction. A library card (session ID) means nothing without the library's database. A signed letter of introduction (JWT) is self-contained -- anyone who trusts the signer can read it.

---

## Why does it matter?

| Aspect | Session | JWT |
|--------|---------|-----|
| **State** | Stateful -- server must store data | Stateless -- token carries data |
| **Revocation** | Easy -- delete the row | Hard -- token is valid until it expires |
| **Scalability** | Needs shared storage across servers | Any server can verify independently |
| **Size** | Cookie is tiny (UUID) | Token can be large (1KB+) |
| **Best for** | Browsers (cookie transport) | APIs, mobile apps, service-to-service |

Neither is universally better. The right choice depends on the consumer.

---

## A simple example

**Session flow (browser):**
```
Browser -> GET /dashboard
           Cookie: volta_session=abc-123
Server  -> Look up abc-123 in Postgres
           Found: user_id=42, tenant_id=7, role=ADMIN
           Return the dashboard
```

**JWT flow (API client):**
```
App     -> GET /api/v1/users/me
           Authorization: Bearer eyJhbGci...
Server  -> Verify signature, read claims
           sub=42, volta_tid=7, volta_roles=[ADMIN]
           Return user data (no database lookup needed)
```

---

## In volta-auth-proxy

volta uses **both** -- sessions for browsers, JWTs for everything else:

**Browser users** get a session cookie (`volta_session`). This cookie is `HttpOnly` (JavaScript can't read it) and `SameSite=Lax` (CSRF protection). The session lives in Postgres and can be revoked instantly.

**API clients and downstream apps** use JWTs via the `Authorization: Bearer` header. JWTs are short-lived (5 minutes, `JWT_TTL_SECONDS=300`) to minimize the revocation problem. If a JWT needs to be "revoked," you just wait 5 minutes for it to expire.

**The bridge:** When a browser makes an API call (via volta-sdk-js), the SDK first calls `/auth/token` with the session cookie to get a fresh JWT, then uses that JWT for the actual API call. This gives browsers the security of sessions (revocable, HttpOnly) while giving APIs the convenience of JWTs (stateless, self-contained).

```
Browser with session cookie
    |
    v
/auth/token (exchanges session for short-lived JWT)
    |
    v
API call with Authorization: Bearer <JWT>
```

This dual approach is a best-of-both-worlds design that's common in modern auth gateways.

---

## See also

- [session-storage-strategies.md](session-storage-strategies.md) -- How volta stores sessions
- [jwt-payload.md](jwt-payload.md) -- What's inside a volta JWT
- [bearer-scheme.md](bearer-scheme.md) -- How JWTs are transmitted in HTTP headers
