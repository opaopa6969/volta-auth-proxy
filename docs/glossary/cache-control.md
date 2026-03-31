# Cache-Control Header

[Japanese / 日本語](cache-control.ja.md)

---

## What is it?

Cache-Control is an HTTP header that tells browsers (and any proxies in between) how to handle caching for a given response. It is a single line in the server's response that says things like "save this for 60 seconds" or "never save this at all." Every time your browser loads a page, it checks Cache-Control to decide whether to use a stored copy or fetch a fresh one from the server.

---

## Why does it matter?

Caching makes the web fast. Without it, every page load would require a full round trip to the server, even for content that has not changed. But caching the *wrong* thing is dangerous. If a page showing "Welcome, alice@example.com" gets cached and a different user sees it, you have a data leak. Cache-Control is the mechanism that lets developers draw the line between "cache this aggressively" and "never store this."

Getting Cache-Control wrong is one of the most common causes of both performance problems (too strict) and security incidents (too lenient).

---

## Simple example

A server sends this response header:

```
Cache-Control: public, max-age=3600
```

This means:

- **public** -- Any cache (browser, CDN, proxy) may store this response.
- **max-age=3600** -- The cached copy is valid for 3600 seconds (1 hour).

For the next hour, the browser will not contact the server at all for this resource. It will just use the copy it already has.

Now compare this:

```
Cache-Control: no-store, private
```

This means:

- **no-store** -- Do not save this response anywhere. Not in memory, not on disk.
- **private** -- Even if you did cache it, only the end user's browser would be allowed to (not a shared CDN).

This is what you use for pages containing personal or sensitive data.

---

## In volta-auth-proxy

volta uses Cache-Control in two distinct ways:

**For static assets** (CSS, JavaScript, images), volta sets permissive caching:

```java
ctx.header("Cache-Control", "public, max-age=60, stale-while-revalidate=86400");
```

This tells browsers and CDNs to cache static files for 60 seconds, and if they are stale, serve the old version while fetching a fresh one in the background (for up to a day).

**For all authentication pages and API responses**, volta calls a helper method `setNoStore()`:

```java
ctx.header("Cache-Control", "no-store, no-cache, must-revalidate, private");
ctx.header("Pragma", "no-cache");
```

This is the most aggressive "do not cache" setting possible. It ensures that login pages, session data, and user-specific responses are never stored by any cache. The `Pragma: no-cache` header is included for compatibility with HTTP/1.0 clients.

See also: [no-store-vs-no-cache.md](no-store-vs-no-cache.md), [private-vs-public.md](private-vs-public.md)
