# App Integration

[日本語版はこちら](app-integration.ja.md)

---

How does an application connect to an authentication system? This question sounds simple, but the answer shapes your entire architecture. There are three fundamental models, and choosing the wrong one can cost months of rework.

---

## Three models of app integration

### Model 1: Embedded (auth code inside your app)

The auth logic lives inside your application. Your app directly handles login forms, password checking, session management, and token validation.

```
  ┌──────────────────────────────────────────────┐
  │ Your Application                              │
  │                                                │
  │  ┌──────────────┐  ┌──────────────────────┐  │
  │  │ Business logic│  │ Auth logic           │  │
  │  │ (your code)   │  │ (Spring Security,    │  │
  │  │               │  │  Passport.js, etc.)  │  │
  │  └──────────────┘  └──────────────────────┘  │
  │                                                │
  └────────────────────────────────────────────────┘
```

**Examples:** Spring Security in a Java app. Passport.js in a Node.js app. Django's auth module. Laravel's authentication.

**Pros:**
- Everything is in one place
- No external services to manage
- Full control over auth behavior

**Cons:**
- Auth code is duplicated across every app
- Every app team must understand auth security
- Changing auth logic means changing every app
- Different apps may implement auth differently (inconsistent security)

This model works for a single application. It fails for a multi-service architecture.

### Model 2: Redirect (external auth server)

The app redirects users to a separate auth server for login. After authentication, the server redirects back with a token.

```
  ┌──────────┐    redirect     ┌──────────────┐
  │ Your App  │ ──────────────► │ Keycloak /   │
  │           │                 │ Auth0 /      │
  │           │ ◄────────────── │ volta login  │
  │           │    token        └──────────────┘
  └──────────┘
```

**Examples:** OAuth2/OIDC with Keycloak, Auth0's Universal Login, any "Login with Google" flow.

**Pros:**
- Auth is centralized (one login server for all apps)
- Apps receive tokens, not raw credentials
- Standards-based (OAuth2/OIDC)

**Cons:**
- Every app must implement the OAuth2 client flow (redirect, token exchange, token validation)
- Every app needs an auth library/SDK
- Token refresh logic must be implemented per app
- If the auth server changes, every app's SDK may need updating

This model is better than embedded, but it still puts auth burden on each application.

### Model 3: Proxy (ForwardAuth)

A reverse proxy handles authentication before the request reaches your app. Your app receives identity information via HTTP headers, with zero auth code.

```
  ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
  │ Browser   │────►│ Traefik   │────►│ volta    │     │ Your App │
  │           │     │ (proxy)   │     │ (auth)   │     │ (NO auth │
  │           │     │           │◄────│          │     │  code!)  │
  │           │◄────│           │─────┤          ├────►│          │
  └──────────┘     └──────────┘     └──────────┘     └──────────┘
                   Adds X-Volta-*                     Reads headers
                   headers
```

**Examples:** volta-auth-proxy with Traefik ForwardAuth, Ory Oathkeeper, oauth2-proxy.

**Pros:**
- Apps have ZERO auth code
- Adding auth to a new app = one line of Traefik config
- Auth changes happen in one place (volta), not in every app
- Any language/framework works (just read headers)

**Cons:**
- Requires a reverse proxy (Traefik, Nginx, etc.)
- Network hop for auth check on every request
- Apps must trust the proxy (network isolation required)

---

## Why the proxy model is best for "mass-produce services quickly"

If you are building a SaaS platform with multiple services (wiki, admin panel, billing dashboard, API gateway), the proxy model lets you add auth to each service in minutes instead of days.

### Adding auth to a new service: three models compared

**Embedded (Spring Security):**
```
1. Add spring-boot-starter-security dependency
2. Configure OAuth2 client properties
3. Implement SecurityFilterChain
4. Handle token refresh in a filter
5. Extract user/tenant from token claims
6. Implement role-based access in each endpoint
7. Test the auth integration
8. Deploy
Time: 1-3 days per service
```

**Redirect (Auth0 SDK):**
```
1. Install @auth0/auth0-spa-js (frontend)
2. Configure auth provider wrapper
3. Add Auth0Provider to app root
4. Implement useAuth0 hook in components
5. Handle token refresh
6. Call Auth0 Management API for user data
7. Configure Auth0 dashboard for the new app
8. Test the integration
Time: 1-2 days per service
```

**Proxy (volta ForwardAuth):**
```
1. Add Traefik middleware to route:
   middlewares: [volta-auth]
2. Read headers in your app:
   ctx.header("X-Volta-User-Id")
   ctx.header("X-Volta-Tenant-Id")
3. Done.
Time: 30 minutes per service
```

The difference is dramatic. With the proxy model, auth is infrastructure. Apps do not know or care how auth works. They just read headers.

---

## volta's 2-step integration

volta provides two complementary integration points:

### Step 1: Headers (passive -- every request)

Every request that passes through Traefik's ForwardAuth arrives at your app with identity headers:

```
X-Volta-User-Id: user-uuid
X-Volta-Tenant-Id: tenant-uuid
X-Volta-Roles: ADMIN
X-Volta-Email: taro@acme.com
X-Volta-Display-Name: Taro Yamada
X-Volta-Tenant-Slug: acme
X-Volta-JWT: eyJhbGci...
X-Volta-App-Id: app-wiki
```

Your app reads these headers. That is Step 1. Most apps only need this.

```java
// A complete authenticated API endpoint in Javalin:
app.get("/api/items", ctx -> {
    String tenantId = ctx.header("X-Volta-Tenant-Id");
    var items = db.query("SELECT * FROM items WHERE tenant_id = ?", tenantId);
    ctx.json(items);
});
// 3 lines. No auth library. No SDK. No token parsing.
```

### Step 2: API (active -- when needed)

When your app needs to perform auth operations (list members, change roles, create invitations), it calls volta's [internal API](internal-api.md):

```java
// List all members of the current tenant:
var response = httpClient.send(
    HttpRequest.newBuilder()
        .uri(URI.create("http://volta:7070/api/v1/tenants/" + tenantId + "/members"))
        .header("Authorization", "Bearer " + serviceToken)
        .build(),
    HttpResponse.BodyHandlers.ofString()
);
```

Step 2 is for admin panels, team settings pages, and any UI that manages users or tenants.

---

## The language-agnostic advantage

Because the proxy model communicates via HTTP headers, your apps can be written in any language:

```python
# Python (Flask)
@app.route('/api/data')
def get_data():
    tenant_id = request.headers.get('X-Volta-Tenant-Id')
    # ...
```

```go
// Go (net/http)
func handler(w http.ResponseWriter, r *http.Request) {
    tenantId := r.Header.Get("X-Volta-Tenant-Id")
    // ...
}
```

```ruby
# Ruby (Sinatra)
get '/api/data' do
    tenant_id = request.env['HTTP_X_VOLTA_TENANT_ID']
    # ...
end
```

```rust
// Rust (Actix-web)
async fn handler(req: HttpRequest) -> impl Responder {
    let tenant_id = req.headers().get("X-Volta-Tenant-Id");
    // ...
}
```

No SDK. No vendor library. Just HTTP headers, which every language and framework already understands.

---

## Further reading

- [forwardauth.md](forwardauth.md) -- Technical details of the ForwardAuth pattern.
- [header.md](header.md) -- The X-Volta-* headers in detail.
- [internal-api.md](internal-api.md) -- volta's REST API for app delegation.
- [downstream-app.md](downstream-app.md) -- What downstream apps are and how they relate to volta.
- [jwt.md](jwt.md) -- The X-Volta-JWT header for higher security.
