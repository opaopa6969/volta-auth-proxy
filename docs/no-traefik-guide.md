# Running Without Traefik

[English](no-traefik-guide.md) | [日本語](no-traefik-guide.ja.md)

> Traefik is recommended but NOT required. volta works without a reverse proxy.

---

## 3 Deployment Patterns

### Pattern A: volta Only (Simplest)

```
Browser
  ↓
volta-auth-proxy (port 7070)
  ├── /login, /invite, /admin    → volta serves HTML
  ├── /api/v1/*                  → Internal API
  └── /.well-known/jwks.json     → JWKS

Your App (port 8080)
  ├── /api/*                     → your business API
  └── /                          → your frontend
```

**How it works:**
- volta handles all auth pages directly
- Your app runs on a separate port
- Your frontend calls BOTH volta (auth) and your app (data)
- No reverse proxy needed

**Frontend code:**
```javascript
// volta-sdk-js handles auth
Volta.init({ gatewayUrl: "http://localhost:7070" });

// Call your app directly for data
const tasks = await Volta.fetch("http://localhost:8080/api/tasks");

// volta handles login/logout
await Volta.logout();
```

**Your app verifies JWT directly** (no ForwardAuth headers):
```java
VoltaAuth volta = VoltaAuth.builder()
    .jwksUrl("http://volta-auth-proxy:7070/.well-known/jwks.json")
    .expectedIssuer("volta-auth")
    .expectedAudience("volta-apps")
    .build();

app.before("/api/*", volta.middleware());

app.get("/api/tasks", ctx -> {
    VoltaUser user = VoltaAuth.getUser(ctx);
    // user comes from JWT verification, not headers
});
```

**Pros:**
- Zero extra infrastructure
- docker-compose: just volta + Postgres + your app
- Simplest possible setup

**Cons:**
- Two ports exposed (7070 + 8080) — need CORS
- No automatic header injection (your app must verify JWT)
- No subdomain routing

**Best for:** development, prototyping, single-app deployments

---

### Pattern B: Traefik (Recommended for production)

```
Browser
  ↓
Traefik (port 80/443)
  ↓ ForwardAuth ↓
volta-auth-proxy    Your App
(auth check only)   (gets X-Volta-* headers)
```

This is the standard pattern described in the main [README](../README.md).

**Best for:** production, multiple apps, subdomain routing

---

### Pattern C: nginx / Caddy / Any Reverse Proxy

ForwardAuth is not Traefik-specific. Any reverse proxy with auth delegation works.

#### nginx (auth_request)

```nginx
server {
    listen 80;
    server_name app.example.com;

    # Auth check — nginx asks volta
    location = /volta-auth {
        internal;
        proxy_pass http://volta-auth-proxy:7070/auth/verify;
        proxy_pass_request_body off;
        proxy_set_header Content-Length "";
        proxy_set_header X-Original-URI $request_uri;
        proxy_set_header X-Forwarded-Host $host;
    }

    location / {
        auth_request /volta-auth;

        # Pass volta headers to your app
        auth_request_set $volta_user_id $upstream_http_x_volta_user_id;
        auth_request_set $volta_tenant_id $upstream_http_x_volta_tenant_id;
        auth_request_set $volta_roles $upstream_http_x_volta_roles;
        auth_request_set $volta_jwt $upstream_http_x_volta_jwt;

        proxy_set_header X-Volta-User-Id $volta_user_id;
        proxy_set_header X-Volta-Tenant-Id $volta_tenant_id;
        proxy_set_header X-Volta-Roles $volta_roles;
        proxy_set_header X-Volta-JWT $volta_jwt;

        proxy_pass http://your-app:8080;
    }
}
```

#### Caddy (forward_auth)

```
app.example.com {
    forward_auth volta-auth-proxy:7070 {
        uri /auth/verify
        copy_headers {
            X-Volta-User-Id
            X-Volta-Tenant-Id
            X-Volta-Tenant-Slug
            X-Volta-Roles
            X-Volta-Display-Name
            X-Volta-JWT
        }
    }
    reverse_proxy your-app:8080
}
```

**Best for:** teams already using nginx or Caddy

---

## Pattern A: Detailed Setup (No Reverse Proxy)

### docker-compose.yml

```yaml
services:
  postgres:
    image: postgres:16-alpine
    ports: ["54329:5432"]
    environment:
      POSTGRES_DB: volta_auth
      POSTGRES_USER: volta
      POSTGRES_PASSWORD: volta

  volta-auth-proxy:
    build: ../volta-auth-proxy
    ports: ["7070:7070"]
    depends_on: [postgres]
    env_file: .env
    environment:
      CORS_ALLOWED_ORIGINS: "http://localhost:3000,http://localhost:8080"

  my-app:
    build: .
    ports: ["8080:8080"]
    environment:
      VOLTA_JWKS_URL: http://volta-auth-proxy:7070/.well-known/jwks.json
```

### CORS Configuration

Without a reverse proxy, your frontend makes cross-origin requests to volta. volta needs CORS headers:

```env
# .env
CORS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost:8080
```

### Frontend (SPA) Setup

```html
<script src="http://localhost:7070/js/volta.js"></script>
<script>
  Volta.init({ gatewayUrl: "http://localhost:7070" });

  // Auth-aware fetch to YOUR app (not volta)
  async function loadTasks() {
    // Get JWT from volta session
    const session = await Volta.getSession();
    const jwt = session.token;

    // Call your app with JWT
    const res = await fetch("http://localhost:8080/api/tasks", {
      headers: { "Authorization": "Bearer " + jwt }
    });
    return res.json();
  }
</script>
```

### Your App: JWT Verification

Without ForwardAuth, your app must verify JWT itself:

```java
// Javalin
VoltaAuth volta = VoltaAuth.builder()
    .jwksUrl("http://volta-auth-proxy:7070/.well-known/jwks.json")
    .expectedIssuer("volta-auth")
    .expectedAudience("volta-apps")
    .build();

app.before("/api/*", volta.middleware());

app.get("/api/tasks", ctx -> {
    VoltaUser user = VoltaAuth.getUser(ctx);
    String tenantId = user.getTenantId();
    var tasks = db.query("SELECT * FROM tasks WHERE tenant_id = ?", tenantId);
    ctx.json(tasks);
});
```

```javascript
// Express (Node.js) — verify JWT manually
const jose = require("jose");
const JWKS = jose.createRemoteJWKSet(
  new URL("http://volta-auth-proxy:7070/.well-known/jwks.json")
);

app.use("/api", async (req, res, next) => {
  const token = req.headers.authorization?.replace("Bearer ", "");
  if (!token) return res.status(401).json({ error: "No token" });

  try {
    const { payload } = await jose.jwtVerify(token, JWKS, {
      issuer: "volta-auth",
      audience: "volta-apps",
    });
    req.user = {
      userId: payload.sub,
      tenantId: payload.volta_tid,
      roles: payload.volta_roles,
    };
    next();
  } catch (e) {
    return res.status(401).json({ error: "Invalid token" });
  }
});
```

---

## Comparison

| | Pattern A (no proxy) | Pattern B (Traefik) | Pattern C (nginx/Caddy) |
|---|---|---|---|
| **Infrastructure** | None | Traefik | nginx or Caddy |
| **Setup complexity** | Lowest | Medium | Medium |
| **Auth method** | JWT verification | ForwardAuth headers | ForwardAuth headers |
| **CORS needed** | Yes | No | No |
| **Multiple apps** | Manual JWT | Automatic headers | Automatic headers |
| **Subdomain routing** | No | Yes | Yes |
| **Production ready** | Dev/small | Yes | Yes |
| **volta philosophy** | "Just works" | "Config aggregation" | "Your choice" |
