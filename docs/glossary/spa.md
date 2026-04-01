# SPA (Single Page Application)

[日本語版はこちら](spa.ja.md)

---

## What is it?

A Single Page Application (SPA) is a web application that loads one [HTML](html.md) page and then dynamically rewrites the content using [JavaScript](javascript.md), without ever loading a new page from the [server](server.md). When you click a link in a SPA, the browser does not navigate to a new URL in the traditional sense -- JavaScript intercepts the click, fetches data from an [API](api.md), and updates the page in place.

Think of it like a flip book versus a stack of postcards. A traditional website is like a stack of postcards -- each page is a separate card, and to see a new one, you throw away the current card and pick up the next. A SPA is like a flip book -- you have one book, and pages change by flipping, but you never put the book down. The "container" stays the same; only the content changes.

Common SPA frameworks include React, Vue, Angular, and Svelte. They all follow the same principle: the browser downloads one HTML file and a large JavaScript bundle, and from that point on, the JavaScript handles everything -- routing, rendering, API calls, and state management.

---

## Why does it matter?

SPAs have become the dominant way to build modern web application frontends. Understanding them matters for volta-auth-proxy because:

- **SPAs are the primary consumer of volta's [Internal API](internal-api.md)** -- they call `/api/v1/users/me`, `/api/v1/tenants`, etc. via JavaScript
- **SPAs handle authentication differently** -- they cannot use server-side [sessions](session.md) directly; they rely on [JWTs](jwt.md) and [cookies](cookie.md)
- **SPAs create unique security challenges** -- [XSS](xss.md) attacks can steal tokens from the browser, [CORS](cors.md) must be configured correctly
- **SPAs need volta-sdk-js** -- the [SDK](sdk.md) handles automatic JWT refresh, which is essential for SPAs

If you are building a [downstream app](downstream-app.md) that uses volta for authentication, it is likely a SPA.

---

## How does it work?

### Traditional website vs. SPA

```
  Traditional (Multi-Page Application):

  Browser                          Server
  ──────                           ──────
  GET /dashboard    ─────────────>  renders full HTML
                    <─────────────  <html>...</html>
  (whole page loads)

  Click "Settings"  ─────────────>  renders full HTML
                    <─────────────  <html>...</html>
  (whole page loads again)

  Click "Profile"   ─────────────>  renders full HTML
                    <─────────────  <html>...</html>
  (whole page loads again)


  SPA (Single Page Application):

  Browser                          Server
  ──────                           ──────
  GET /             ─────────────>  returns index.html + app.js
                    <─────────────  <html><script src="app.js">
  (page loads once)

  Click "Settings"  ─────────────>  GET /api/v1/settings (JSON)
  (JS intercepts)   <─────────────  {"theme":"dark","lang":"ja"}
  JS updates DOM    (no page reload)

  Click "Profile"   ─────────────>  GET /api/v1/users/me (JSON)
  (JS intercepts)   <─────────────  {"name":"Taro","role":"ADMIN"}
  JS updates DOM    (no page reload)
```

### The SPA architecture

```
  ┌──────────────────────────────────────────────────────┐
  │                    Browser                            │
  │                                                       │
  │  ┌─────────────────────────────────────────────────┐  │
  │  │              SPA (JavaScript)                    │  │
  │  │                                                  │  │
  │  │  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │  │
  │  │  │  Router   │  │  State   │  │  Components  │  │  │
  │  │  │ /dash     │  │ Manager  │  │  Dashboard   │  │  │
  │  │  │ /settings │  │ (Redux/  │  │  Settings    │  │  │
  │  │  │ /profile  │  │  Pinia)  │  │  Profile     │  │  │
  │  │  └──────────┘  └──────────┘  └──────────────┘  │  │
  │  │                      │                           │  │
  │  │               fetch("/api/...")                   │  │
  │  └──────────────────────┼──────────────────────────┘  │
  │                         │                              │
  └─────────────────────────┼──────────────────────────────┘
                            │ HTTP (JSON)
                            ▼
  ┌──────────────────────────────────────────────────────┐
  │              volta-auth-proxy (API)                   │
  │  /api/v1/users/me                                    │
  │  /api/v1/tenants                                     │
  │  /api/v1/admin/members                               │
  └──────────────────────────────────────────────────────┘
```

### Client-side routing

In a traditional website, the browser sends a new request to the server when you navigate to `/settings`. In a SPA, the URL changes but the browser does not send a request for a new page:

```
  URL: /dashboard           URL: /settings           URL: /profile
  ┌──────────────────┐      ┌──────────────────┐     ┌──────────────────┐
  │  ┌────────────┐  │      │  ┌────────────┐  │     │  ┌────────────┐  │
  │  │  Navbar    │  │      │  │  Navbar    │  │     │  │  Navbar    │  │
  │  ├────────────┤  │      │  ├────────────┤  │     │  ├────────────┤  │
  │  │            │  │      │  │            │  │     │  │            │  │
  │  │ Dashboard  │  │ ───> │  │ Settings   │  │ ──> │  │ Profile    │  │
  │  │ content    │  │      │  │ content    │  │     │  │ content    │  │
  │  │            │  │      │  │            │  │     │  │            │  │
  │  ├────────────┤  │      │  ├────────────┤  │     │  ├────────────┤  │
  │  │  Footer    │  │      │  │  Footer    │  │     │  │  Footer    │  │
  │  └────────────┘  │      │  └────────────┘  │     │  └────────────┘  │
  └──────────────────┘      └──────────────────┘     └──────────────────┘

  Same shell (navbar, footer). Only the middle content changes.
  The page never reloads. JavaScript swaps the content.
```

### SPA vs. MPA vs. SSR

| Approach | Rendering | Page load | Example |
|----------|-----------|-----------|---------|
| MPA (Multi-Page App) | Server renders full HTML | Full page reload on navigation | volta-auth-proxy admin pages (jte) |
| SPA (Single Page App) | Browser renders via JS | No reload, JS updates DOM | React/Vue dashboard apps |
| SSR (Server-Side Rendering) | Server renders, JS hydrates | First load is server-rendered, then SPA-like | Next.js, Nuxt.js |

---

## How does volta-auth-proxy use it?

### volta itself is NOT a SPA

volta-auth-proxy's own pages (login, tenant selector, invitation acceptance) are server-rendered using [jte](jte.md) [templates](template.md). This is intentional:

- Login pages must work without JavaScript (accessibility, reliability)
- Server-rendered pages have no [XSS](xss.md) risk from client-side rendering
- Auth flows involve [redirects](redirect-uri.md) that work better with traditional page loads

### SPAs are volta's primary consumers

The applications that sit behind volta (the [downstream apps](downstream-app.md)) are typically SPAs. A typical architecture:

```
  ┌──────────────────────────────────────────────────────────┐
  │                        Browser                            │
  │                                                           │
  │  ┌───────────────────────────────────────────────────┐   │
  │  │          Your SPA (React / Vue / etc.)             │   │
  │  │                                                    │   │
  │  │  import { VoltaClient } from 'volta-sdk-js';      │   │
  │  │  const volta = new VoltaClient();                  │   │
  │  │                                                    │   │
  │  │  // SDK handles JWT refresh automatically          │   │
  │  │  const user = await volta.getUser();               │   │
  │  │  const members = await volta.fetch('/api/members');│   │
  │  └───────────────────────────────────────────────────┘   │
  │         │                           │                     │
  │         │ /auth/refresh             │ /api/v1/users/me   │
  └─────────┼───────────────────────────┼─────────────────────┘
            │                           │
            ▼                           ▼
  ┌──────────────────────────────────────────────────────────┐
  │                    volta-auth-proxy                       │
  │  Session cookie validates user                           │
  │  Issues short-lived JWT (5 min)                          │
  │  Returns user info via API                               │
  └──────────────────────────────────────────────────────────┘
```

### The SPA authentication flow with volta

```
  1. User opens your-app.example.com
     ├── SPA loads (index.html + app.js)
     └── SPA calls volta /api/v1/users/me
              │
              ▼
  2. No session cookie → volta returns 401
              │
              ▼
  3. volta-sdk-js detects 401 → redirects to volta login
              │
              ▼
  4. User authenticates via Google OIDC
              │
              ▼
  5. volta creates session cookie → redirects back to SPA
              │
              ▼
  6. SPA loads again, calls /api/v1/users/me
     ├── Session cookie is valid → volta returns user data
     └── SPA renders the authenticated UI
              │
              ▼
  7. 5 minutes later, JWT expires
     ├── volta-sdk-js detects 401
     ├── Calls /auth/refresh (session cookie still valid)
     ├── Gets new JWT
     └── Retries original request transparently
```

### ForwardAuth pattern with SPAs

When a SPA is served through Traefik with [ForwardAuth](forwardauth.md), every request to the SPA's backend goes through volta:

```
  SPA → fetch("/api/data") → Traefik → ForwardAuth → volta checks session
                                                      │
                                              ┌───────┴───────┐
                                              │ Valid session  │
                                              │ Add X-Volta-* │
                                              │ headers        │
                                              └───────┬───────┘
                                                      │
                                              Your backend API
                                              reads X-Volta-* headers
```

---

## Common mistakes and attacks

### Mistake 1: Storing JWTs in localStorage

SPAs need to store the JWT somewhere. The tempting choice is `localStorage` because it persists across page reloads. But `localStorage` is accessible to any JavaScript on the page, including injected scripts from [XSS](xss.md) attacks. volta-sdk-js stores JWTs only in memory and refreshes them via the [session](session.md) [cookie](cookie.md) when needed.

### Mistake 2: Not handling token refresh

A SPA that gets a JWT and uses it forever will break when the JWT expires (5 minutes in volta). The SPA must either use volta-sdk-js (which handles refresh automatically) or implement its own refresh logic.

### Attack 1: XSS in SPAs

SPAs are particularly vulnerable to [XSS](xss.md) because the entire application runs as JavaScript. If an attacker can inject a `<script>` tag, they can:

- Read any data the SPA can access
- Make API calls as the authenticated user
- Steal in-memory JWTs

Defense: sanitize all user input, use Content Security Policy headers, and keep JWTs short-lived (volta's 5-minute expiry limits the damage window).

### Mistake 3: CORS misconfiguration

SPAs make cross-origin API calls (the SPA is served from `app.example.com`, the API is at `auth.example.com`). If [CORS](cors.md) is not configured correctly, the browser will block the requests. If CORS is configured too permissively (`Access-Control-Allow-Origin: *`), any website can make API calls on behalf of the user.

### Mistake 4: Deep link routing failures

When a user bookmarks `/dashboard/settings` and later visits it directly, the server receives a request for `/dashboard/settings`. If the server only serves `index.html` at `/`, the user gets a 404. The server must be configured to return `index.html` for all SPA routes, letting the client-side router handle the path.

---

## Further reading

- [javascript.md](javascript.md) -- The language that powers SPAs.
- [html.md](html.md) -- The single HTML page that SPAs load.
- [css.md](css.md) -- Styling in SPAs.
- [api.md](api.md) -- The API pattern that SPAs consume.
- [jwt.md](jwt.md) -- The tokens SPAs use for API authentication.
- [cors.md](cors.md) -- Cross-origin rules that affect SPAs.
- [xss.md](xss.md) -- The primary security threat to SPAs.
- [sdk.md](sdk.md) -- volta-sdk-js, the SDK for SPAs that use volta.
- [frontend-backend.md](frontend-backend.md) -- How SPAs split across browser and server.
