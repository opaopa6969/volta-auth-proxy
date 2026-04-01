# Stripe

[日本語版はこちら](stripe.ja.md)

---

## What is it?

Stripe is an online payment processing platform that lets businesses accept credit cards, manage subscriptions, and handle billing through a developer-friendly API. Founded in 2010, Stripe has become the de facto standard for payment processing in SaaS applications. If you have ever bought something online from a startup, there is a good chance Stripe processed the payment.

Think of Stripe like a bank teller who sits between your customer and your business. The customer hands the teller their credit card, the teller processes the payment, takes a small fee, and deposits the rest into your account. The teller also handles the paperwork -- receipts, refunds, fraud checks, tax reporting. You never have to touch the credit card yourself.

The key innovation of Stripe is its API design. Before Stripe, accepting payments online required filling out multi-page merchant account applications, dealing with legacy payment gateways, and writing fragile integration code. Stripe reduced this to a few lines of code and a clean REST API.

---

## Why does it matter?

For any SaaS product, billing is tightly coupled with authentication and authorization. When a customer upgrades their plan, their permissions change. When a subscription expires, access must be revoked. When a new team member is added to a paid plan, the billing needs to update.

This is where Stripe intersects with identity management. volta-auth-proxy needs to know a tenant's billing status to enforce access rules:

- Is this tenant on a free plan or paid plan?
- Have they exceeded their seat limit?
- Is their payment method valid, or has it failed?
- Did they just upgrade, and do they need new features unlocked immediately?

Without integrating billing and auth, you end up with users who have access they should not (expired subscriptions) or who are missing access they should have (just upgraded but features not yet enabled).

---

## How does it work?

### Core concepts

| Concept | Description |
|---------|-------------|
| **Customer** | A Stripe record representing a paying entity (usually maps to a volta tenant) |
| **Subscription** | A recurring payment plan (e.g., $49/month Pro plan) |
| **Product** | What you are selling (e.g., "volta Pro", "volta Enterprise") |
| **Price** | A specific pricing option for a product (e.g., $49/month, $490/year) |
| **Invoice** | A bill generated for a subscription period |
| **Payment Intent** | Represents a single payment attempt |
| **Webhook** | An HTTP callback Stripe sends to your server when something happens |

### Webhook-driven architecture

Stripe communicates changes to your application through **webhooks** -- HTTP POST requests sent to a URL you configure. This is an event-driven architecture:

```
  Stripe                          Your Server (volta)
  ======                          ===================

  Customer upgrades plan
       │
       ├──► Webhook: customer.subscription.updated
       │         { subscription: { plan: "pro", status: "active" } }
       │
       │                          Receive webhook
       │                          Verify signature
       │                          Update tenant plan in DB
       │                          Return 200 OK
       │
  Payment fails
       │
       ├──► Webhook: invoice.payment_failed
       │         { customer: "cus_xxx", attempt: 3 }
       │
       │                          Receive webhook
       │                          Mark tenant as "payment_failed"
       │                          Restrict access (grace period)
       │                          Send notification
```

### Common webhook events

| Event | When it fires | What volta should do |
|-------|--------------|---------------------|
| `customer.subscription.created` | New subscription starts | Create/upgrade tenant plan |
| `customer.subscription.updated` | Plan changes (upgrade/downgrade) | Update tenant features/limits |
| `customer.subscription.deleted` | Subscription cancelled | Downgrade to free plan |
| `invoice.payment_failed` | Payment attempt fails | Start grace period, notify |
| `invoice.paid` | Payment succeeds | Clear any payment failure flags |
| `customer.deleted` | Customer removed from Stripe | Handle tenant deactivation |

### Webhook signature verification

Stripe signs every webhook with a secret key. This prevents attackers from sending fake webhooks to your server:

```
Stripe-Signature: t=1614556800,
  v1=5257a869e7ecebeda32affa62cdca3fa51cad7e77a0e56ff536d0ce8e108d8bd
```

Your server must verify this signature before processing the event. Without verification, anyone who discovers your webhook URL could forge events like "subscription upgraded" and grant themselves premium access.

### Stripe vs alternatives

| Feature | Stripe | PayPal | Paddle | Lemon Squeezy |
|---------|--------|--------|--------|----------------|
| API quality | Excellent | Moderate | Good | Good |
| Subscription management | Built-in | Basic | Built-in | Built-in |
| Webhook system | Comprehensive | Limited | Good | Good |
| Merchant of Record | No (you handle tax) | No | Yes | Yes |
| Global availability | 46+ countries | 200+ countries | Global | Global |
| Developer experience | Industry benchmark | Legacy feel | Clean | Clean |

---

## How does volta-auth-proxy use it?

volta-auth-proxy ingests Stripe webhooks to synchronize billing state with authentication and authorization. This is not about processing payments -- volta does not handle credit cards. It is about knowing what each tenant is entitled to.

### Integration architecture

```
  Stripe Cloud
       │
       │  Webhooks (HTTPS POST)
       ▼
  ┌──────────────────┐
  │  volta-auth-proxy │
  │                   │
  │  /webhook/stripe  │  ← Receives events
  │       │           │
  │       ▼           │
  │  Verify signature │
  │  Update tenant DB │
  │  Adjust features  │
  │  Notify if needed │
  └──────────────────┘
```

### What volta does with Stripe data

1. **Plan mapping**: Stripe subscription products map to volta tenant plans (free, pro, enterprise). Each plan defines feature flags and limits.
2. **Seat counting**: Stripe subscription quantities map to volta's per-tenant user limits.
3. **Grace periods**: When a payment fails, volta does not immediately revoke access. It enters a grace period (configurable), showing warnings to the tenant admin.
4. **Feature gating**: Premium features (e.g., SAML SSO, audit logs, custom domains) are gated by the tenant's Stripe subscription plan.

### Why webhooks, not polling?

volta uses Stripe webhooks instead of polling the Stripe API because:

- **Real-time**: Changes are reflected within seconds, not on a polling interval
- **Efficient**: No wasted API calls checking for changes that have not happened
- **Reliable**: Stripe retries failed webhook deliveries for up to 72 hours
- **Stripe's recommendation**: Stripe explicitly recommends webhooks for subscription state management

---

## Common mistakes and attacks

### Mistake 1: Not verifying webhook signatures

If you process webhooks without verifying the `Stripe-Signature` header, anyone can POST fake events to your webhook URL. An attacker could forge a `customer.subscription.created` event and grant themselves a premium plan for free.

### Mistake 2: Not handling duplicate events

Stripe may send the same event multiple times (network retries). Your webhook handler must be **idempotent** -- processing the same event twice should produce the same result. Use the event ID to detect duplicates.

### Mistake 3: Returning errors from the webhook handler

If your webhook endpoint returns a non-2xx status, Stripe will retry the delivery. If your handler consistently fails, Stripe will disable the webhook endpoint entirely. Handle errors gracefully and return 200 even if you need to queue the event for later processing.

### Mistake 4: Trusting Stripe data without mapping

Never use Stripe's internal IDs directly in your authorization logic. Map Stripe customers to volta tenants, Stripe products to volta plans. If the mapping breaks, fail closed (restrict access) rather than fail open.

### Attack: Webhook URL discovery

If an attacker discovers your webhook URL, they can attempt to send crafted events. Signature verification prevents this, but also: use a non-guessable path (e.g., `/webhook/stripe/a7b3c9d2`) and consider IP allowlisting if Stripe publishes webhook source IPs.

---

## Further reading

- [Stripe documentation](https://stripe.com/docs) -- The complete reference.
- [Stripe webhooks guide](https://stripe.com/docs/webhooks) -- How to receive and handle events.
- [Stripe webhook signature verification](https://stripe.com/docs/webhooks/signatures) -- Verifying event authenticity.
- [oauth2.md](oauth2.md) -- How authentication relates to billing.
- [sso.md](sso.md) -- SSO as a premium feature gated by Stripe plan.
