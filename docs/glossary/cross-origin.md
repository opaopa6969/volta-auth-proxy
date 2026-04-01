# Cross-Origin

[日本語版はこちら](cross-origin.ja.md)

---

## In one sentence?

Cross-origin means a webpage on one [domain](domain.md) is trying to communicate with a [server](server.md) on a different domain -- and the [browser](browser.md) has strict rules about whether to allow it.

---

## Ordering from a neighboring restaurant

You're sitting in Restaurant A, but the dish you want is on Restaurant B's menu across the street:

| Scenario | Web equivalent |
|---|---|
| Restaurant A's waiter brings you their own menu | Same-origin request (allowed by default) |
| You ask Restaurant A's waiter to get a dish from Restaurant B | Cross-origin request (blocked by default) |
| Restaurant B says "Yes, we deliver to Restaurant A's customers" | [CORS](cors.md) headers allow the request |
| Restaurant B says nothing | Browser blocks the response |

An **origin** is the combination of three things:

```
  https://app.example.com:443
  ──┬──   ───────┬───────  ─┬─
  scheme      domain       port

  Different origin if ANY of these differ:
  http://app.example.com        ← different scheme
  https://api.example.com       ← different domain
  https://app.example.com:8080  ← different port
```

---

## Why do we need this?

Without cross-origin restrictions (the same-origin policy):

- A malicious site (`evil.com`) could make requests to `your-bank.com` using your [cookies](cookie.md)
- The response (your bank balance, transactions) would be readable by `evil.com`'s JavaScript
- Every website you visit could secretly interact with every other site where you're logged in
- [Session](session.md) hijacking would be trivial

The same-origin policy is one of the most important security mechanisms in the web platform. [CORS](cors.md) is the controlled way to relax it when needed.

---

## Cross-origin in volta-auth-proxy

volta deals with cross-origin requests in several ways:

**Where cross-origin occurs in volta:**

```
  https://app.acme.example.com    (app frontend)
           │
           │ JavaScript fetch() to:
           │ https://auth.example.com/auth/refresh
           │
           ▼
  Different origin! (different subdomain)
  Browser sends preflight OPTIONS request first
  volta must respond with proper CORS headers
```

**volta's CORS configuration:**

volta explicitly configures which origins are allowed to make cross-origin requests:

| Header | volta's value | Purpose |
|---|---|---|
| `Access-Control-Allow-Origin` | Specific allowed origins | NOT `*` -- only trusted [domains](domain.md) |
| `Access-Control-Allow-Credentials` | `true` | Allows [cookies](cookie.md) to be sent cross-origin |
| `Access-Control-Allow-Methods` | `GET, POST, OPTIONS` | Which [HTTP](http.md) methods are allowed |
| `Access-Control-Allow-Headers` | `Content-Type, Authorization` | Which [headers](header.md) the client can send |

**Why volta can't use `Access-Control-Allow-Origin: *`:**

If you set the origin to `*` (allow everyone), browsers refuse to send [cookies](cookie.md) with the request. Since volta needs the session cookie for `/auth/refresh`, it must specify exact origins.

**The ForwardAuth bypass:**

When using [ForwardAuth](forwardauth.md), cross-origin is not an issue because:

- The [browser](browser.md) talks to `app.acme.example.com` (same origin as the app)
- The [reverse proxy](reverse-proxy.md) internally talks to volta (server-to-server, no browser involved)
- No cross-origin request ever happens from the browser's perspective

This is one of the advantages of the ForwardAuth pattern over direct API calls.

---

## Concrete example

What happens when an app makes a cross-origin request to volta:

1. User is on `https://app.acme.example.com` (logged in, has session [cookie](cookie.md))
2. App's JavaScript needs to refresh the [JWT](jwt.md):
   ```javascript
   fetch('https://auth.example.com/auth/refresh', {
     method: 'POST',
     credentials: 'include'  // ← send cookies cross-origin
   })
   ```
3. [Browser](browser.md) detects this is cross-origin (`app.acme.example.com` to `auth.example.com`)
4. Browser sends a **preflight** OPTIONS request:
   ```
   OPTIONS /auth/refresh HTTP/1.1
   Host: auth.example.com
   Origin: https://app.acme.example.com
   Access-Control-Request-Method: POST
   ```
5. volta responds with CORS headers:
   ```
   HTTP/1.1 204 No Content
   Access-Control-Allow-Origin: https://app.acme.example.com
   Access-Control-Allow-Methods: POST
   Access-Control-Allow-Credentials: true
   Access-Control-Max-Age: 3600
   ```
6. Browser checks: Does the origin match? Are credentials allowed? Is POST allowed? -- All yes.
7. Browser sends the actual POST request with the session cookie
8. volta verifies the session, returns a new JWT
9. Browser checks: Does `Access-Control-Allow-Origin` in the response match? -- Yes.
10. JavaScript receives the response with the new JWT

If volta had NOT returned the correct CORS headers at step 5, the browser would block the request at step 6 and JavaScript would get a network error.

---

## Learn more

- [CORS](cors.md) -- The mechanism that controls cross-origin access
- [Domain](domain.md) -- What defines an origin's domain component
- [Cookie](cookie.md) -- How cookies interact with cross-origin requests
- [CSRF](csrf.md) -- An attack that exploits cross-origin request behavior
- [SameSite](samesite.md) -- Cookie attribute that restricts cross-origin cookie sending
- [ForwardAuth](forwardauth.md) -- A pattern that avoids cross-origin issues entirely
- [Browser](browser.md) -- The enforcer of cross-origin restrictions
