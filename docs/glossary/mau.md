# MAU (Monthly Active Users)

[日本語版はこちら](mau.ja.md)

---

## What is it?

MAU stands for Monthly Active Users -- it is the number of unique people who actually use your application at least once during a calendar month.

Think of it like a gym membership count. A gym might have 5,000 members who signed up, but only 1,200 actually swiped their card and came in this month. Those 1,200 are the "monthly active users." The gym could charge you rent based on total members (5,000) or actual visitors (1,200). In the SaaS world, most auth providers charge you based on MAU -- the people who actually showed up.

---

## Why does it matter?

MAU is the most common billing metric for cloud-hosted authentication services like Auth0, Clerk, and Okta. The more users who log in during a month, the more you pay.

Here is the problem: MAU-based pricing feels cheap at first, but it scales in a way that surprises you.

### The cost curve

| MAU | Auth0 (approximate) | Clerk (approximate) | volta-auth-proxy |
|-----|---------------------|---------------------|------------------|
| 1,000 | Free tier | Free tier | $0 |
| 10,000 | ~$230/mo | ~$250/mo | $0 |
| 50,000 | ~$1,150/mo | ~$1,000/mo | $0 |
| 100,000 | ~$2,400/mo | ~$2,000/mo | $0 |
| 500,000 | Custom ($$$$) | Custom ($$$$) | $0 |

When your SaaS starts growing, that monthly auth bill can become one of your biggest infrastructure costs -- more than your database, more than your servers. And you cannot easily switch providers because of [vendor lock-in](vendor-lock-in.md).

### How MAU is counted

Different services count MAU differently, which makes comparison tricky:

- **Auth0:** A user counts as "active" if they log in or if their token is refreshed. Even a silent token refresh (happening in the background) counts.
- **Clerk:** Counts any user who authenticates at least once in the billing period.
- **Okta:** Counts users who are provisioned (exist in the system), not just active ones -- which is even worse.

The key takeaway: you often have less control over your MAU number than you think. Background token refreshes, automated systems, and test accounts all count.

---

## How does volta-auth-proxy handle this?

volta-auth-proxy is [self-hosted](self-hosting.md). You run it on your own servers. There is no per-user billing, no MAU metering, and no usage tracking phone-home.

Whether you have 100 users or 1,000,000 users, the cost of running volta is just the cost of your own infrastructure (a small PostgreSQL database and a lightweight Java process that uses ~30MB of RAM).

```
  Auth0 pricing:
  ┌──────────────────────────────────────┐
  │  $0.02 per MAU ... times 100,000    │
  │  = $2,000/month for auth alone      │
  └──────────────────────────────────────┘

  volta-auth-proxy pricing:
  ┌──────────────────────────────────────┐
  │  One small VM + Postgres             │
  │  = ~$20-50/month total               │
  │  (same price at 100 or 1,000,000    │
  │   users)                             │
  └──────────────────────────────────────┘
```

This is one of the core reasons volta exists. When your SaaS grows, auth should not become your most expensive line item.

---

## Real-world example

Imagine you are building a project management SaaS. In year one, you have 2,000 MAU. Auth0's free tier covers you. Life is great.

In year two, you grow to 30,000 MAU. Your Auth0 bill is now ~$700/month. Not ideal, but manageable.

In year three, you have 150,000 MAU. Auth0 is now $3,000+/month. That is $36,000/year just for login functionality. You want to switch, but your entire codebase uses Auth0's SDK, Auth0's user database, Auth0's webhook formats. Switching would take months of engineering work.

This is the MAU trap. volta avoids it entirely by being self-hosted and free regardless of user count.

---

## Further reading

- [vendor-lock-in.md](vendor-lock-in.md) -- Why MAU pricing plus lock-in is a dangerous combination.
- [auth0.md](auth0.md) -- Auth0's pricing model in detail.
- [self-hosting.md](self-hosting.md) -- Why volta chose self-hosting over cloud-hosted pricing.
- [idaas.md](idaas.md) -- The cloud-hosted auth model and its trade-offs.
