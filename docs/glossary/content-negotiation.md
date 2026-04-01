# Content Negotiation

[日本語版はこちら](content-negotiation.ja.md)

---

## What is it?

Content negotiation is the process by which a server chooses the right response format based on what the client says it can accept. A browser asks for HTML. An API client asks for JSON. The same endpoint can serve both, choosing the format based on the `Accept` [header](header.md) in the request.

Think of it like ordering at a restaurant that serves both dine-in and takeaway. You place the same order ("chicken curry"), but the packaging is different: a plate with garnish for dine-in, a sealed container for takeaway. The food is the same; the presentation adapts to the customer's needs.

In volta-auth-proxy, content negotiation is CRITICAL for security. When an unauthenticated browser hits a protected page, volta redirects to `/login` (302). But when an unauthenticated SPA fetch() call hits the same page, volta MUST return a JSON error (401) instead. Returning a 302 to a fetch() call causes the browser to silently follow the redirect to Google's login page, and the SPA receives Google's HTML instead of a JSON error.

---

## Why does it matter?

Without content negotiation:

- **Broken SPAs**: fetch() follows 302 redirects automatically. The SPA gets Google's login HTML instead of a JSON error it can handle
- **Broken API clients**: A CLI tool or mobile app receives an HTML page when it expects JSON
- **Confusing UX**: The user sees no error message because the SPA cannot parse the redirect response
- **Security confusion**: The SPA might interpret an HTML response as a successful response and render it unsafely

---

## How does it work?

### The Accept header

Clients indicate their preferred format via the `Accept` header:

```
  Browser request:
  GET /dashboard
  Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8

  API/SPA request:
  GET /api/v1/users/me
  Accept: application/json

  AJAX request:
  GET /some-page
  X-Requested-With: XMLHttpRequest
```

### Decision tree

```
  Incoming request
  │
  ├── Accept contains "application/json"?
  │   └── Yes → Respond with JSON
  │
  ├── X-Requested-With: XMLHttpRequest?
  │   └── Yes → Respond with JSON
  │
  ├── Authorization: Bearer ...?
  │   └── Yes → Respond with JSON
  │
  └── Otherwise (text/html or no Accept header)
      └── Respond with HTML or redirect
```

### Why 302 breaks SPA fetch()

```
  ❌ WITHOUT content negotiation:

  SPA fetch("/dashboard")
  │
  ├── volta: "Not authenticated! 302 → /login"
  ├── Browser (behind the scenes): "302? I'll follow that."
  ├── Browser: "GET /login → 302 → Google OAuth"
  ├── Browser: "GET accounts.google.com/..."
  └── fetch() receives: Google's HTML login page
      SPA: "What is this HTML? I expected JSON!"
      Result: White screen, broken app

  ✅ WITH content negotiation:

  SPA fetch("/dashboard", { headers: { "Accept": "application/json" }})
  │
  ├── volta: "Not authenticated + wants JSON → 401 JSON"
  └── fetch() receives: { "error": { "code": "AUTHENTICATION_REQUIRED" } }
      SPA: "401? I'll show the login modal."
      Result: Clean user experience
```

---

## How does volta-auth-proxy use it?

### Defined in `dsl/protocol.yaml`

```yaml
content_negotiation:
  rules:
    - condition: "Accept header contains 'application/json'"
      response: json

    - condition: "X-Requested-With: XMLHttpRequest"
      response: json

    - condition: "Authorization: Bearer ..."
      response: json

    - condition: "Accept: text/html OR no Accept header"
      response: html_or_redirect

  reason: >
    SPA fetch() follows 302 automatically. If Gateway returns 302
    to Google login, fetch receives Google's HTML instead of a JSON error.
```

### State machine guards for content negotiation

`auth-machine.yaml` uses the `request.accept_json` context variable to branch:

```yaml
# UNAUTHENTICATED state
login_browser:
  trigger: "GET /login"
  guard: "!request.accept_json"    # Browser → redirect to Google
  actions:
    - { type: side_effect, action: create_oidc_flow }
    - { type: http, action: redirect, target: google_authorize_url }
  next: AUTH_PENDING

login_api:
  trigger: "GET /login"
  guard: "request.accept_json"      # SPA/API → JSON error
  actions:
    - { type: http, action: json_error, error_ref: AUTHENTICATION_REQUIRED }
  next: UNAUTHENTICATED
```

### Global transitions: logout

```yaml
logout_browser:
  trigger: "POST /auth/logout"
  guard: "!request.accept_json"
  actions:
    - { type: http, action: redirect, target: "/login" }
  next: UNAUTHENTICATED

logout_api:
  trigger: "POST /auth/logout"
  guard: "request.accept_json"
  actions:
    - { type: http, action: json_ok }
  next: UNAUTHENTICATED
```

### Implementation in `HttpSupport.java`

```java
// HttpSupport.java
public static boolean acceptsJson(Context ctx) {
    String accept = ctx.header("Accept");
    if (accept != null && accept.contains("application/json")) return true;
    if (ctx.header("X-Requested-With") != null) return true;
    String auth = ctx.header("Authorization");
    if (auth != null && auth.startsWith("Bearer ")) return true;
    return false;
}
```

---

## Common mistakes and attacks

### Mistake 1: Returning 302 to JSON clients

This is the most common mistake. Always check the Accept header before returning a redirect. volta makes this impossible to forget by encoding it in the [DSL](dsl.md) as separate transitions.

### Mistake 2: Assuming all browsers send Accept: text/html

Some browsers, older HTTP libraries, and proxies may not send an Accept header at all. volta treats "no Accept header" the same as "text/html" -- a safe default for browser-like clients.

### Mistake 3: Content-Type confusion

`Accept` is what the client WANTS. `Content-Type` is what the server SENDS. Do not confuse them. volta checks `Accept` (request) and sets `Content-Type` (response) accordingly.

### Attack: Accept header manipulation

An attacker might send `Accept: application/json` to an endpoint that normally returns HTML, hoping the JSON response leaks more information. volta returns the same information regardless of format -- just packaged differently.

---

## Further reading

- [header.md](header.md) -- HTTP headers including Accept and Content-Type.
- [dsl.md](dsl.md) -- How content negotiation is encoded as guard expressions.
- [guard.md](guard.md) -- The `request.accept_json` guard variable.
- [state-machine.md](state-machine.md) -- Separate transitions for browser vs API.
- [downstream-app.md](downstream-app.md) -- How downstream apps handle response formats.
