# Domain

[日本語版はこちら](domain.ja.md)

---

## In one sentence?

A domain is the human-readable address of a website -- like `example.com` -- so you don't have to memorize numbers like `93.184.216.34`.

---

## Your house address on the internet

Imagine the internet is a huge city. Every building (server) has a street number (IP address like `93.184.216.34`), but nobody remembers numbers. So we give buildings names:

| Real world | Internet |
|---|---|
| "The Hilton Hotel" | `example.com` |
| Street address: 123 Main St | IP address: `93.184.216.34` |
| You tell the taxi the name | [Browser](browser.md) resolves the domain to an IP |
| The phone book maps name to address | DNS (Domain Name System) maps domain to IP |

- `google.com` is a domain
- `volta.example.com` is a domain (specifically, a [subdomain](subdomain.md))
- `93.184.216.34` is NOT a domain -- it's an IP address

---

## Why do we need this?

Without domains:

- You'd have to memorize IP addresses for every website (`142.250.80.46` instead of `google.com`)
- If the server moves to a new IP, everyone's bookmarks break
- There would be no way to host multiple websites on the same server
- [SSL/TLS](ssl-tls.md) certificates couldn't identify websites reliably
- [Cookies](cookie.md) would have no way to scope themselves properly

Domains are the foundation of web identity. They determine where [cookies](cookie.md) are sent, which [CORS](cors.md) rules apply, and how [SSL/TLS](ssl-tls.md) certificates are validated.

---

## Domain in volta-auth-proxy

volta uses domains extensively for multi-tenant architecture:

| Concept | Example | Purpose |
|---|---|---|
| Base domain | `example.com` | The organization's root domain |
| Auth domain | `volta.example.com` | Where volta-auth-proxy lives |
| App [subdomain](subdomain.md) | `app.acme.example.com` | Tenant-specific app URL |
| [Wildcard certificate](wildcard-certificate.md) | `*.example.com` | One cert covers all subdomains |

Key domain-related behaviors:

- **Tenant resolution** -- volta determines which tenant a request belongs to by inspecting the domain/subdomain
- **Cookie scoping** -- The `__volta_session` [cookie](cookie.md) is scoped to the volta domain to prevent leakage
- **[Redirect URI](redirect-uri.md) validation** -- volta validates that [OAuth2](oauth2.md) redirect URIs match allowed domains to prevent [open redirect](open-redirect.md) attacks
- **[CORS](cors.md) origins** -- volta only allows [cross-origin](cross-origin.md) requests from known domains

---

## Concrete example

How domains work in a typical volta deployment:

1. Your company owns `acme.com`
2. You set up volta at `auth.acme.com`
3. Your SaaS app lives at `app.acme.com`
4. Tenant "Globex" accesses the app at `globex.app.acme.com`
5. When Globex's user visits that URL:
   - The [browser](browser.md) resolves `globex.app.acme.com` to an IP via DNS
   - The [reverse proxy](reverse-proxy.md) receives the request
   - volta reads the [subdomain](subdomain.md) (`globex`) to identify the tenant
   - The [wildcard certificate](wildcard-certificate.md) (`*.app.acme.com`) secures the connection

---

## Learn more

- [Subdomain](subdomain.md) -- Sub-addresses like `app.example.com` under a domain
- [SSL/TLS](ssl-tls.md) -- How domains get encrypted connections
- [Wildcard Certificate](wildcard-certificate.md) -- One cert for all subdomains
- [Cookie](cookie.md) -- How cookies are scoped to domains
- [Cross-Origin](cross-origin.md) -- Security rules between different domains
- [Redirect URI](redirect-uri.md) -- Why domain validation matters for OAuth
