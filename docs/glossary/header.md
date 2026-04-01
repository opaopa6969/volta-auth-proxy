# HTTP Header

[日本語版はこちら](header.ja.md)

---

## What is it?

An HTTP header is a piece of metadata attached to an HTTP request or response. When your browser asks a server for a web page, it sends headers along with the request -- and the server sends headers back with the response. Headers carry information *about* the message, not the message content itself.

Think of it like the envelope of a letter. The letter inside is the actual content (the web page, the JSON data, the image). The envelope has the return address, the destination, the postage stamp, and maybe a "FRAGILE" sticker. HTTP headers are the envelope -- they tell the server and the browser important things about the message without being the message itself.

---

## Why does it matter?

Headers matter enormously in volta-auth-proxy because **headers are how identity travels from the proxy to your application**. When volta authenticates a user, it does not modify the request body or inject JavaScript. It sets HTTP headers -- `X-Volta-User-Id`, `X-Volta-Tenant-Id`, `X-Volta-Roles`, and others -- that Traefik passes to your downstream app.

Your app reads these headers and knows exactly who the user is, which tenant they belong to, and what role they have. No SDK required. No auth library. Just headers.

---

## Common headers you already use

You encounter HTTP headers every day, even if you do not realize it:

```
  Request headers (browser → server):
  ┌──────────────────────────────────────────────────┐
  │ Host: wiki.example.com                           │  ← Which website?
  │ Cookie: __volta_session=abc123                   │  ← Session token
  │ Accept: text/html                                │  ← I want HTML
  │ User-Agent: Mozilla/5.0 (Chrome)                 │  ← I'm Chrome
  │ Accept-Language: en-US                           │  ← I speak English
  └──────────────────────────────────────────────────┘

  Response headers (server → browser):
  ┌──────────────────────────────────────────────────┐
  │ Content-Type: text/html; charset=utf-8           │  ← Here's HTML
  │ Set-Cookie: __volta_session=xyz789; HttpOnly     │  ← Remember this
  │ Cache-Control: no-store                          │  ← Don't cache this
  │ X-Volta-Request-Id: req-12345                    │  ← Tracking ID
  └──────────────────────────────────────────────────┘
```

Headers are key-value pairs: a name and a value, separated by a colon.

---

## The X-Volta-* custom headers

HTTP allows custom headers. By convention, custom headers use a prefix to avoid collision with standard headers. volta uses the `X-Volta-` prefix for all identity-related headers.

When volta's [ForwardAuth](forwardauth.md) endpoint authenticates a request, it returns these headers to Traefik, which forwards them to your app:

| Header | Example value | Meaning |
|--------|---------------|---------|
| `X-Volta-User-Id` | `550e8400-e29b-41d4-a716-446655440000` | The authenticated user's UUID |
| `X-Volta-Email` | `taro@acme.com` | The user's email address |
| `X-Volta-Display-Name` | `Taro Yamada` | The user's display name |
| `X-Volta-Tenant-Id` | `660e8400-e29b-41d4-a716-446655440001` | The current tenant's UUID |
| `X-Volta-Tenant-Slug` | `acme` | The tenant's URL-friendly identifier |
| `X-Volta-Roles` | `ADMIN` | The user's role in this tenant |
| `X-Volta-JWT` | `eyJhbGciOiJSUzI1NiIs...` | A short-lived JWT with all claims |
| `X-Volta-App-Id` | `app-wiki` | The matched app from volta-config.yaml |

### How headers flow through the system

```
  Browser                  Traefik              volta           Your App
  ═══════                  ═══════              ═════           ════════

  GET /dashboard
  Cookie: __volta_session=abc
  ─────────────────────►
                          Forward to volta
                          for auth check
                          ──────────────────►
                                              Session valid.
                                              User: taro
                                              Tenant: acme
                                              Role: ADMIN
                          ◄──────────────────
                          200 OK
                          X-Volta-User-Id: taro-uuid
                          X-Volta-Tenant-Id: acme-uuid
                          X-Volta-Roles: ADMIN

                          Forward original request
                          + volta headers
                          ─────────────────────────────────►
                                                            GET /dashboard
                                                            X-Volta-User-Id: taro-uuid
                                                            X-Volta-Tenant-Id: acme-uuid
                                                            X-Volta-Roles: ADMIN
                                                            X-Volta-JWT: eyJ...

                                                            App reads headers.
                                                            No auth code needed.
```

### Reading headers in your app

```java
// Javalin
app.get("/api/data", ctx -> {
    String userId = ctx.header("X-Volta-User-Id");
    String tenantId = ctx.header("X-Volta-Tenant-Id");
    String role = ctx.header("X-Volta-Roles");

    var data = db.query("SELECT * FROM items WHERE tenant_id = ?", tenantId);
    ctx.json(data);
});
```

```python
# Flask
@app.route('/api/data')
def get_data():
    user_id = request.headers.get('X-Volta-User-Id')
    tenant_id = request.headers.get('X-Volta-Tenant-Id')
    role = request.headers.get('X-Volta-Roles')

    data = db.execute("SELECT * FROM items WHERE tenant_id = %s", tenant_id)
    return jsonify(data)
```

```javascript
// Express.js
app.get('/api/data', (req, res) => {
    const userId = req.headers['x-volta-user-id'];
    const tenantId = req.headers['x-volta-tenant-id'];
    const role = req.headers['x-volta-roles'];

    // Note: header names are lowercase in Express
});
```

---

## Security: why you must trust headers only through Traefik

Headers can be set by anyone. A malicious user could send:

```
curl -H "X-Volta-User-Id: admin-uuid" https://wiki.example.com/api/data
```

If your app trusts this header without going through Traefik's ForwardAuth, the attacker just impersonated an admin. Headers are only trustworthy when:

1. The request goes through Traefik (which strips client-sent `X-Volta-*` headers)
2. Traefik's ForwardAuth middleware calls volta to verify the session
3. volta's response headers replace anything the client sent

This is why apps must **never** be directly accessible without the reverse proxy.

---

## Further reading

- [forwardauth.md](forwardauth.md) -- The mechanism that populates volta headers.
- [jwt.md](jwt.md) -- The `X-Volta-JWT` header for higher-security verification.
- [http.md](http.md) -- HTTP protocol basics.
- [cookie.md](cookie.md) -- The session cookie that starts the auth flow.
