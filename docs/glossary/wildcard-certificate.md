# Wildcard Certificate

[日本語版はこちら](wildcard-certificate.ja.md)

---

## In one sentence?

A wildcard certificate is a single [SSL/TLS](ssl-tls.md) certificate that covers a [domain](domain.md) and all its [subdomains](subdomain.md) at one level -- like `*.example.com` covering `app.example.com`, `auth.example.com`, and anything else ending in `.example.com`.

---

## One key for every apartment in the building

Remember the [subdomain](subdomain.md) analogy where a [domain](domain.md) is a building and subdomains are apartments? A wildcard certificate is like a master key:

| Individual certificates | Wildcard certificate |
|---|---|
| Each apartment gets its own key | One master key opens all apartments |
| Add a new apartment? Get a new key | Add a new apartment? Master key already works |
| Manage 50 keys for 50 apartments | Manage 1 key for all apartments |
| Each key expires separately | One expiration date to track |
| More secure (one stolen key = one room) | Riskier (one stolen key = all rooms) |

The `*` in `*.example.com` means "any single word here":

| Matches | Does NOT match |
|---|---|
| `app.example.com` | `example.com` (no subdomain) |
| `auth.example.com` | `deep.app.example.com` (two levels deep) |
| `anything.example.com` | `other-domain.com` (different domain) |

---

## Why do we need this?

Without wildcard certificates:

- You'd need a separate certificate for every [subdomain](subdomain.md) -- in a multi-tenant system, that's one per tenant
- Adding a new tenant would require obtaining and configuring a new certificate
- Certificate renewal becomes an operational nightmare at scale
- Some certificate authorities charge per certificate, increasing costs

With a wildcard certificate, adding a new tenant subdomain requires zero certificate changes.

---

## Wildcard certificate in volta-auth-proxy

volta's multi-tenant architecture relies heavily on wildcard certificates:

```
  Certificate: *.app.example.com
  ┌────────────────────────────────────────┐
  │  Covers:                               │
  │  ✓ acme.app.example.com               │
  │  ✓ globex.app.example.com             │
  │  ✓ initech.app.example.com            │
  │  ✓ any-new-tenant.app.example.com     │
  │                                        │
  │  Does NOT cover:                       │
  │  ✗ app.example.com (no wildcard part)  │
  │  ✗ auth.example.com (different branch) │
  │  ✗ deep.sub.app.example.com           │
  └────────────────────────────────────────┘
```

In practice, a volta deployment typically needs:

| Certificate | Covers | Purpose |
|---|---|---|
| `*.example.com` | `auth.example.com`, `app.example.com` | Top-level services |
| `*.app.example.com` | `acme.app.example.com`, `globex.app.example.com`, etc. | Tenant subdomains |

**Where the certificate lives:**

The wildcard certificate is configured on the [reverse proxy](reverse-proxy.md) (Traefik/Nginx), NOT on volta itself. volta handles [HTTP](http.md) traffic on the internal [Docker network](network-isolation.md) where no TLS is needed.

**Automatic renewal with Let's Encrypt:**

Wildcard certificates from Let's Encrypt require DNS-01 challenge (proving you control the domain via DNS records), unlike regular certificates which can use HTTP-01 challenge. Traefik can handle this automatically.

---

## Concrete example

Setting up wildcard certificates for a volta deployment:

1. You own `myproduct.com` and use Cloudflare for DNS
2. Configure Traefik to use Let's Encrypt with DNS-01 challenge:
   ```yaml
   # traefik.yml (simplified)
   certificatesResolvers:
     letsencrypt:
       acme:
         email: admin@myproduct.com
         storage: /data/acme.json
         dnsChallenge:
           provider: cloudflare
   ```
3. Traefik automatically requests `*.app.myproduct.com` from Let's Encrypt
4. Let's Encrypt asks: "Prove you own `app.myproduct.com`"
5. Traefik creates a DNS TXT record via Cloudflare's API: `_acme-challenge.app.myproduct.com`
6. Let's Encrypt verifies the record and issues the wildcard certificate
7. Now any tenant subdomain works immediately:
   - `acme.app.myproduct.com` -- covered
   - `globex.app.myproduct.com` -- covered
   - `brand-new-tenant.app.myproduct.com` -- covered (no action needed!)
8. Traefik auto-renews the certificate before it expires (every ~60 days)

Without the wildcard, step 7 would require requesting a new certificate for each new tenant -- adding delay and complexity to tenant onboarding.

---

## Learn more

- [SSL/TLS](ssl-tls.md) -- The encryption technology certificates enable
- [Subdomain](subdomain.md) -- What wildcard certificates cover
- [Domain](domain.md) -- The base address certificates are tied to
- [Reverse Proxy](reverse-proxy.md) -- Where the certificate is configured
- [Network Isolation](network-isolation.md) -- Why internal services don't need certificates
