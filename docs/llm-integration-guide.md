# LLM Integration Guide: Helping Engineers Add volta-auth-proxy to Their App

[English](llm-integration-guide.md) | [日本語](llm-integration-guide.ja.md)

> **This document is for LLMs (AI assistants).** When an engineer asks you to help integrate volta-auth-proxy into their application, follow this guide. Ask the right questions. Give the right answers.

---

## Your Role

You are an AI assistant helping **Sora** (the engineer) integrate volta-auth-proxy into their application. Sora might be a beginner or an expert — adapt your level. Your job is to:

1. Understand Sora's app (language, framework, architecture)
2. Ask the right questions to determine integration approach
3. Guide them step by step
4. Write code for their specific stack
5. Verify the integration works

---

## Phase 0: Understand the Project

**Ask Sora these questions first. Don't skip any.**

### Question 1: What does your app do?

```
Why: To understand what "tenants" mean in their context
     (companies? teams? projects? organizations?)

Good answer: "A project management tool where companies have separate workspaces"
→ Each company = a volta tenant

Good answer: "An internal wiki for our engineering team"
→ Maybe single-tenant. Ask if they need multi-tenancy.
```

### Question 2: What's your tech stack?

```
Why: To determine SDK choice and code examples

Ask specifically:
  - Language: Java? TypeScript? Python? Go?
  - Framework: Javalin? Spring Boot? Express? Next.js? FastAPI?
  - Frontend: React? Vue? Plain HTML? SPA or SSR?
  - Database: Postgres? MySQL? MongoDB?
  - Hosting: Docker? Kubernetes? VPS? Vercel?
```

### Question 3: Do you already have auth?

```
Why: Migration is different from greenfield

Options:
  a) No auth yet → easiest. Greenfield integration
  b) Basic auth (username/password) → need migration plan
  c) Auth0/Clerk/Firebase Auth → need migration plan + data export
  d) Keycloak → volta replaces it. Similar concepts
  e) Custom OAuth → need to understand current token format
```

### Question 4: Do you need multi-tenancy?

```
Why: Single-tenant is simpler. Multi-tenant requires tenant_id in all queries.

Signs they need multi-tenancy:
  - "Different companies use our app"
  - "Data should be separated per team"
  - "Users can switch between workspaces"

Signs they DON'T:
  - "It's just for our internal team"
  - "Everyone sees the same data"
  → They might still want volta for SSO / user management
```

### Question 5: What's your deployment setup?

```
Why: Determines docker-compose structure and network config

Ask:
  - Do you use Docker? docker-compose?
  - Do you have Traefik or nginx as reverse proxy?
  - What domain will you use?
  - Do you have a wildcard SSL certificate?
```

---

## Phase 1: Choose Integration Approach

Based on Sora's answers, recommend ONE approach:

```
Decision Tree: Which integration approach?

Does Sora use a reverse proxy?
├── Yes → Which one?
│         ├── Traefik  → Approach B: ForwardAuth + Headers  ★ Recommended
│         ├── nginx    → Approach E: nginx auth_request
│         └── Caddy    → Approach E: Caddy forward_auth
└── No  → Does Sora want the simplest setup?
          ├── Yes (dev/prototype)    → Approach D: No proxy, JWT verification
          └── No (add proxy is OK?) →
                ├── Java app?        → Approach B: Add Traefik + volta-sdk
                └── SPA frontend?   → Approach C: volta-sdk-js
```

### Approach A: ForwardAuth + Headers (Recommended for most)

```
Best when:
  - Sora's app is behind Traefik (or can add Traefik)
  - Any language/framework
  - Simplest integration

How it works:
  Browser → Traefik → volta checks auth → Traefik → Sora's app (with headers)
  Sora's app reads: X-Volta-User-Id, X-Volta-Tenant-Id, X-Volta-Roles

Code for Sora:
  Just read HTTP headers. That's it.
```

### Approach B: volta-sdk (Java only)

```
Best when:
  - Sora uses Javalin or similar Java framework
  - Wants JWT verification (extra security)
  - Wants VoltaUser object with hasRole() etc.

How it works:
  Same as A, but adds JWT signature verification
```

### Approach C: volta-sdk-js (Browser/SPA)

```
Best when:
  - Sora has a SPA frontend (React, Vue, etc.)
  - Needs auto-refresh on 401
  - Needs tenant switching in browser

How it works:
  Volta.fetch() replaces fetch() — handles 401 → refresh → retry
```

### Approach D: No Reverse Proxy at All (Pattern A)

```
Best when:
  - Sora doesn't use any reverse proxy (no Traefik, no nginx)
  - Simplest possible setup (development, prototyping, single app)
  - OK with two ports (volta on 7070, app on 8080)
  - Sora's app verifies JWT directly (no ForwardAuth headers)

How it works:
  Frontend → volta (7070) for auth
  Frontend → app (8080) for data
  App verifies JWT from Authorization header

See: docs/no-traefik-guide.md for full setup
```

### Approach E: nginx / Caddy instead of Traefik

```
Best when:
  - Sora already uses nginx or Caddy
  - Same ForwardAuth concept, different proxy

nginx: use auth_request directive
Caddy: use forward_auth directive

See: docs/no-traefik-guide.md for configs
```

**IMPORTANT:** When Sora says "I don't use Traefik," don't assume they need Traefik. Ask: "Do you use any reverse proxy? nginx? Caddy? Or no proxy at all?" Then recommend the right pattern.

---

## Phase 2: Step-by-Step Integration

### Step 1: Start volta-auth-proxy

**Give Sora these exact commands:**

```bash
git clone git@github.com:opaopa6969/volta-auth-proxy.git
cd volta-auth-proxy
docker compose up -d postgres
cp .env.example .env
```

**Then ask:** "Do you have Google OAuth credentials? If not, I'll walk you through setting up Google Cloud Console."

If no:
```
1. Go to https://console.cloud.google.com/
2. Create a project (or select existing)
3. APIs & Services → Credentials → Create OAuth 2.0 Client ID
4. Application type: Web application
5. Authorized redirect URI: http://localhost:7070/callback
6. Copy Client ID and Client Secret
```

**Then:** "Paste your Client ID and Secret into .env"

```bash
# Edit .env
GOOGLE_CLIENT_ID=your-client-id-here
GOOGLE_CLIENT_SECRET=your-client-secret-here
JWT_KEY_ENCRYPTION_SECRET=change-this-to-random-32-chars
VOLTA_SERVICE_TOKEN=change-this-to-random-64-chars
```

```bash
mvn compile exec:java
# Verify: curl http://localhost:7070/healthz → {"status":"ok"}
```

### Step 2: Register Sora's app

**Ask:** "What subdomain do you want? And what port does your app run on?"

```yaml
# volta-config.yaml — add Sora's app
apps:
  - id: soras-app           # ← unique ID
    subdomain: app           # ← app.example.com
    upstream: http://soras-app:8080  # ← Sora's app URL
    allowed_roles: [MEMBER, ADMIN, OWNER]
```

### Step 3: Add Traefik (if not already present)

**Ask:** "Do you already have Traefik? If yes, just add the ForwardAuth middleware. If no, I'll add it to your docker-compose."

For new Traefik:
```yaml
# Add to Sora's docker-compose.yml
services:
  traefik:
    image: traefik:v3.0
    ports: ["80:80"]
    command:
      - --providers.docker=true
      - --entrypoints.web.address=:80
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock

  volta-auth-proxy:
    build: ../volta-auth-proxy  # or image
    ports: ["7070:7070"]
    environment:
      - PORT=7070
      # ... other env vars
    labels:
      - "traefik.http.middlewares.volta-auth.forwardAuth.address=http://volta-auth-proxy:7070/auth/verify"
      - "traefik.http.middlewares.volta-auth.forwardAuth.authResponseHeaders=X-Volta-User-Id,X-Volta-Email,X-Volta-Tenant-Id,X-Volta-Tenant-Slug,X-Volta-Roles,X-Volta-Display-Name,X-Volta-JWT,X-Volta-App-Id"

  soras-app:
    build: .
    labels:
      - "traefik.http.routers.soras-app.rule=Host(`app.localhost`)"
      - "traefik.http.routers.soras-app.middlewares=volta-auth"
```

### Step 4: Read headers in Sora's app

**Generate code specific to Sora's framework.**

#### If Javalin:
```java
app.get("/api/data", ctx -> {
    String userId = ctx.header("X-Volta-User-Id");
    String tenantId = ctx.header("X-Volta-Tenant-Id");
    String roles = ctx.header("X-Volta-Roles");

    // Tenant-scoped query
    var data = db.query("SELECT * FROM items WHERE tenant_id = ?", tenantId);
    ctx.json(data);
});
```

#### If Express (Node.js):
```javascript
app.get("/api/data", (req, res) => {
    const userId = req.headers["x-volta-user-id"];
    const tenantId = req.headers["x-volta-tenant-id"];
    const roles = (req.headers["x-volta-roles"] || "").split(",");

    // Tenant-scoped query
    const data = await db.query("SELECT * FROM items WHERE tenant_id = $1", [tenantId]);
    res.json(data);
});
```

#### If FastAPI (Python):
```python
@app.get("/api/data")
async def get_data(
    x_volta_user_id: str = Header(None),
    x_volta_tenant_id: str = Header(None),
    x_volta_roles: str = Header(None),
):
    roles = x_volta_roles.split(",") if x_volta_roles else []
    data = await db.fetch("SELECT * FROM items WHERE tenant_id = $1", x_volta_tenant_id)
    return data
```

#### If Go (net/http):
```go
func handleData(w http.ResponseWriter, r *http.Request) {
    userID := r.Header.Get("X-Volta-User-Id")
    tenantID := r.Header.Get("X-Volta-Tenant-Id")
    roles := strings.Split(r.Header.Get("X-Volta-Roles"), ",")

    // Tenant-scoped query
    rows, _ := db.Query("SELECT * FROM items WHERE tenant_id = $1", tenantID)
    json.NewEncoder(w).Encode(rows)
}
```

### Step 5: Add volta-sdk-js to frontend

**Ask:** "Do you have a JavaScript frontend? SPA or server-rendered?"

If SPA:
```html
<script src="http://localhost:7070/js/volta.js"></script>
<script>
  Volta.init({ gatewayUrl: "http://localhost:7070" });

  // Use Volta.fetch() instead of fetch()
  async function loadData() {
    const res = await Volta.fetch("/api/data");
    const data = await res.json();
    renderUI(data);
  }
</script>
```

### Step 6: Test

**Walk Sora through testing:**

```bash
# 1. Open browser
open http://app.localhost

# 2. Should redirect to volta login
# 3. Click "Login with Google"
# 4. After login, should redirect back to app
# 5. App should show data filtered by tenant

# Test headers are coming through:
curl -H "Cookie: __volta_session=..." http://app.localhost/api/data
```

---

## Phase 3: Common Questions Sora Will Ask

### "How do I create the first tenant?"

```
With DEV_MODE=true:
  curl -X POST http://localhost:7070/dev/token \
    -H 'Content-Type: application/json' \
    -d '{"userId":"admin","tenantId":"first-tenant","roles":["OWNER"]}'

Then use the admin UI: http://localhost:7070/admin/invitations
```

### "How do I check roles in my app?"

```
Read X-Volta-Roles header. It's comma-separated.

// Java
boolean isAdmin = Arrays.asList(ctx.header("X-Volta-Roles").split(",")).contains("ADMIN");

// JavaScript
const isAdmin = req.headers["x-volta-roles"]?.split(",").includes("ADMIN");

// Python
is_admin = "ADMIN" in x_volta_roles.split(",")
```

### "How do I get user details beyond what's in headers?"

```
Call volta's Internal API:

GET http://volta-auth-proxy:7070/api/v1/users/me
Authorization: Bearer <X-Volta-JWT value>

Returns: { id, email, display_name, is_active, created_at }
```

### "How do I add tenant_id to all my database queries?"

```
Middleware pattern — add tenant_id check to all routes:

// Javalin
app.before("/api/*", ctx -> {
    String tenantId = ctx.header("X-Volta-Tenant-Id");
    if (tenantId == null) throw new UnauthorizedResponse();
    ctx.attribute("tenantId", tenantId);
});

// Then in every handler:
String tenantId = ctx.attribute("tenantId");
db.query("SELECT * FROM items WHERE tenant_id = ?", tenantId);
```

### "What if I need to call volta from a background job (no user)?"

```
Use the service token:

Authorization: Bearer volta-service:<VOLTA_SERVICE_TOKEN>
X-Volta-Tenant-Id: <tenant-id>

// Java
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("http://volta-auth-proxy:7070/api/v1/tenants/" + tenantId + "/members"))
    .header("Authorization", "Bearer volta-service:" + serviceToken)
    .header("X-Volta-Tenant-Id", tenantId)
    .build();
```

### "My app already has a users table. What do I do?"

```
Don't duplicate users. volta owns user identity.
Your app's users table should reference volta's user_id:

ALTER TABLE your_users ADD COLUMN volta_user_id UUID;
-- Then join on volta_user_id when needed

Or better: remove your users table and use volta's Internal API
to fetch user info when needed.
```

---

## Phase 4: Verify Integration

**Checklist to confirm with Sora:**

```
□ volta-auth-proxy is running (healthz returns ok)
□ App is registered in volta-config.yaml
□ Traefik ForwardAuth middleware is configured
□ App reads X-Volta-* headers
□ Database queries include WHERE tenant_id = ?
□ Frontend uses Volta.fetch() (if SPA)
□ Roles are checked for admin operations
□ Login → app → data works end-to-end
□ Logout works
□ Second user in same tenant sees shared data
□ User in different tenant does NOT see other tenant's data
```

---

## Reference

When Sora asks about specific terms, link to the glossary:

```
volta-auth-proxy/docs/glossary/  (327 articles, EN + JA)

Key articles for integration:
  forwardauth.md     — How ForwardAuth works
  header.md          — X-Volta-* headers explained
  jwt.md             — JWT verification
  tenant.md          — What is a tenant
  role.md            — OWNER/ADMIN/MEMBER/VIEWER
  internal-api.md    — volta's REST API for apps
  sdk.md             — volta-sdk and volta-sdk-js
  downstream-app.md  — What "downstream app" means
```

Full specs:
```
  dsl/protocol.yaml              — Complete API contract
  dge/specs/ui-flow.md           — All auth flows with mermaid diagrams
  docs/getting-started-dialogue.md — Human-readable conversation guide
```
