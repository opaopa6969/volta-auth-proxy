# Browser Back Button Cache

[Japanese / 日本語](browser-back-button-cache.ja.md)

---

## What is it?

When you press the browser's "Back" button, the browser often shows you a stored snapshot of the previous page instead of fetching it from the server again. This is called the back-forward cache (bfcache). It is designed to make navigation feel instant, but it creates a security problem: the page you see may be outdated, and on authentication pages, it may show a logged-in session that has already ended.

---

## Why does it matter?

Imagine this scenario on a shared computer at a library:

1. Alice logs in to the app, sees her dashboard with personal data.
2. Alice clicks "Logout." The session is destroyed on the server.
3. Alice closes the tab and walks away.
4. Bob opens the browser, presses the Back button a few times.
5. Bob sees Alice's cached dashboard -- complete with her name, email, and data.

Alice did everything right. She logged out. But the browser's back-forward cache served a stale snapshot. This is not a hypothetical risk; it is a well-documented attack vector on shared and public computers.

---

## Simple example

Without protection:
```
User logs in    ->  Dashboard (cached in bfcache)
User logs out   ->  Login page
User hits Back  ->  Dashboard shown from bfcache! (stale, but visible)
```

With `Cache-Control: no-store`:
```
User logs in    ->  Dashboard (NOT cached)
User logs out   ->  Login page
User hits Back  ->  Browser fetches from server -> Server sees no session -> Redirect to login
```

The key is `no-store`. This directive tells the browser not to retain the page for the back-forward cache at all.

---

## In volta-auth-proxy

volta protects against back-button cache attacks by applying `setNoStore()` to every authenticated page:

```java
private static void setNoStore(Context ctx) {
    ctx.header("Cache-Control", "no-store, no-cache, must-revalidate, private");
    ctx.header("Pragma", "no-cache");
}
```

This means:

- After logout, pressing Back shows the login page (not stale dashboard content).
- The session list page (`/sessions`) is never cached, so switching users on a shared machine does not leak session data.
- The invitation acceptance page is never cached, preventing stale invite states from being displayed.

volta also destroys the server-side session on logout and clears the session cookie, so even if a browser somehow serves a cached page, any attempt to interact with it will fail authentication and redirect to login.

See also: [data-leakage-via-cache.md](data-leakage-via-cache.md), [no-store-vs-no-cache.md](no-store-vs-no-cache.md)
