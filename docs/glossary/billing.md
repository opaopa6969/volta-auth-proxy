# Billing

[日本語版はこちら](billing.ja.md)

---

## What is it?

Billing is the process of charging customers for their use of a service. In a SaaS context, billing typically involves subscription plans (free, pro, enterprise), payment processing, invoice generation, and handling payment failures. volta-auth-proxy does not process payments directly -- it integrates with Stripe via [webhooks](webhook.md) to stay informed about each [tenant](tenant.md)'s billing status.

Think of it like the relationship between a gym and a payment company. The gym (your app) provides the workout equipment and trainers. The payment company (Stripe) handles membership fees, card processing, and dunning. The gym's front desk system (volta) checks your membership status when you walk in. If your payment failed, the front desk tells you to update your card before letting you in. The gym does not process credit cards itself.

volta's role in billing is reactive: it [ingests](ingestion.md) Stripe events and takes action (activate plans, [suspend](suspension.md) tenants, fire [webhooks](webhook.md)) based on billing state changes.

---

## Why does it matter?

Without billing integration in your auth layer, you face dangerous gaps:

```
  Without billing-aware auth:
  ┌──────────────────────────────────────────────┐
  │  Stripe: "Acme's subscription cancelled"      │
  │                                               │
  │  Your app: knows nothing.                     │
  │  All 50 Acme employees still have full access.│
  │  You're providing free service.               │
  │                                               │
  │  Months later: "Why is revenue down?"         │
  └──────────────────────────────────────────────┘

  With billing-aware auth (volta):
  ┌──────────────────────────────────────────────┐
  │  Stripe: "Acme's subscription cancelled"      │
  │                                               │
  │  volta: Suspends Acme tenant.                 │
  │  All 50 employees see: "Please renew."        │
  │  volta fires webhook to your app.             │
  │  Your app can offer a grace period.           │
  └──────────────────────────────────────────────┘
```

Billing-aware authentication ensures that access matches payment status.

---

## How does it work?

### Stripe webhook integration

Stripe sends events to volta when billing state changes:

```
  Stripe                    volta-auth-proxy            Your App
  ======                    ================            ========

  Customer signs up
  and picks "Pro" plan
  ─────────────────────────►
  subscription.created       1. Map Stripe customer
                               to volta tenant
                            2. Set tenant plan = "pro"
                            3. Write outbox:
                               billing.plan_changed
                            ──────────────────────────►
                                                       webhook:
                                                       billing.plan_changed
                                                       "Acme is now Pro"

  3 months later...
  Payment fails
  ─────────────────────────►
  invoice.payment_failed     1. Flag tenant:
                               payment_failed = true
                            2. Set grace period (3 days)
                            3. Notify tenant admins
                            ──────────────────────────►
                                                       webhook:
                                                       billing.payment_failed
                                                       "Acme payment failed"

  Grace period expires,
  no payment received
  ─────────────────────────►
  subscription.deleted       1. Suspend tenant
                            2. Revoke all sessions
                            3. Write outbox:
                               tenant.suspended
                            ──────────────────────────►
                                                       webhook:
                                                       tenant.suspended
                                                       "Acme suspended"
```

### Billing states in volta

```
  ┌──────────┐     ┌──────────────┐     ┌──────────────┐
  │  ACTIVE  │────►│ GRACE_PERIOD │────►│  SUSPENDED   │
  │          │     │              │     │              │
  │ Plan     │     │ Payment      │     │ No payment   │
  │ active,  │     │ failed,      │     │ received,    │
  │ all good │     │ 3-day grace  │     │ access       │
  │          │     │              │     │ blocked      │
  └──────────┘     └──────────────┘     └──────────────┘
       ▲                  │                    │
       │                  │                    │
       └──────────────────┴────────────────────┘
                payment received
               (subscription.updated)
```

### Plan-based features

volta can enforce plan-based access controls:

```
  Tenant: Acme Corp
  Plan: "pro"

  Plan limits:
  ┌────────────────────────────────────────────┐
  │  Plan    │ Members │ M2M Clients │ Webhooks│
  │──────────┼─────────┼─────────────┼─────────│
  │  free    │    5    │      1      │    2    │
  │  pro     │   50    │     10      │   20    │
  │  enterprise│ unlimited│ unlimited │ unlimited│
  └────────────────────────────────────────────┘
```

volta can check these limits during [provisioning](provisioning.md), [M2M](m2m.md) client creation, and [webhook](webhook.md) registration, returning 402 Payment Required when a limit is reached.

---

## How does volta-auth-proxy use it?

### Stripe webhook ingestion

volta receives Stripe webhooks at `/webhooks/stripe`:

```yaml
# volta-config.yaml
billing:
  provider: stripe
  webhook_endpoint: /webhooks/stripe
  webhook_secret: ${STRIPE_WEBHOOK_SECRET}
  grace_period_days: 3
  plans:
    free:
      max_members: 5
      max_m2m_clients: 1
      max_webhooks: 2
    pro:
      max_members: 50
      max_m2m_clients: 10
      max_webhooks: 20
    enterprise:
      max_members: -1  # unlimited
      max_m2m_clients: -1
      max_webhooks: -1
```

### Stripe customer to tenant mapping

volta maps Stripe customers to tenants via a metadata field:

```
  Stripe Customer object:
  {
    "id": "cus_acme123",
    "metadata": {
      "volta_tenant_id": "acme-uuid"
    }
  }

  When volta receives a Stripe event:
    1. Extract customer ID from event
    2. Look up volta_tenant_id from customer metadata
    3. Apply billing action to that tenant
```

### Events volta handles

| Stripe event | volta action |
|-------------|-------------|
| `customer.subscription.created` | Activate plan, set limits |
| `customer.subscription.updated` | Update plan tier, fire [webhook](webhook.md) |
| `customer.subscription.deleted` | Start grace or [suspend](suspension.md) |
| `invoice.payment_failed` | Start grace period, notify admins |
| `invoice.paid` | Clear payment failure, restore access |
| `customer.deleted` | Suspend tenant, notify |

### Billing status in the auth flow

volta checks billing status during authentication:

```java
// In ForwardAuthHandler
Tenant tenant = tenantService.get(principal.tenantId());

if (tenant.isSuspended() &&
    "billing".equals(tenant.suspendedReason())) {
    ctx.status(402).json(Map.of(
        "error", "payment_required",
        "message", "Please update your payment method",
        "billing_portal_url", stripeBillingPortalUrl(tenant)
    ));
    return;
}
```

### Outbound billing webhooks

When billing state changes, volta fires webhooks to your app via the [outbox](outbox-pattern.md):

```json
{
  "event": "billing.plan_changed",
  "timestamp": "2026-04-01T12:00:00Z",
  "tenant_id": "acme-uuid",
  "data": {
    "old_plan": "free",
    "new_plan": "pro",
    "effective_at": "2026-04-01T12:00:00Z"
  }
}
```

---

## Common mistakes and attacks

### Mistake 1: Not implementing a grace period

Suspending immediately on the first payment failure is aggressive. Credit cards expire, banks flag legitimate charges, and auto-renewals fail for innocent reasons. A 3-day grace period with notifications is standard.

### Mistake 2: Trusting client-reported plan status

If your frontend checks the plan locally ("am I on Pro?"), an attacker can modify the check. Plan enforcement must happen server-side, in volta's auth layer.

### Mistake 3: No webhook signature verification

If you do not verify Stripe's webhook signature, an attacker can send fake billing events to downgrade or suspend tenants. Always verify with the Stripe webhook secret.

### Mistake 4: Deleting data on subscription cancellation

When a subscription is cancelled, suspend the tenant -- do not delete data. The customer might resubscribe, or they might need to export their data.

### Attack: Fake billing webhook

An attacker discovers volta's `/webhooks/stripe` endpoint and sends a forged `subscription.deleted` event to suspend a competitor's tenant. Defense: Stripe signature verification blocks all forged events.

### Attack: Plan limit bypass

An attacker tries to create more M2M clients than their plan allows by sending rapid concurrent requests. Defense: volta checks plan limits inside a database transaction with row locking to prevent race conditions.

---

## Further reading

- [ingestion.md](ingestion.md) -- How volta receives and processes Stripe webhooks.
- [suspension.md](suspension.md) -- Tenant suspension triggered by billing failures.
- [webhook.md](webhook.md) -- Billing events forwarded to your app.
- [outbox-pattern.md](outbox-pattern.md) -- Reliable delivery of billing webhooks.
- [tenant.md](tenant.md) -- The entity associated with a billing subscription.
- [Stripe Webhook Docs](https://stripe.com/docs/webhooks) -- Stripe's official webhook documentation.
