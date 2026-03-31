# Auth0

[日本語版はこちら](auth0.ja.md)

---

## What is it?

Auth0 is a cloud-hosted authentication and authorization service (Identity-as-a-Service) that lets developers add login, signup, and user management to their apps without building it from scratch.

Think of it like hiring a security company for your office building. They provide the guards, the ID badge system, the cameras, and the access control. You do not have to build any of that yourself. But you pay a monthly fee, the guards follow the security company's rules (not yours), and if you want to switch to a different security company, you have to rip out their entire system and replace it.

---

## Why does it matter?

Auth0 is one of the most popular [IDaaS](idaas.md) providers. It was founded in 2013 and acquired by Okta in 2021 for $6.5 billion. Thousands of companies use it, from startups to enterprises. If you are building a web app, someone will probably suggest "just use Auth0."

Understanding Auth0 helps you understand the trade-offs of cloud-hosted auth versus [self-hosting](self-hosting.md), and why volta-auth-proxy exists.

---

## What Auth0 does well

Let's be fair -- Auth0 became popular for good reasons:

| Strength | Detail |
|----------|--------|
| **Quick start** | Add login to a new app in under an hour. Their quickstart guides are excellent. |
| **SDKs for everything** | JavaScript, React, Next.js, iOS, Android, Flutter, Python, Go, Java, .NET -- they have SDKs for most platforms. |
| **Social logins** | Google, GitHub, Facebook, Apple -- toggle them on in the dashboard. |
| **Enterprise features** | SAML, Active Directory, LDAP connections for enterprise customers. |
| **Universal Login** | A hosted login page that handles all the auth flows. You redirect users there, they log in, and come back. |
| **Dashboard** | Web-based management UI. Create applications, configure rules, view logs. No command line needed. |

For a team of 2-3 developers building an MVP, Auth0 can save weeks of work. That is genuinely valuable.

---

## What's problematic

### 1. Cost at scale

Auth0 charges by [MAU](mau.md) (Monthly Active Users). The free tier covers ~7,500 MAU. After that:

```
  Auth0 cost growth:

  Year 1:  5,000 MAU   →  Free        ← "Auth0 is great!"
  Year 2:  25,000 MAU  →  ~$575/mo    ← "OK, that's a cost..."
  Year 3:  100,000 MAU →  ~$2,400/mo  ← "Wait, $28,800/year for login?!"
  Year 4:  500,000 MAU →  Custom      ← "$$$$ and you can't leave"
```

At scale, Auth0 can become one of your top-3 infrastructure costs -- and it is "just" the login system.

### 2. Vendor lock-in

See [vendor-lock-in.md](vendor-lock-in.md) for the full picture. Specific to Auth0:

- **User database:** You cannot export password hashes. If you leave Auth0, every user must reset their password.
- **Auth0 Actions:** Server-side JavaScript that runs during login flows. This code only runs inside Auth0. No portability.
- **Auth0 Organizations:** Their multi-tenancy feature. Deeply tied to their API. No equivalent elsewhere.
- **Auth0 SDKs:** Your frontend depends on `@auth0/auth0-spa-js`. Removing it means rewriting auth in every page.

### 3. Limited customization

Auth0's Universal Login can be customized, but within limits. You are working inside their UI framework. If you want pixel-perfect control over your login page, or login flows that do not match Auth0's mental model, you will hit walls.

### 4. Okta acquisition concerns

Since the Okta acquisition, some developers have reported:
- Price increases
- Feature deprecation
- Support quality changes
- Uncertainty about the product roadmap

---

## Why volta chose not to use Auth0

volta-auth-proxy exists because Auth0 (and similar services) do not meet certain requirements:

| Requirement | Auth0 | volta |
|-------------|-------|-------|
| Self-hostable | No | Yes (only) |
| $0 at 100k MAU | No (~$2,400/mo) | Yes |
| Full UI control | Limited | Full ([jte](jte.md) templates) |
| No SDK dependency | Auth0 SDK required | Standard HTTP headers |
| Data ownership | Auth0's cloud | Your PostgreSQL |
| Multi-tenant by design | Organizations (paid feature) | Core architecture |
| Startup time | N/A (cloud) | ~200ms |
| Memory footprint | N/A (cloud) | ~30MB |

volta is not "anti-Auth0." Auth0 is a good product for teams that want zero ops burden and have the budget. volta is for teams that want full control, predictable costs, and no lock-in.

---

## When Auth0 makes sense

Be honest about the trade-offs. Auth0 might be the right choice if:

- You are a small team building an MVP and need auth in a day
- You have no one who wants to manage infrastructure
- Your budget can absorb MAU-based pricing long-term
- You need enterprise SSO connections (SAML/LDAP) immediately and do not want to build them
- You are OK with the lock-in trade-off

---

## When volta makes more sense

volta is a better fit if:

- You are building a multi-tenant SaaS and want auth as part of your core architecture
- You expect to grow beyond 10,000+ MAU and want predictable costs
- You need full control over the login experience
- Data residency or compliance requires self-hosting
- You want to avoid vendor lock-in from day one

---

## Further reading

- [mau.md](mau.md) -- How MAU pricing works and why it gets expensive.
- [vendor-lock-in.md](vendor-lock-in.md) -- The full lock-in picture.
- [idaas.md](idaas.md) -- The cloud-hosted auth model Auth0 pioneered.
- [keycloak.md](keycloak.md) -- Another alternative to Auth0 (open-source, but heavy).
- [self-hosting.md](self-hosting.md) -- The model volta uses instead.
