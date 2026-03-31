# Vendor Lock-in

[日本語版はこちら](vendor-lock-in.ja.md)

---

## What is it?

Vendor lock-in is when you become so dependent on a specific company's product that switching to an alternative becomes extremely difficult, expensive, or practically impossible.

Think of it like an old mobile phone contract. You signed a 2-year deal with a carrier. You got a "free" phone. But then you realize: your phone number is tied to them, you owe a cancellation fee, your phone is locked to their network, and all your family is on the same plan for the group discount. Switching to another carrier means losing your number, paying fees, buying a new phone, and convincing your family to switch too. So you stay -- even when the service is bad and the price keeps going up.

Vendor lock-in in software works exactly the same way. You start using a service because it is easy. Over time, your code, your data, and your workflows become deeply intertwined with that service. Leaving gets harder every month.

---

## Why does it matter?

Lock-in is dangerous because it removes your negotiating power. Once a vendor knows you cannot easily leave, they can:

1. **Raise prices** -- and you have to accept (or spend months migrating)
2. **Change terms** -- new limits, new restrictions, take-it-or-leave-it
3. **Deprecate features** -- things you depend on disappear
4. **Have outages** -- your entire product goes down because their service goes down, and there is nothing you can do

### How Auth0 and Clerk lock you in

| Lock-in mechanism | How it works |
|-------------------|-------------|
| **Proprietary SDK** | Your frontend uses `@auth0/auth0-spa-js` or `@clerk/nextjs`. Every page, every component has their code woven in. |
| **User database** | All your user records live in Auth0/Clerk's cloud. Exporting them means losing password hashes (you cannot export bcrypt hashes from Auth0). |
| **Webhook formats** | You built automation around their specific event formats. A new provider has different event names and payloads. |
| **Custom rules/actions** | Auth0 "Actions" and Clerk "webhooks" use their proprietary APIs. None of this transfers. |
| **Session management** | Their session cookies, their token formats, their refresh flows. Switching means every user gets logged out. |
| **Login UI** | Auth0's Universal Login, Clerk's `<SignIn />` component. Your design is built around their components. |

The result: switching auth providers is typically a 2-6 month engineering project. Most teams never do it. They just keep paying.

---

## How does self-hosting avoid it?

When you self-host your authentication (like volta-auth-proxy), you own everything:

```
  Cloud-hosted (Auth0/Clerk):        Self-hosted (volta):
  ┌──────────────────────┐           ┌──────────────────────┐
  │  Their servers       │           │  Your servers         │
  │  Their database      │           │  Your database        │
  │  Their SDK           │           │  Standard headers     │
  │  Their rules engine  │           │  Your code            │
  │  Their login UI      │           │  Your templates       │
  │  Their pricing       │           │  Your pricing ($0)    │
  └──────────────────────┘           └──────────────────────┘
       You rent everything.              You own everything.
```

With volta:

- **Your user data** lives in your PostgreSQL database. You can query it, back it up, and migrate it with standard SQL.
- **No proprietary SDK** -- apps read standard HTTP headers (`X-Volta-User-Id`, `X-Volta-Tenant-Id`). Switching away from volta means writing a new auth service that sets the same headers.
- **Login UI** is [jte](jte.md) templates that you fully control. No "themed" components with hidden behavior.
- **Configuration** is a `.env` file and a simple `volta-config.yaml`. No cloud dashboard that only exists on someone else's servers.

---

## volta's approach

volta is self-host only, by design. There is no volta cloud service, no volta SaaS plan. This is intentional:

1. **You own the data.** User records, sessions, audit logs -- all in your Postgres.
2. **You own the code.** volta is open source. If the project disappears tomorrow, you still have a working auth system.
3. **You own the deployment.** Run it on AWS, GCP, a $5 VPS, or a Raspberry Pi. Nobody can raise your price.
4. **Standard integration.** volta uses [ForwardAuth](forwardauth.md) and standard HTTP headers. Your apps do not import any volta-specific SDK. The coupling is minimal.

The trade-off is real: you are responsible for ops (uptime, backups, security patches). But you are in control.

---

## How to evaluate lock-in risk

When choosing any tool or service, ask yourself:

1. **Can I export my data?** (All of it, including password hashes and sessions?)
2. **Can I run it myself?** (Is there a self-hosted option?)
3. **Does my code depend on their SDK?** (Or does it use standard protocols?)
4. **What happens if they double their prices?** (Can I switch in under a month?)
5. **What happens if they shut down?** (Do I lose everything?)

If you answer "no" to most of these, you are locked in.

---

## Further reading

- [mau.md](mau.md) -- How MAU-based pricing makes lock-in even more painful.
- [self-hosting.md](self-hosting.md) -- The self-hosting model and its trade-offs.
- [auth0.md](auth0.md) -- Auth0's specific lock-in mechanisms.
- [keycloak.md](keycloak.md) -- An open-source alternative that avoids cloud lock-in (but has its own complexity).
