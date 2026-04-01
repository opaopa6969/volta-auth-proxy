# Getting Started: A Conversation

[English](getting-started-dialogue.md) | [日本語](getting-started-dialogue.ja.md)

> Two engineers walk into a conference room. One works at volta. The other just wants to add auth to their app.

---

## Cast

**Rin (volta engineer):** Knows volta-auth-proxy inside out. Patient. Likes diagrams.

**Kai (app developer):** Building a project management SaaS. Has a Javalin app. Needs multi-tenant auth. Has heard of OAuth but never implemented it.

---

## Scene 1: "I just want login to work"

**Kai:** OK so I have this Javalin app. Users need to log in. I've been putting it off because auth is scary. I looked at Auth0 but it's expensive. Keycloak looks like a nightmare. Can volta help?

**Rin:** What does your app do?

**Kai:** Project management. Teams create workspaces, invite members, manage tasks. Each workspace is separate — you shouldn't see another team's data.

**Rin:** That's [multi-tenant](glossary/multi-tenant.md). Each workspace is a [tenant](glossary/tenant.md). volta was literally built for this. Here's what your architecture will look like:

```
Browser
  ↓
Traefik (reverse proxy)
  ↓                    ↓
volta-auth-proxy       Your app
(handles all auth)     (handles tasks/projects)
```

**Kai:** Wait, two services? I thought volta was "tight coupling, no apologies."

**Rin:** "Tight coupling" means volta's *config* is in one place. Your app and volta are separate services, but Traefik connects them. Your app never handles login, never verifies passwords, never manages sessions. volta does all of that.

**Kai:** So what does my app actually do?

**Rin:** Your app reads [HTTP headers](glossary/header.md). That's it.

**Kai:** ...that's it?

**Rin:** That's it.

---

## Scene 2: "Show me the headers"

**Rin:** When a user accesses your app, here's what happens:

```
1. Browser → Traefik → "Is this user logged in?"
2. Traefik → volta-auth-proxy → checks session → "Yes, here's who they are"
3. Traefik → Your app (with identity headers attached)
```

Your app receives these [headers](glossary/header.md) on every request:

```
X-Volta-User-Id:      550e8400-e29b-41d4-a716-446655440000
X-Volta-Email:        kai@example.com
X-Volta-Tenant-Id:    7c9e6679-7425-40de-944b-e07fc1f90ae7
X-Volta-Tenant-Slug:  acme-corp
X-Volta-Roles:        ADMIN,MEMBER
X-Volta-Display-Name: Kai Tanaka
X-Volta-JWT:          eyJhbGciOiJSUzI1NiIs...
```

**Kai:** So I just read `X-Volta-Tenant-Id` and filter my database queries by that?

**Rin:** Exactly.

```java
app.get("/api/tasks", ctx -> {
    String tenantId = ctx.header("X-Volta-Tenant-Id");
    String userId = ctx.header("X-Volta-User-Id");

    var tasks = db.query(
        "SELECT * FROM tasks WHERE tenant_id = ?",
        tenantId
    );
    ctx.json(tasks);
});
```

**Kai:** That's... really simple. But how do I know the headers aren't fake? Someone could just send `X-Volta-User-Id: admin` manually.

**Rin:** Two layers of protection:

1. **Network isolation:** Your app only accepts requests from Traefik's internal network. No one can reach your app directly.
2. **[JWT](glossary/jwt.md) verification:** If you want extra security, verify the `X-Volta-JWT` header. It's cryptographically signed.

```java
// Optional but recommended: verify the JWT
VoltaAuth volta = VoltaAuth.builder()
    .jwksUrl("http://volta-auth-proxy:7070/.well-known/jwks.json")
    .expectedIssuer("volta-auth")
    .expectedAudience("volta-apps")
    .build();

app.before("/api/*", volta.middleware());

// Now you can get a verified user object
app.get("/api/tasks", ctx -> {
    VoltaUser user = VoltaAuth.getUser(ctx);
    // user.getTenantId(), user.hasRole("ADMIN"), etc.
});
```

---

## Scene 3: "How do I set this up?"

**Kai:** OK I'm sold. How do I actually set this up?

**Rin:** Four steps.

### Step 1: Clone volta and start it

```bash
git clone git@github.com:opaopa6969/volta-auth-proxy.git
cd volta-auth-proxy
docker compose up -d postgres
cp .env.example .env
# Edit .env: add your Google OAuth credentials
mvn compile exec:java
```

### Step 2: Register your app in volta-config.yaml

```yaml
domain:
  base: example.com

apps:
  - id: project-manager
    subdomain: pm
    upstream: http://my-app:8080
    allowed_roles: [MEMBER, ADMIN, OWNER]
```

**Kai:** That's it? Four lines?

**Rin:** Four lines. volta generates the Traefik config automatically.

### Step 3: Add Traefik

```yaml
# docker-compose.yml (your project)
services:
  traefik:
    image: traefik:v3.0
    ports: ["80:80", "443:443"]
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock

  volta-auth-proxy:
    image: volta-auth-proxy:latest
    ports: ["7070:7070"]
    env_file: .env

  my-app:
    build: .
    labels:
      - "traefik.http.routers.my-app.rule=Host(`pm.example.com`)"
      - "traefik.http.routers.my-app.middlewares=volta-auth"
```

### Step 4: Read headers in your app

```java
app.get("/api/tasks", ctx -> {
    String tenantId = ctx.header("X-Volta-Tenant-Id");
    // Done. You have multi-tenant auth.
});
```

**Kai:** ...that's really it.

**Rin:** That's really it.

---

## Scene 4: "What about invitations?"

**Kai:** OK but how do users join a workspace? I need invitations.

**Rin:** volta handles that too. You don't write any invitation code. volta has a built-in invitation system:

1. Admin opens `https://auth.example.com/admin/invitations`
2. Creates an invitation link
3. Shares it via Slack or email
4. New user clicks the link → Google login → consent screen → joins workspace

Your app doesn't know any of this happened. Next time that user accesses your app, the `X-Volta-Tenant-Id` header just includes them.

**Kai:** What if I want to show a "Members" page in my app?

**Rin:** Call volta's [Internal API](glossary/internal-api.md):

```java
app.get("/app/team", ctx -> {
    String jwt = ctx.header("X-Volta-JWT");
    String tenantId = ctx.header("X-Volta-Tenant-Id");

    // Ask volta for the member list
    var response = httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create(
                "http://volta-auth-proxy:7070/api/v1/tenants/" + tenantId + "/members"
            ))
            .header("Authorization", "Bearer " + jwt)
            .build(),
        HttpResponse.BodyHandlers.ofString()
    );

    ctx.json(response.body());
});
```

**Kai:** So volta is like... the single source of truth for users and tenants, and my app just asks volta when it needs that info?

**Rin:** Exactly. Your app owns tasks and projects. volta owns users, tenants, roles, and sessions. Clean separation.

---

## Scene 5: "What about the frontend?"

**Kai:** My app has a [JavaScript](glossary/javascript.md) frontend. What happens when the user's session expires?

**Rin:** Add volta-sdk-js. One script tag.

```html
<script src="http://volta-auth-proxy:7070/js/volta.js"></script>
<script>
  Volta.init({ gatewayUrl: "http://volta-auth-proxy:7070" });
</script>
```

Now use `Volta.fetch()` instead of `fetch()`:

```javascript
// Before (session expires → broken)
const res = await fetch("/api/tasks");

// After (session expires → auto-refresh → retry → works)
const res = await Volta.fetch("/api/tasks");
```

**Kai:** It automatically refreshes the session?

**Rin:** Yes. If the [JWT](glossary/jwt.md) expires (every 5 minutes), the SDK silently refreshes it. The user never sees a login screen during normal use. If the session truly expires (after 8 hours), the SDK redirects to login and brings them back to where they were.

**Kai:** What about tenant switching? Some users belong to multiple workspaces.

**Rin:**

```javascript
// Switch to a different workspace
await Volta.switchTenant("other-workspace-id");
// Page automatically reloads with new tenant context
```

---

## Scene 6: "What about roles?"

**Kai:** I need admins to manage the team, but regular members should only see their own tasks.

**Rin:** volta has 4 [roles](glossary/role.md):

```
OWNER  → Can delete the workspace, transfer ownership
ADMIN  → Can invite/remove members, change roles
MEMBER → Normal usage
VIEWER → Read-only
```

In your app:

```java
app.delete("/api/tasks/{id}", ctx -> {
    VoltaUser user = VoltaAuth.getUser(ctx);

    if (!user.hasRole("ADMIN")) {
        ctx.status(403).json(Map.of("error", "Admins only"));
        return;
    }

    db.execute("DELETE FROM tasks WHERE id = ? AND tenant_id = ?",
        ctx.pathParam("id"), user.getTenantId());
    ctx.status(204);
});
```

**Kai:** And the role assignment happens in volta?

**Rin:** Yes. `https://auth.example.com/admin/members` — admins can change roles there. Your app just reads `X-Volta-Roles` and enforces business rules.

---

## Scene 7: "What DON'T I need to build?"

**Kai:** Let me make sure I understand. What do I NOT need to build?

**Rin:**

```
You do NOT build:
  ❌ Login page
  ❌ Google OAuth integration
  ❌ Session management
  ❌ JWT issuance or verification (SDK does it)
  ❌ User registration / signup
  ❌ Password management
  ❌ Invitation system
  ❌ Tenant/workspace creation
  ❌ Member management
  ❌ Role management
  ❌ "Forgot password" flow
  ❌ MFA (volta handles this in Phase 3)
  ❌ CSRF protection for auth (volta handles it)

You DO build:
  ✅ Your business logic (tasks, projects, etc.)
  ✅ Your frontend UI
  ✅ Your database (tasks, projects — NOT users/tenants)
  ✅ Tenant-scoped queries (WHERE tenant_id = ?)
  ✅ Role-based access checks in your business logic
```

**Kai:** That's... a lot of stuff I don't have to build.

**Rin:** That's the point. volta handles the auth infrastructure. You focus on what makes your app unique.

---

## Scene 8: "One last question"

**Kai:** What happens if volta goes down?

**Rin:** Good question. volta is the [ForwardAuth](glossary/forwardauth.md) checkpoint. If volta is down:

- New requests can't be authenticated → Traefik returns 401
- Users with cached pages might see data but can't make API calls
- Your app itself stays up — it just can't verify new requests

For Phase 1 (single instance), this is a known [trade-off](glossary/tradeoff.md). Phase 2 adds Redis sessions + horizontal scaling for high availability.

**Kai:** Honestly, for my scale right now, that's fine. I'd rather have simple auth that I understand than complex auth that I don't.

**Rin:** That's the volta philosophy. [Choose the hell you understand](glossary/native-implementation.md).

**Kai:** When do I start?

**Rin:**

```bash
git clone git@github.com:opaopa6969/volta-auth-proxy.git
```

Right now.

---

> **Every term in this conversation is clickable.** If you don't know what something means, click it. [That's not embarrassing — that's learning.](../README.md)
