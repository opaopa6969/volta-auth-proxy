# Cloudflare Zero Trust

[日本語版はこちら](cloudflare-zero-trust.ja.md)

---

## What is it?

Cloudflare Zero Trust (formerly Cloudflare Access) is a cloud-based identity-aware proxy service that sits between users and your applications, authenticating every request before allowing access. It is part of Cloudflare's broader Zero Trust platform, which also includes a secure web gateway, DNS filtering, and browser isolation.

Think of it like a cloud-hosted security guard stationed at the entrance to your building. Instead of having a lock on the door that anyone with a key can open, the security guard checks every person's ID badge, verifies they are on the access list, and only then opens the door. The guard works for Cloudflare, not for you -- you pay Cloudflare a monthly fee, and they handle everything.

The "Zero Trust" part means: never trust a request just because it comes from inside the corporate network. Every request, from every user, from every device, must be authenticated and authorized. This is the opposite of the traditional "castle and moat" approach where everything inside the VPN is trusted.

---

## Why does it matter?

Cloudflare Zero Trust is relevant to volta-auth-proxy because it solves a similar problem -- putting authentication in front of web applications -- but with a fundamentally different architecture:

| Aspect | Cloudflare Zero Trust | volta-auth-proxy |
|--------|----------------------|-----------------|
| Where it runs | Cloudflare's edge network (cloud) | Your server (self-hosted) |
| Data sovereignty | Traffic flows through Cloudflare | Traffic stays on your infrastructure |
| Authentication | Delegates to IdPs (Google, Okta, etc.) | Direct [OIDC](oidc.md) with Google |
| Pricing | Free (50 users) to $7+/user/month | Free (open source) |
| Customization | Dashboard configuration | Code-level control |
| Multi-tenancy | Per-application policies | Per-tenant isolation |

For organizations that must keep traffic on-premise (healthcare, finance, government, data sovereignty regulations), Cloudflare Zero Trust is not an option -- all traffic routes through Cloudflare's servers. volta-auth-proxy runs entirely on your infrastructure.

---

## How does it work?

### Architecture

```
  User's Browser
       │
       │  HTTPS
       ▼
  ┌──────────────────────────┐
  │  Cloudflare Edge Network  │
  │                           │
  │  1. Receive request       │
  │  2. Check for CF token    │
  │     (cookie/header)       │
  │                           │
  │  No token:                │
  │  3. Redirect to IdP       │──► Google / Okta / Azure AD
  │  4. User authenticates    │◄── (OIDC callback)
  │  5. Issue CF access token │
  │                           │
  │  Has valid token:         │
  │  6. Check access policy   │
  │  7. Forward to origin     │
  └──────────────────────────┘
       │
       │  Cloudflare Tunnel (or DNS)
       ▼
  ┌──────────────┐
  │  Your Server  │  (origin, never exposed directly)
  │  (app)        │
  └──────────────┘
```

### Key components

| Component | Description |
|-----------|-------------|
| **Access** | The authentication/authorization layer. Checks identity before allowing requests through. |
| **Tunnel** | A secure connection from your server to Cloudflare, so your origin is never directly exposed to the internet. |
| **Gateway** | DNS and HTTP filtering. Blocks malicious sites and enforces browsing policies. |
| **WARP** | Client-side agent for device posture and secure connectivity. |

### Access policies

Cloudflare Access policies define who can reach each application:

```
Application: app.example.com
Policy: Allow
  - Emails ending in @company.com
  - From identity provider: Google Workspace
  - With device posture: Managed device with disk encryption

Policy: Deny
  - Everyone else
```

### Cloudflare Tunnel

Traditionally, to put a web app behind Cloudflare, you need to expose a public IP. Cloudflare Tunnel eliminates this by running a small daemon (`cloudflared`) on your server that creates an outbound-only connection to Cloudflare:

```
  Internet ──► Cloudflare Edge ◄──── cloudflared ──── Your App
                                  (outbound only)
```

Your server has no open inbound ports. Attackers cannot reach it directly.

### Comparison with self-hosted alternatives

| Feature | Cloudflare Zero Trust | volta + [Traefik](traefik.md) | [Keycloak](keycloak.md) + Proxy |
|---------|----------------------|-------------------------------|--------------------------------|
| Deployment | Cloud (Cloudflare manages) | Self-hosted (you manage) | Self-hosted (you manage) |
| DDoS protection | Built-in | Separate solution needed | Separate solution needed |
| Data path | Through Cloudflare | Through your infrastructure | Through your infrastructure |
| Customization | Dashboard only | Full code control | Admin console + themes |
| Pricing | Per user/month | Free (open source) | Free (open source) |
| Offline operation | No (depends on Cloudflare) | Yes | Yes |
| Multi-tenancy | Application-level | Tenant-level | Realm-level |

---

## How does volta-auth-proxy use it?

volta-auth-proxy does **not** use Cloudflare Zero Trust. volta is designed to **replace** Cloudflare Zero Trust for organizations that need self-hosted identity-aware proxying.

### Why replace Cloudflare Zero Trust?

1. **Data sovereignty**: With Cloudflare, all traffic -- including authentication tokens, request bodies, and headers -- passes through Cloudflare's infrastructure. For healthcare (HIPAA), finance, or EU organizations with strict GDPR requirements, this may be unacceptable.

2. **Vendor dependency**: If Cloudflare has an outage, your authentication stops working. volta runs on your infrastructure, so you control availability.

3. **Cost at scale**: Cloudflare Zero Trust costs $7+/user/month for the paid tier. For a SaaS with 10,000 users, that is $70,000/year just for the auth proxy. volta is free.

4. **Multi-tenant SaaS**: Cloudflare Access policies are per-application, not per-tenant. volta was built specifically for multi-tenant SaaS where each tenant has different configurations, domains, and access rules.

5. **Customization**: Cloudflare's policies are configured through a dashboard with predefined options. volta gives you code-level control over every authentication decision.

### Migration path from Cloudflare to volta

For teams currently using Cloudflare Access who want to self-host:

1. Set up volta-auth-proxy with [Traefik](traefik.md) ForwardAuth
2. Configure the same [OIDC](oidc.md) provider (Google, etc.) in volta
3. Migrate DNS from Cloudflare to your own infrastructure (or keep Cloudflare for DNS/CDN only)
4. Remove Cloudflare Access policies
5. Use Cloudflare Tunnel alternative (WireGuard, Tailscale) if you still want tunneling

---

## Common mistakes and attacks

### Mistake 1: Assuming Cloudflare sees nothing

All HTTP traffic passes through Cloudflare in plaintext (they terminate TLS). This means Cloudflare can technically see request bodies, cookies, and tokens. For sensitive data, this is a significant trust decision.

### Mistake 2: Not securing the origin

If your origin server is directly reachable (has a public IP), attackers can bypass Cloudflare entirely by connecting directly. Use Cloudflare Tunnel or firewall rules to ensure the origin only accepts connections from Cloudflare.

### Mistake 3: Overly broad policies

A policy like "allow all @company.com emails" might be too broad if contractors or former employees retain Google Workspace access. Use group-based policies and regularly audit membership.

### Mistake 4: Ignoring device posture

Cloudflare Zero Trust can check device posture (OS version, disk encryption, screen lock). Not using this means a compromised personal device with valid credentials gets full access.

### Attack: JWT confusion on the origin

Cloudflare Access issues its own JWT (CF-Access-Jwt-Assertion header). If your origin does not validate this JWT properly (checking issuer, audience, and signature), an attacker who discovers the JWT format could forge one. Always validate the CF JWT at the origin.

---

## Further reading

- [Cloudflare Zero Trust documentation](https://developers.cloudflare.com/cloudflare-one/) -- Official docs.
- [Cloudflare Access](https://developers.cloudflare.com/cloudflare-one/policies/access/) -- Access policy documentation.
- [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/) -- Tunnel setup guide.
- [forwardauth.md](forwardauth.md) -- The self-hosted pattern that replaces Cloudflare Access.
- [reverse-proxy.md](reverse-proxy.md) -- How reverse proxies work.
- [oidc.md](oidc.md) -- The protocol used for authentication.
- [sso.md](sso.md) -- Single sign-on concepts.
