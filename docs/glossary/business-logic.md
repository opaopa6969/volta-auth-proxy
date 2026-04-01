# Business Logic

[日本語版はこちら](business-logic.ja.md)

---

## What is it?

Business logic is the set of rules and operations that are specific to YOUR business -- the things that make your application unique. It is not infrastructure (databases, networks, servers) and it is not generic functionality (user login, file storage). It is the "what makes you, you" of your software.

Think of it like a restaurant. The kitchen equipment (ovens, fridges, sinks) is infrastructure -- every restaurant needs them. Taking reservations and seating guests is generic functionality -- most restaurants do it the same way. But the recipes? The secret sauce? The decision to serve only organic ingredients sourced from local farms? That is business logic. It is what makes THIS restaurant different from the one next door.

---

## Examples of business logic vs infrastructure

```
Infrastructure (generic, every app needs it):
  - Store data in a database
  - Send HTTP responses
  - Handle network connections
  - Manage memory

Generic functionality (common, but not every app needs all of it):
  - User authentication (login/logout)
  - File uploads
  - Email sending
  - Payment processing

Business logic (specific to YOUR app):
  - "Free tier users can create up to 3 projects"
  - "Premium users get 50GB storage"
  - "If an invoice is overdue by 30 days, suspend the account"
  - "Managers can approve expenses up to $5,000; above that needs VP approval"
  - "Show different pricing for users in Japan vs the US"
```

Business logic is the reason your application exists. Without it, you just have a generic empty shell.

---

## Why an auth proxy should NOT contain business logic

This is where things get interesting. An auth proxy answers one question: "Is this person who they say they are, and are they allowed to access this resource?" That is it.

An auth proxy should NOT answer questions like:
- "Has this user exceeded their free tier limit?"
- "Is this user's subscription active?"
- "Should this user see the Japanese or English version?"
- "Can this manager approve this specific expense?"

These are YOUR business rules. They belong in YOUR application, not in the auth layer.

### Why this separation matters

```
Auth proxy with business logic (BAD):
┌──────────────────────────────────────┐
│ Auth Proxy                            │
│                                      │
│ - Is the user logged in?             │ ← Auth (correct)
│ - What is the user's role?           │ ← Auth (correct)
│ - Is their subscription active?      │ ← Business logic (WRONG)
│ - Have they exceeded the API limit?  │ ← Business logic (WRONG)
│ - What tier are they on?             │ ← Business logic (WRONG)
└──────────────────────────────────────┘

Problems:
  - Auth proxy needs to know about subscriptions, tiers, API limits
  - Every business rule change requires redeploying the auth proxy
  - Auth proxy becomes a monolith of everyone's business rules
  - Different apps have different rules, all crammed into one proxy

Auth proxy without business logic (GOOD / volta):
┌──────────────────────────────────────┐
│ volta-auth-proxy                      │
│                                      │
│ - Is the user logged in?             │ ← Auth
│ - What is the user's role?           │ ← Auth
│ - Which tenant are they in?          │ ← Auth
│ - Return identity headers            │ ← Auth
└──────────────────────────────────────┘
                │
                │ X-Volta-User-Id, X-Volta-Roles, X-Volta-Tenant-Id
                ▼
┌──────────────────────────────────────┐
│ Your App                              │
│                                      │
│ - Is their subscription active?      │ ← Your business logic
│ - Have they exceeded their limit?    │ ← Your business logic
│ - What tier are they on?             │ ← Your business logic
└──────────────────────────────────────┘
```

volta tells your app WHO the user is. Your app decides WHAT the user can do.

---

## volta's discipline

volta deliberately limits itself to identity concerns:

| volta does (auth) | volta does NOT do (business logic) |
|------|------|
| Authenticate the user (OIDC login) | Check subscription status |
| Identify the user (user ID, email) | Enforce usage quotas |
| Identify the tenant (tenant ID, slug) | Apply pricing rules |
| Determine the role (OWNER, ADMIN, MEMBER, VIEWER) | Evaluate business permissions |
| Issue identity tokens (JWT) | Make content decisions |
| Enforce app-level role access (allowed_roles) | Handle billing states |

The `allowed_roles` in volta-config.yaml is the closest volta gets to business logic -- and even that is deliberately simple: "can this role access this app, yes or no." Fine-grained permissions within the app are the app's responsibility.

This discipline means volta stays small, focused, and stable. It does not need to change when your business rules change. It does not need to know about your pricing tiers, your subscription model, or your approval workflows. It just tells your app: "This is Taro, an ADMIN of the ACME tenant. Do what you will."

---

## In volta-auth-proxy

volta strictly limits itself to identity and access -- who the user is, which tenant they belong to, and what role they have -- leaving all application-specific business logic to the downstream apps that receive volta's identity headers.

---

## Further reading

- [forwardauth.md](forwardauth.md) -- How volta passes identity to apps without business logic.
- [downstream-app.md](downstream-app.md) -- Where business logic belongs.
- [rbac.md](rbac.md) -- volta's role model (the boundary of what volta decides).
- [interface-extension-point.md](interface-extension-point.md) -- How volta stays extensible without absorbing business logic.
