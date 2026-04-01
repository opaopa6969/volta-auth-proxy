# Subdomain

[日本語版はこちら](subdomain.ja.md)

---

## In one sentence?

A subdomain is a section added in front of a [domain](domain.md) -- like `app.example.com` -- used to organize different services or tenants under one main address.

---

## Apartments in the same building

Think of a [domain](domain.md) as a building, and subdomains as apartments inside it:

| Building (Domain) | Apartment (Subdomain) | What lives there |
|---|---|---|
| `example.com` | `www.example.com` | Marketing website |
| `example.com` | `app.example.com` | Web application |
| `example.com` | `auth.example.com` | Authentication service |
| `example.com` | `acme.app.example.com` | Tenant "ACME"'s instance |

- The building owner (domain owner) decides who gets apartments
- Each apartment can have its own lock ([SSL/TLS](ssl-tls.md) certificate)
- Mail ([cookies](cookie.md)) addressed to one apartment doesn't go to another
- You can have apartments within apartments: `acme.app.example.com` is a subdomain of `app.example.com`

---

## Why do we need this?

Without subdomains:

- Every service would need its own separate domain (expensive, hard to manage)
- Multi-tenant apps would need a different way to identify tenants (query parameters, paths -- all less clean)
- [Cookie](cookie.md) isolation between services on the same domain would be impossible
- You couldn't use a single [wildcard certificate](wildcard-certificate.md) to cover everything

Subdomains give you unlimited organization under one domain, with built-in browser security boundaries.

---

## Subdomain in volta-auth-proxy

Subdomains are central to volta's multi-tenant design:

```
  example.com                    (base domain)
  ├── auth.example.com           (volta-auth-proxy)
  ├── app.example.com            (your SaaS app)
  │   ├── acme.app.example.com   (tenant: ACME Corp)
  │   ├── globex.app.example.com (tenant: Globex Inc)
  │   └── initech.app.example.com(tenant: Initech)
  └── api.example.com            (API server)
```

**Tenant resolution by subdomain:**

When a request arrives at `acme.app.example.com`, volta extracts the first segment (`acme`) and looks it up in the tenants table as the `slug`. This determines which tenant's data and [RBAC](authentication-vs-authorization.md) rules apply.

**Cookie isolation:**

The session [cookie](cookie.md) is set on the auth subdomain (`auth.example.com`), not on `.example.com`. This means the cookie is NOT sent to `app.example.com` or `evil.example.com`. The [reverse proxy](reverse-proxy.md) / [ForwardAuth](forwardauth.md) pattern handles authentication without sharing cookies across subdomains.

---

## Concrete example

Setting up tenant subdomains in a volta deployment:

1. You own `myproduct.com`
2. DNS: `*.app.myproduct.com` points to your [server](server.md) (wildcard DNS record)
3. [SSL/TLS](ssl-tls.md): You get a [wildcard certificate](wildcard-certificate.md) for `*.app.myproduct.com`
4. volta config: `VOLTA_BASE_DOMAIN=myproduct.com`
5. A new tenant "Acme" signs up and picks slug `acme`
6. volta stores `slug: "acme"` in the tenants table
7. User visits `https://acme.app.myproduct.com`
8. [Reverse proxy](reverse-proxy.md) receives the request, asks volta via [ForwardAuth](forwardauth.md)
9. volta reads the `Host` [header](header.md): `acme.app.myproduct.com`
10. volta extracts `acme`, finds the tenant, checks the [session](session.md), and injects `X-Volta-Tenant-Slug: acme`

---

## Learn more

- [Domain](domain.md) -- The parent address that subdomains extend
- [Wildcard Certificate](wildcard-certificate.md) -- One SSL cert covering all subdomains
- [ForwardAuth](forwardauth.md) -- How volta authenticates requests across subdomains
- [Cookie](cookie.md) -- How cookies are scoped to specific subdomains
- [Routing](routing.md) -- How requests reach the right service based on subdomain
