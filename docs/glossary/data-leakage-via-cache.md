# Data Leakage via Cache

[Japanese / 日本語](data-leakage-via-cache.ja.md)

---

## What is it?

Data leakage via cache happens when sensitive information -- user names, email addresses, session tokens, or personal settings -- gets stored in a browser cache, CDN cache, or proxy cache and is later exposed to someone who should not see it. The data was meant for one specific user, but a caching layer kept a copy and served it to someone else (or made it accessible on a shared machine).

---

## Why does it matter?

Cache-based data leaks are especially dangerous because they are silent. There is no error message, no log entry, no alarm. The wrong person simply sees the right person's data. Real-world scenarios include:

- **Shared computers**: User A logs out, User B presses Back and sees User A's dashboard (see [browser-back-button-cache.md](browser-back-button-cache.md)).
- **CDN cache poisoning**: A CDN caches an authenticated API response and serves it to unauthenticated users.
- **Corporate proxy caching**: A company proxy caches a page with employee data and serves it to a different employee.
- **Disk forensics**: Even after logout, cached HTML files remain on disk and can be recovered.

---

## Simple example

A login dashboard response without proper cache headers:

```http
HTTP/1.1 200 OK
Content-Type: text/html

<h1>Welcome, alice@example.com</h1>
<p>Your API key: sk-abc123...</p>
```

The browser saves this to its disk cache. Later, on the same machine:

1. Alice logs out.
2. An attacker opens `chrome://cache` or browses the cache folder on disk.
3. The attacker finds Alice's API key in the cached HTML.

With proper headers, this is prevented:

```http
HTTP/1.1 200 OK
Content-Type: text/html
Cache-Control: no-store, no-cache, must-revalidate, private
Pragma: no-cache

<h1>Welcome, alice@example.com</h1>
<p>Your API key: sk-abc123...</p>
```

Now the browser does not save the response to disk at all.

---

## In volta-auth-proxy

volta prevents cache-based data leakage through a consistent strategy:

**1. All auth pages use `setNoStore()`:**

Every endpoint that serves user-specific content calls:

```java
ctx.header("Cache-Control", "no-store, no-cache, must-revalidate, private");
ctx.header("Pragma", "no-cache");
```

This covers the login page, callback, session dashboard, invitation pages, and all API endpoints returning user data.

**2. Short-lived JWTs (5 minutes):**

Even if a JWT were somehow cached, it expires in 300 seconds (`JWT_TTL_SECONDS=300`). An attacker who finds a cached token has a very narrow window to use it.

**3. Session cookie is HttpOnly:**

The session cookie (`__volta_session`) is set with `HttpOnly; SameSite=Lax` and `Secure` when on HTTPS. It cannot be read by JavaScript or sent in cross-site requests, reducing the chance it appears in cached page content.

**4. Server-side session validation:**

Even if a browser serves a cached page, any action the user takes will trigger a server-side session check. If the session has been revoked (logout), the request fails and the user is redirected to login.

See also: [browser-back-button-cache.md](browser-back-button-cache.md), [no-store-vs-no-cache.md](no-store-vs-no-cache.md), [token-theft.md](token-theft.md)
