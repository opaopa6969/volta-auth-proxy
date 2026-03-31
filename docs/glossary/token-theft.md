# Token Theft

[Japanese / 日本語](token-theft.ja.md)

---

## What is it?

Token theft is when an attacker obtains a valid authentication token (like a JWT or session cookie) that belongs to another user. With the stolen token, the attacker can impersonate the victim -- make API calls, access data, and perform actions as if they were the legitimate user. The attacker does not need the user's password; the token is the proof of identity.

---

## Why does it matter?

Tokens are the keys to the kingdom in modern web applications. A stolen token grants immediate access without any further authentication. Unlike a stolen password (which may be mitigated by 2FA), a stolen token bypasses all login-time security checks because the authentication already happened. The damage depends on the token's scope and lifetime -- a long-lived admin token is far more dangerous than a short-lived read-only token.

---

## Simple example

Common ways tokens get stolen:

| Attack vector | How it works |
|--------------|-------------|
| **XSS (Cross-Site Scripting)** | Injected JavaScript reads `document.cookie` or `localStorage` and sends tokens to the attacker |
| **Network sniffing** | On unencrypted HTTP, anyone on the same network can read tokens in transit |
| **Log exposure** | Tokens accidentally logged in server logs, error messages, or analytics |
| **Browser extensions** | Malicious extensions read cookies or request headers |
| **Referrer leakage** | Token in URL is sent to third-party sites via the Referer header |

---

## In volta-auth-proxy

volta applies multiple layers of defense against token theft:

**HttpOnly cookies**:

```java
String cookie = AuthService.SESSION_COOKIE + "=" + sessionId
    + "; Path=/; Max-Age=" + sessionTtlSeconds
    + "; HttpOnly; SameSite=Lax";
if (ctx.req().isSecure()) {
    cookie += "; Secure";
}
```

- **HttpOnly**: JavaScript cannot read the session cookie via `document.cookie`. This blocks XSS-based cookie theft entirely.
- **Secure**: The cookie is only sent over HTTPS (when detected), preventing network sniffing.
- **SameSite=Lax**: The cookie is not sent on cross-site requests, reducing the attack surface for CSRF and cross-site token leakage.

**Short JWT expiry**:

JWTs expire in 5 minutes (`JWT_TTL_SECONDS=300`). Even if a JWT is stolen, the attacker has a very narrow window to use it. Sessions last longer (8 hours by default), but sessions are server-side and can be revoked.

**Server-side session revocation**:

Sessions are stored in the database, not just in cookies. If theft is suspected, an admin can revoke the session, immediately invalidating the cookie. volta also limits concurrent sessions to 5 per user and automatically revokes the oldest when the limit is exceeded.

**Tokens never in URLs**:

volta's OIDC flow uses the Authorization Code Flow, which means tokens travel server-to-server, not through browser URLs. The session ID is in a cookie (not a URL parameter), so it does not appear in browser history, referrer headers, or server access logs.

**No localStorage tokens**:

volta uses cookies for sessions, not localStorage or sessionStorage. This is deliberate -- `localStorage` is accessible to any JavaScript running on the page (including XSS payloads), while `HttpOnly` cookies are not.

See also: [replay-attack.md](replay-attack.md), [open-redirect.md](open-redirect.md), [data-leakage-via-cache.md](data-leakage-via-cache.md)
