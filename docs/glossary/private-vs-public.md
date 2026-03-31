# private vs public (Cache-Control)

[Japanese / 日本語](private-vs-public.ja.md)

---

## What is it?

`private` and `public` are Cache-Control directives that control *who* is allowed to cache a response. `public` means any cache in the chain can store it -- the user's browser, a CDN like Cloudflare, a corporate proxy, anything. `private` means only the end user's browser may cache it -- shared caches must not store it.

---

## Why does it matter?

When a response travels from a server to a browser, it may pass through multiple intermediaries: a CDN edge node, a reverse proxy, a corporate firewall. Each of these can potentially cache the response. If a response contains user-specific data (like "Hello, Alice" or a dashboard with personal settings), it must not be cached by a shared intermediary. Otherwise, Bob might receive Alice's cached page from the CDN.

The `private` directive tells all shared caches to back off. Only the user's own browser may store it.

---

## Simple example

```
                          CDN         Corporate Proxy       Browser
                         (shared)        (shared)          (private)

public, max-age=3600      saves           saves             saves
private, max-age=3600     skips           skips             saves
no-store                  skips           skips             skips
```

Use **public** for: CSS files, JavaScript bundles, marketing pages, images -- anything the same for every user.

Use **private** for: user dashboards, account settings, API responses with personal data -- anything user-specific.

Use **no-store** for: login pages, authentication callbacks, anything containing tokens or credentials.

---

## In volta-auth-proxy

volta uses `public` for static assets that are identical for every visitor:

```java
ctx.header("Cache-Control", "public, max-age=60, stale-while-revalidate=86400");
```

For all authentication endpoints and user-specific pages, volta uses `private` combined with `no-store`:

```java
ctx.header("Cache-Control", "no-store, no-cache, must-revalidate, private");
```

The `private` here acts as an extra safety net. Even though `no-store` already prevents caching, adding `private` ensures that if any shared cache misinterprets `no-store`, it at least knows the response is not meant to be shared across users.

This two-tier approach gives volta both performance (cached static assets) and security (never-cached auth data).

See also: [cache-control.md](cache-control.md), [data-leakage-via-cache.md](data-leakage-via-cache.md)
