# IDaaS (Identity as a Service)

[日本語版はこちら](idaas.ja.md)

---

## What is it?

IDaaS stands for Identity as a Service -- it is a cloud-hosted authentication and user management system that you pay someone else to run for you, instead of running it yourself.

Think of it like renting a fully managed apartment versus owning a house. With a managed apartment (IDaaS), the landlord handles plumbing, electrical, landscaping, and repairs. You just pay rent and live there. With a house you own (self-hosting), you control everything -- you can renovate however you want, nobody can raise your rent -- but the toilet breaks at midnight? That is your problem.

IDaaS is the "managed apartment" model for authentication.

---

## Why does it matter?

Authentication is hard to build correctly. There are security vulnerabilities everywhere: XSS, CSRF, session hijacking, token theft, brute force attacks, and more. Getting it wrong can expose your users' data.

IDaaS providers like [Auth0](auth0.md), Clerk, and Okta say: "Don't build auth yourself. We have a team of security experts who do this full-time. Just use our service."

This is a legitimate value proposition. The question is whether the trade-offs (cost, lock-in, limited control) are worth it for your situation.

---

## Popular IDaaS providers

| Provider | Known for | Pricing model |
|----------|-----------|---------------|
| **Auth0** (Okta) | Most popular. Excellent SDKs, quickstart guides. | Per [MAU](mau.md) |
| **Clerk** | Developer-friendly, beautiful pre-built UI components. | Per MAU |
| **Okta** | Enterprise-focused. SAML, SCIM, LDAP. | Per user (provisioned) |
| **Firebase Auth** | Part of Google Cloud. Simple, great for mobile. | Free to 50K MAU, then per verification |
| **AWS Cognito** | Part of AWS. Tight AWS integration. | Per MAU |
| **Supabase Auth** | Part of Supabase. PostgreSQL-based. | Included in Supabase plan |

### What they all share

- **Cloud-hosted:** You do not run servers. They do.
- **API/SDK driven:** You integrate via their libraries or REST APIs.
- **Usage-based pricing:** You pay based on how many users you have.
- **Managed security:** They handle security patches, infrastructure, and uptime.

---

## IDaaS versus self-hosted

```
  IDaaS (Auth0, Clerk, Okta):
  ┌───────────────────────────────────────┐
  │  + Fast to start (minutes to hours)   │
  │  + Zero ops burden                    │
  │  + Security team maintains it         │
  │  - Cost grows with users (MAU)        │
  │  - Vendor lock-in                     │
  │  - Limited customization              │
  │  - Data lives on their servers        │
  └───────────────────────────────────────┘

  Self-hosted (volta, Keycloak):
  ┌───────────────────────────────────────┐
  │  + $0 regardless of user count        │
  │  + Full control over everything       │
  │  + Data stays on your servers         │
  │  + No vendor lock-in                  │
  │  - More setup work upfront            │
  │  - You handle ops and security        │
  │  - Need someone who understands auth  │
  └───────────────────────────────────────┘
```

### Why some teams prefer IDaaS

- **Small teams:** A 2-person startup does not want to manage auth infrastructure. They want to ship features.
- **Speed to market:** IDaaS lets you add login in hours, not days. For an MVP, this matters.
- **Compliance checkboxes:** Some enterprise customers want to hear "we use Auth0/Okta" because those names have SOC 2 certifications.
- **Broad IdP support:** IDaaS providers often support 20+ social login providers and enterprise SAML connections out of the box.

### Why some teams prefer self-hosted

- **Cost at scale:** Once you pass 10,000+ MAU, the savings from self-hosting become significant.
- **Control:** You can customize every aspect of the login experience, session behavior, and user data model.
- **Privacy/compliance:** Some regulations require user data to stay in specific jurisdictions.
- **Independence:** No risk of the provider raising prices, changing terms, or shutting down.

---

## How volta fits in

volta-auth-proxy is not an IDaaS. It is the opposite: a self-hosted auth gateway that you run on your own infrastructure.

volta was built by people who considered IDaaS options and decided the trade-offs were not acceptable for a multi-tenant SaaS:

| IDaaS trade-off | volta's answer |
|-----------------|---------------|
| MAU pricing | $0 at any scale |
| Vendor lock-in | Standard HTTP headers, your PostgreSQL |
| Limited UI control | Full [jte](jte.md) templates |
| Data on their servers | Data in your Postgres |
| Dependency on their uptime | Your uptime, your responsibility |

---

## Further reading

- [auth0.md](auth0.md) -- The most popular IDaaS provider, analyzed in detail.
- [self-hosting.md](self-hosting.md) -- The alternative to IDaaS.
- [vendor-lock-in.md](vendor-lock-in.md) -- The biggest risk of IDaaS.
- [mau.md](mau.md) -- The billing metric that makes IDaaS expensive.
- [iam.md](iam.md) -- The umbrella term that includes both IDaaS and self-hosted solutions.
