# no-store vs no-cache

[Japanese / 日本語](no-store-vs-no-cache.ja.md)

---

## What is it?

`no-store` and `no-cache` are two directives you can put in a Cache-Control header, and despite their similar names, they do very different things. `no-store` means "do not save this response anywhere, ever." `no-cache` means "you may save it, but you must check with the server before using it." The difference matters enormously for security.

---

## Why does it matter?

Many developers assume `no-cache` means "do not cache." It does not. A response marked `no-cache` can still be stored on disk by the browser. It just has to be revalidated before use. That means the data is physically sitting in the browser's cache folder. If the machine is shared, another user could find it. If someone inspects the browser cache, they see everything.

For authentication pages and sensitive data, `no-cache` is not enough. You need `no-store`.

---

## Simple example

| Directive | Browser saves to disk? | Browser checks server before using? | Safe for auth pages? |
|-----------|----------------------|-------------------------------------|---------------------|
| `no-store` | No | N/A (nothing saved) | Yes |
| `no-cache` | Yes | Yes (every time) | No -- data still on disk |
| `max-age=0` | Yes | Yes | No -- data still on disk |
| (no header) | Yes | Maybe | No |

Think of it this way:

- **no-store** = "Shred this document after reading it"
- **no-cache** = "Keep this document in the filing cabinet, but call me to confirm it is still current before you use it"

Both result in a fresh server request, but only `no-store` ensures the data never hits the disk.

---

## In volta-auth-proxy

volta uses both directives together for maximum safety:

```java
ctx.header("Cache-Control", "no-store, no-cache, must-revalidate, private");
```

This is intentionally redundant. Here is why each piece is included:

- **no-store** -- The real workhorse. Tells the browser not to save the response at all.
- **no-cache** -- Belt-and-suspenders. Some older caches ignore `no-store` but respect `no-cache`.
- **must-revalidate** -- Prevents caches from serving stale content even under error conditions.
- **private** -- Ensures no shared proxy or CDN stores the response.

volta applies this to every authentication-related endpoint: the login page, the callback handler, the session dashboard, and all API responses that include user data. Static assets (CSS, JS) use a different, permissive policy.

See also: [cache-control.md](cache-control.md), [data-leakage-via-cache.md](data-leakage-via-cache.md)
