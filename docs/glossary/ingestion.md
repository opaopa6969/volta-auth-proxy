# Ingestion

[日本語版はこちら](ingestion.ja.md)

---

## What is it?

Ingestion is the process of receiving, validating, and processing incoming data from an external source. In the context of volta-auth-proxy, ingestion primarily refers to receiving inbound [webhooks](webhook.md) -- for example, Stripe sending billing events to volta. The receiving system must verify the data is authentic, parse it, and act on it.

Think of it like a mailroom in a large office building. Letters and packages arrive from many senders. The mailroom does not just dump everything on employees' desks. It checks return addresses, scans for suspicious items, sorts by department, and then delivers to the right person. Ingestion in software works the same way: data arrives from outside, gets validated, sorted, and routed to the correct handler.

Ingestion is the opposite of emission. When volta sends [webhooks](webhook.md) to your app (via the [outbox pattern](outbox-pattern.md)), that is emission. When volta receives webhooks from Stripe, that is ingestion.

---

## Why does it matter?

Inbound data from external systems cannot be blindly trusted:

```
  External source              volta-auth-proxy
  ===============              ================

  Stripe sends:                volta must:
  "Plan upgraded               1. Is this really from Stripe?
   for tenant X"                  (verify signature)
                               2. Is the data well-formed?
                                  (validate schema)
                               3. Have I seen this before?
                                  (deduplicate)
                               4. What should I do?
                                  (route to handler)
                               5. Did processing succeed?
                                  (acknowledge or retry)
```

Without proper ingestion:

- **Fake events**: An attacker could send forged billing events to downgrade or upgrade tenants
- **Duplicate processing**: A retried webhook could charge a customer twice
- **Data corruption**: Malformed payloads could crash your application
- **Missing events**: Unacknowledged webhooks stop being sent by the provider

---

## How does it work?

### The ingestion pipeline

```
  ┌─────────────────────────────────────────────────────┐
  │              Ingestion Pipeline                       │
  │                                                       │
  │  1. RECEIVE        2. VERIFY        3. PARSE          │
  │  ┌────────────┐   ┌────────────┐   ┌────────────┐    │
  │  │ Accept     │──►│ Check HMAC │──►│ Deserialize│    │
  │  │ HTTP POST  │   │ signature  │   │ JSON body  │    │
  │  │ Raw bytes  │   │ Timestamp  │   │ Validate   │    │
  │  └────────────┘   └────────────┘   └────────────┘    │
  │                                         │             │
  │  4. DEDUPLICATE    5. ROUTE        6. ACKNOWLEDGE     │
  │  ┌────────────┐   ┌────────────┐   ┌────────────┐    │
  │  │ Check event│──►│ Dispatch   │──►│ Return     │    │
  │  │ ID against │   │ to correct │   │ HTTP 200   │    │
  │  │ seen set   │   │ handler    │   │ to sender  │    │
  │  └────────────┘   └────────────┘   └────────────┘    │
  └─────────────────────────────────────────────────────┘
```

### Step 1: Receive

Accept the raw HTTP request and store the body as bytes before any processing:

```java
// Save raw body for signature verification
byte[] rawBody = ctx.bodyAsBytes();
String signatureHeader = ctx.header("Stripe-Signature");
```

### Step 2: Verify authenticity

Each provider has its own signature scheme. Stripe uses HMAC-SHA256 with a timestamp:

```
  Stripe-Signature: t=1711900000,v1=abc123def456...

  Verification:
    1. Extract timestamp (t) and signature (v1)
    2. Build signed payload: timestamp + "." + rawBody
    3. Compute HMAC-SHA256 with webhook secret
    4. Compare computed signature with v1
    5. Check timestamp is within tolerance (5 minutes)
```

### Step 3: Parse and validate

```json
{
  "id": "evt_1234567890",
  "type": "customer.subscription.updated",
  "data": {
    "object": {
      "customer": "cus_acme",
      "status": "active",
      "items": {
        "data": [{"price": {"id": "price_pro_monthly"}}]
      }
    }
  }
}
```

Validate that required fields exist and have expected types before processing.

### Step 4: Deduplicate

Stripe (and other providers) may retry events. Use the event `id` to detect duplicates:

```
  Event "evt_1234567890" arrives
    → Check: have we processed this ID before?
    → YES: Return 200 (acknowledge) but skip processing
    → NO:  Process the event, store the ID
```

### Step 5: Route to handler

```java
switch (event.getType()) {
    case "customer.subscription.updated":
        handlePlanChange(event);
        break;
    case "customer.subscription.deleted":
        handleCancellation(event);
        break;
    case "invoice.payment_failed":
        handlePaymentFailure(event);
        break;
    default:
        log.info("Ignoring unhandled event: {}", event.getType());
}
```

---

## How does volta-auth-proxy use it?

### Stripe billing webhook ingestion

volta ingests Stripe webhooks at a dedicated endpoint to manage [billing](billing.md) state:

```
  Stripe                    volta-auth-proxy
  ======                    ================

  subscription.updated ────► POST /webhooks/stripe
                              │
                              ├─ Verify Stripe signature
                              ├─ Parse event
                              ├─ Deduplicate by event ID
                              ├─ Route by event type:
                              │
                              │  subscription.updated
                              │  → Update tenant plan
                              │  → Fire billing.plan_changed webhook
                              │
                              │  subscription.deleted
                              │  → Suspend tenant
                              │  → Fire tenant.suspended webhook
                              │
                              │  invoice.payment_failed
                              │  → Flag tenant for grace period
                              │  → Notify tenant admins
                              │
                              └─ Return 200 to Stripe
```

### Stripe events volta handles

| Stripe event | volta action |
|-------------|-------------|
| `customer.subscription.created` | Activate [tenant](tenant.md) plan |
| `customer.subscription.updated` | Update plan tier, fire [webhook](webhook.md) |
| `customer.subscription.deleted` | [Suspend](suspension.md) tenant, fire webhook |
| `invoice.payment_failed` | Start grace period, notify admins |
| `invoice.paid` | Clear payment failure flags |
| `customer.deleted` | Suspend tenant, notify |

### Ingestion endpoint configuration

```yaml
# volta-config.yaml
billing:
  provider: stripe
  webhook_endpoint: /webhooks/stripe
  webhook_secret: ${STRIPE_WEBHOOK_SECRET}
  event_tolerance_seconds: 300
  handled_events:
    - customer.subscription.created
    - customer.subscription.updated
    - customer.subscription.deleted
    - invoice.payment_failed
    - invoice.paid
```

### Ingestion + outbox integration

When volta ingests a Stripe event and updates a tenant's plan, it also writes to the [outbox](outbox-pattern.md) to notify your app:

```
  Stripe → volta (ingestion) → DB update + outbox write
                                         ↓
                              Outbox worker → your app (webhook)

  Example:
    Stripe says: "Acme upgraded to Pro"
    volta:
      1. Updates acme tenant: plan = "pro"
      2. Writes to outbox: billing.plan_changed
    Outbox worker:
      3. POST to your-app.com/webhooks/volta
         {"event": "billing.plan_changed",
          "tenant_id": "acme-uuid",
          "new_plan": "pro"}
```

---

## Common mistakes and attacks

### Mistake 1: Not verifying webhook signatures

If you skip signature verification on inbound webhooks, an attacker can send fake events. Always verify signatures before processing.

### Mistake 2: Processing before acknowledging

Some implementations do heavy processing before returning 200. If processing is slow, the sender times out and retries, causing duplicate processing. Return 200 quickly, then process asynchronously.

### Mistake 3: No idempotency

Without deduplication, retried events cause duplicate side effects (e.g., sending the "plan upgraded" email twice). Always check the event ID before processing.

### Mistake 4: Hardcoding event handling

If you add a `default: throw` in your event router, new unhandled event types will crash your ingestion endpoint. Unknown events should be logged and ignored.

### Attack: Webhook forgery

An attacker discovers your ingestion endpoint and sends fake Stripe events to manipulate tenant plans. Defense: always verify Stripe signatures, restrict the endpoint to Stripe's IP ranges if possible.

### Attack: Replay attack

An attacker captures a legitimate webhook and replays it. Defense: check the event timestamp (Stripe includes it in the signature). Reject events older than 5 minutes.

---

## Further reading

- [webhook.md](webhook.md) -- Outbound webhooks (the sending side).
- [billing.md](billing.md) -- Stripe billing integration details.
- [outbox-pattern.md](outbox-pattern.md) -- How ingested events trigger outbound webhooks.
- [retry.md](retry.md) -- How providers retry failed webhook deliveries.
- [payload.md](payload.md) -- The data content of ingested events.
