# Caddy

[日本語版はこちら](caddy.ja.md)

---

## What is it?

Caddy is a modern, open-source web server written in Go that is best known for one thing: **automatic HTTPS by default**. When you point a domain at Caddy, it automatically obtains and renews TLS certificates from Let's Encrypt without any configuration. No certbot, no cron jobs, no manual renewal.

Think of Caddy like a hotel concierge who automatically handles all the paperwork for every guest. You just show up and say your name, and the concierge handles the key card, the room assignment, and the checkout time. Other hotels ([nginx](nginx.md)) make you fill out forms yourself. [Traefik](traefik.md) also has automatic check-in, but the concierge desk is more complex.

Caddy's configuration language (the "Caddyfile") is intentionally minimal and human-readable. Where nginx requires pages of directives and Traefik needs YAML/TOML files or Docker labels, Caddy often needs just a few lines.

---

## Why does it matter?

TLS misconfigurations are one of the most common security problems on the internet. Expired certificates, weak cipher suites, missing redirects from HTTP to HTTPS -- these happen constantly because TLS setup is traditionally manual and error-prone.

Caddy eliminates this entire class of problems. HTTPS is not a feature you enable -- it is the default you would have to explicitly disable.

For volta-auth-proxy users, Caddy matters as a supported reverse proxy alternative. If you already use Caddy or prefer its simplicity, you do not need to switch to Traefik. Caddy's `forward_auth` directive provides the same ForwardAuth functionality that volta needs.

---

## How does it work?

### The Caddyfile

Caddy's configuration is dramatically simpler than its competitors:

```caddyfile
app.example.com {
    forward_auth volta:7070 {
        uri /verify
        copy_headers X-Volta-User X-Volta-Tenant
    }
    reverse_proxy backend:8080
}
```

That is it. Five lines. Caddy will:
1. Automatically obtain a TLS certificate for `app.example.com`
2. Redirect HTTP to HTTPS
3. For every request, call volta's `/verify` endpoint
4. If volta returns 200, copy identity headers and forward to the backend
5. If volta returns 401/302, return that to the client

Compare this to the equivalent [nginx](nginx.md) config (15+ lines) or [Traefik](traefik.md) Docker labels.

### forward_auth directive

The `forward_auth` directive is Caddy's equivalent of Traefik's ForwardAuth middleware and nginx's `auth_request` module.

```
  Browser ──► Caddy ──► forward_auth ──► volta-auth-proxy
                           │
                           ├── 200 OK → copy headers → reverse_proxy → Backend
                           └── 401/302 → return to browser
```

Configuration options:

| Option | Description |
|--------|-------------|
| `uri` | The path to call on the auth server (e.g., `/verify`) |
| `copy_headers` | Headers to copy from the auth response to the upstream request |
| `copy_headers {header} {rename}` | Copy and rename headers |
| `header_up` | Add headers to the auth request (e.g., original URI) |

### Automatic HTTPS

Caddy uses the ACME protocol to automatically:

1. Detect that a domain needs a certificate
2. Request a certificate from Let's Encrypt (or ZeroSSL)
3. Complete the ACME challenge (HTTP-01 or TLS-ALPN-01)
4. Install the certificate
5. Renew before expiration (typically 30 days before)

This happens silently in the background. No configuration needed.

### Caddy vs Traefik vs nginx

| Feature | Caddy | [Traefik](traefik.md) | [nginx](nginx.md) |
|---------|-------|---------|-------|
| Auto HTTPS | Default (always on) | Built-in (needs config) | Requires certbot |
| ForwardAuth | `forward_auth` directive | ForwardAuth middleware | `auth_request` module |
| Config simplicity | Excellent (Caddyfile) | Moderate (labels/YAML) | Complex (custom syntax) |
| Auto-discovery | Limited (via plugins) | Native (Docker, K8s) | None |
| Performance | Good | Good | Excellent |
| Written in | Go | Go | C |
| Plugin system | Yes (Go modules) | Yes (Go plugins) | Yes (C modules) |
| API for config | Yes (JSON API) | Yes (REST API) | No (file-based) |

### Caddy as an API gateway

Caddy can also serve as a lightweight API gateway with features like:

- Rate limiting (via plugins)
- Request/response header manipulation
- Path rewriting
- Load balancing with health checks
- WebSocket proxying

---

## How does volta-auth-proxy use it?

volta-auth-proxy supports Caddy as an alternative reverse proxy alongside [Traefik](traefik.md) (recommended) and [nginx](nginx.md).

### Integration setup

```caddyfile
# Caddyfile for volta + your app
{
    # Global options (optional)
    email admin@example.com
}

# Your protected application
app.example.com {
    forward_auth volta:7070 {
        uri /verify
        copy_headers X-Volta-User X-Volta-Tenant X-Volta-Roles
    }
    reverse_proxy backend:8080
}

# volta's own endpoints (login, callback) -- not behind forward_auth
auth.example.com {
    reverse_proxy volta:7070
}
```

### When to choose Caddy

- You want the simplest possible configuration
- Auto-HTTPS with zero effort is important to you
- You do not need Docker auto-discovery (your infrastructure is relatively static)
- You value readable configuration over feature density
- You are already a Caddy user

### When NOT to choose Caddy

- You need Docker label-based auto-discovery (use [Traefik](traefik.md))
- You need maximum raw throughput (use [nginx](nginx.md))
- You need a built-in dashboard for monitoring (use [Traefik](traefik.md))

---

## Common mistakes and attacks

### Mistake 1: Forgetting that HTTPS is automatic

Caddy enables HTTPS by default. If you are testing locally with `localhost`, Caddy will generate a self-signed certificate. This can confuse developers who are not expecting TLS on their local setup. Use `http://` explicitly in the Caddyfile if you want plain HTTP for development.

### Mistake 2: Not using copy_headers

If you set up `forward_auth` but forget `copy_headers`, your backend will not receive volta's identity headers. The request will be authenticated but the backend will not know who the user is.

### Mistake 3: Putting volta's login endpoints behind forward_auth

The login page, callback URL, and public endpoints must NOT be behind `forward_auth`. Otherwise, users cannot reach the login page because they are not yet authenticated -- a chicken-and-egg problem.

### Mistake 4: Ignoring Caddy's JSON API in production

Caddy has a powerful admin API that can modify configuration at runtime. If this API is exposed without protection, an attacker can reconfigure your entire proxy. Bind the admin API to localhost only or disable it.

### Attack: Certificate transparency monitoring

Because Caddy automatically requests certificates, the domain names appear in Certificate Transparency logs. Attackers monitor these logs to discover new services. This is not a Caddy-specific issue, but automatic cert provisioning makes it more likely that internal staging domains get exposed.

---

## Further reading

- [Caddy official documentation](https://caddyserver.com/docs/) -- The complete reference.
- [Caddy forward_auth directive](https://caddyserver.com/docs/caddyfile/directives/forward_auth) -- The specific feature volta uses.
- [traefik.md](traefik.md) -- volta's recommended proxy with ForwardAuth.
- [nginx.md](nginx.md) -- Traditional alternative with `auth_request`.
- [forwardauth.md](forwardauth.md) -- The ForwardAuth pattern explained.
- [reverse-proxy.md](reverse-proxy.md) -- What a reverse proxy is and why you need one.
