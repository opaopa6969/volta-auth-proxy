# Zero Trust

[日本語版はこちら](zero-trust.ja.md)

---

## What is it?

Zero Trust is a security model based on one core principle: never trust, always verify. No user, device, or network request is trusted by default, even if it comes from inside your own network.

Think of it like a hospital with locked medication cabinets. In an old hospital, anyone wearing scrubs who walked into the pharmacy could grab medication -- the assumption was "if you're inside the building, you're trusted." In a Zero Trust hospital, every cabinet requires a badge scan, every medication request is logged, and even doctors must prove their identity every time they access something. Being inside the building does not mean you are trusted.

Traditional network security is like a castle with a moat: once you are past the walls (the firewall), you can go anywhere. Zero Trust says: there are no walls. Every door is locked. Every person must show ID at every door.

---

## Why does it matter?

The old model ("trust the internal network") fails because:

1. **VPNs are not enough.** If an attacker gets VPN access (stolen credentials, compromised laptop), they have access to everything on the network.
2. **Insiders can be threats.** Not every threat comes from outside. Disgruntled employees, compromised accounts, and lateral movement attacks all exploit internal trust.
3. **Cloud blurs the perimeter.** When your apps are on AWS, your database is on GCP, and your users are remote, there is no "inside" anymore. The network perimeter does not exist.
4. **Remote work is permanent.** Employees accessing systems from home, coffee shops, and airports need the same security regardless of location.

Zero Trust assumes the network is always hostile. Every request must prove it is legitimate.

---

## Zero Trust in practice

### Cloudflare Zero Trust

Cloudflare Zero Trust (formerly Cloudflare Access) is a commercial implementation of Zero Trust principles. It puts an authentication layer in front of your applications:

```
  Without Zero Trust:
  ┌──────────┐     ┌──────────────┐
  │ Internet │────►│ Your App     │  ← Anyone on the network can reach it
  └──────────┘     └──────────────┘

  With Cloudflare Zero Trust:
  ┌──────────┐     ┌────────────────┐     ┌──────────────┐
  │ Internet │────►│ Cloudflare     │────►│ Your App     │
  └──────────┘     │ (verify first) │     └──────────────┘
                   └────────────────┘
                   "Who are you? Prove it."
```

### How volta implements Zero Trust principles

volta-auth-proxy's [ForwardAuth](forwardauth.md) pattern is a Zero Trust implementation at the application layer:

```
  volta ForwardAuth = Zero Trust for your apps:

  ┌──────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────┐
  │ Browser  │────►│ Traefik      │────►│ volta        │     │ Your App │
  └──────────┘     │ (reverse     │     │ (verify      │     └──────────┘
                   │  proxy)      │     │  identity,   │          ▲
                   └──────────────┘     │  check role, │          │
                          │             │  check tenant│          │
                          │             └──────────────┘          │
                          │                    │                  │
                          │              200 OK + headers         │
                          └──────────────────────────────────────►│
                                 (only if volta says OK)
```

Every request to every app goes through this flow. No exceptions. No "trusted" networks. Even internal services behind the reverse proxy are protected. This is Zero Trust:

| Zero Trust principle | volta's implementation |
|---------------------|----------------------|
| **Never trust, always verify** | Every request goes through ForwardAuth. No bypass. |
| **Verify identity** | Session cookie validated against PostgreSQL on every request. |
| **Verify authorization** | User's role checked against app's `allowed_roles` on every request. |
| **Verify context** | Tenant membership confirmed. Suspended tenants blocked. |
| **Minimal privilege** | Apps only see the headers volta provides. No direct database access to user data. |
| **Assume breach** | JWTs expire in 5 minutes. Sessions have sliding + absolute timeouts. Key rotation supported. |

### Comparison with Cloudflare Zero Trust

| Feature | Cloudflare Zero Trust | volta ForwardAuth |
|---------|----------------------|-------------------|
| Network layer | Edge (CDN level) | Application layer (reverse proxy) |
| Identity source | Multiple IdPs | Google OIDC (+ configurable IdPs) |
| Multi-tenancy | No (single org) | Yes (core feature) |
| Self-hosted | No (Cloudflare cloud) | Yes (only) |
| Cost | Usage-based pricing | $0 |
| Best for | Protecting internal tools, VPN replacement | Multi-tenant SaaS app auth |

They solve related but different problems. Cloudflare Zero Trust replaces VPNs and protects access to internal tools. volta protects multi-tenant SaaS applications with tenant-aware identity.

---

## Simple example

Without Zero Trust:

```
  1. Employee connects to company VPN
  2. VPN grants access to internal network
  3. Employee can reach ANY internal service
  4. If attacker steals VPN credentials → access to everything
```

With Zero Trust (volta-style):

```
  1. User visits wiki.example.com
  2. Traefik asks volta: "Is this user allowed?"
  3. volta checks: valid session? Active user? Correct tenant? Right role?
  4. Only if ALL checks pass → request goes through
  5. If attacker steals a session → access only to apps that session's role allows
     → session expires in 8 hours max
     → admin can revoke immediately
```

---

## Further reading

- [forwardauth.md](forwardauth.md) -- How volta's ForwardAuth implements Zero Trust principles.
- [session.md](session.md) -- Session validation on every request (the "always verify" part).
- [rbac.md](rbac.md) -- Role-based access control (the "minimal privilege" part).
- [jwt.md](jwt.md) -- Short-lived tokens as part of the "assume breach" principle.
