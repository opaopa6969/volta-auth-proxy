# Webhook

[日本語版はこちら](webhook.ja.md)

---

## What is it?

A webhook is a server-to-server notification mechanism. When something important happens in one system, it sends an HTTP POST request to a URL registered by another system. Instead of the second system constantly asking "has anything changed?", the first system simply says "hey, something just happened -- here are the details."

Think of it like a doorbell. Without a doorbell, you would have to keep opening the door every few minutes to check if anyone is there (polling). With a doorbell, the visitor presses the button and you are notified immediately. A webhook is the internet's doorbell -- one system rings another system's URL when there is news.

Webhooks are the backbone of event-driven integration. Payment processors like Stripe use them to notify you of charges. Identity providers use them to notify you of user changes. volta-auth-proxy uses them to notify your application of authentication events.

---

## Why does it matter?

Without webhooks, systems must poll each other constantly:

```
  Polling (wasteful):
  ┌────────┐   "Any new events?"    ┌────────────────┐
  │  Your  │ ─────────────────────► │ volta-auth-    │
  │  App   │ ◄───────────────────── │ proxy          │
  │        │   "Nope."              │                │
  │        │                        │                │
  │        │   "Any new events?"    │                │
  │        │ ─────────────────────► │                │
  │        │ ◄───────────────────── │                │
  │        │   "Nope."              │                │
  │        │                        │                │
  │        │   "Any new events?"    │                │
  │        │ ─────────────────────► │                │
  │        │ ◄───────────────────── │                │
  │        │   "Yes! User signed up"│                │
  └────────┘                        └────────────────┘

  Webhook (efficient):
  ┌────────┐                        ┌────────────────┐
  │  Your  │                        │ volta-auth-    │
  │  App   │                        │ proxy          │
  │        │   (silence...)         │                │
  │        │                        │ (user signs up)│
  │        │   POST /your/webhook   │                │
  │        │ ◄───────────────────── │                │
  │        │   "User signed up!"    │                │
  └────────┘                        └────────────────┘
```

Key benefits:

- **Real-time**: Your app learns about events instantly, not on a polling interval
- **Efficient**: No wasted requests when nothing has changed
- **Decoupled**: volta does not need to know your app's internal logic -- it just sends the event
- **Scalable**: Works the same whether you have 1 event per day or 10,000

---

## How does it work?

### The basic flow

```
  1. REGISTRATION
     Your app tells volta: "Send events to https://myapp.com/webhooks/volta"
     volta stores this URL + a shared HMAC secret.

  2. EVENT OCCURS
     A user signs up, a role changes, a tenant is suspended, etc.

  3. NOTIFICATION
     volta sends an HTTP POST to your registered URL:

     POST https://myapp.com/webhooks/volta
     Content-Type: application/json
     X-Volta-Signature: sha256=a1b2c3d4e5f6...
     X-Volta-Event: user.created
     X-Volta-Delivery: 550e8400-e29b-41d4-a716-446655440000

     {
       "event": "user.created",
       "timestamp": "2026-04-01T12:00:00Z",
       "tenant_id": "acme-uuid",
       "data": {
         "user_id": "new-user-uuid",
         "email": "alice@example.com",
         "roles": ["MEMBER"]
       }
     }

  4. ACKNOWLEDGMENT
     Your app returns HTTP 200 to confirm receipt.
     If your app returns 4xx/5xx or times out, volta will retry.
```

### HMAC signature verification

Anyone could POST to your webhook URL pretending to be volta. The HMAC signature prevents this:

```
  volta side:
    secret  = "whsec_abc123..."  (shared secret)
    payload = '{"event":"user.created",...}'
    signature = HMAC-SHA256(secret, payload)
    Header: X-Volta-Signature: sha256=<signature>

  Your app side:
    1. Read the raw request body (do NOT parse JSON first)
    2. Compute HMAC-SHA256 using your copy of the shared secret
    3. Compare your signature with the header value
    4. If they match → the request is authentic
    5. If they don't → reject with 403
```

```java
// Example verification in Java
String header = request.header("X-Volta-Signature");
String expected = "sha256=" + hmacSha256(secret, rawBody);
if (!MessageDigest.isEqual(expected.getBytes(), header.getBytes())) {
    return response.status(403);
}
```

### Common webhook events in volta

| Event | Trigger |
|-------|---------|
| `user.created` | New user joins a [tenant](tenant.md) |
| `user.deleted` | User removed from tenant |
| `user.role_changed` | User's [role](role.md) updated |
| `tenant.suspended` | Tenant [suspended](suspension.md) |
| `tenant.reactivated` | Tenant reactivated |
| `invitation.accepted` | [Invitation code](invitation-code.md) used |
| `billing.plan_changed` | Stripe [billing](billing.md) plan updated |
| `m2m.client_created` | New [M2M](m2m.md) client registered |

---

## How does volta-auth-proxy use it?

### Outbound webhooks (volta notifies your app)

volta uses the [outbox pattern](outbox-pattern.md) to send webhooks reliably. When an event occurs, volta does NOT send the HTTP request immediately. Instead:

1. The event is written to the `webhook_outbox` table inside the same database transaction as the action itself
2. A background outbox worker picks up pending events and delivers them
3. If delivery fails, the worker [retries](retry.md) with exponential backoff

This guarantees that if the action is committed to the database, the webhook will eventually be delivered -- even if the network is down temporarily.

### Inbound webhooks (volta receives events)

volta also acts as a webhook receiver. It [ingests](ingestion.md) Stripe billing webhooks at a dedicated endpoint to handle plan changes, payment failures, and subscription updates. These inbound webhooks are verified using Stripe's signature scheme before processing.

### Webhook registration

Tenant admins register webhook endpoints via the volta API:

```
POST /api/v1/tenants/{tid}/webhooks
{
  "url": "https://myapp.com/webhooks/volta",
  "events": ["user.created", "user.deleted", "tenant.suspended"],
  "active": true
}

Response:
{
  "webhook_id": "wh-uuid",
  "secret": "whsec_abc123...",
  "url": "https://myapp.com/webhooks/volta",
  "events": ["user.created", "user.deleted", "tenant.suspended"]
}
```

The `secret` is shown only once at creation time. The admin stores it in their application to verify HMAC signatures.

---

## Common mistakes and attacks

### Mistake 1: Not verifying the HMAC signature

If you skip signature verification, anyone who discovers your webhook URL can send fake events. Always verify the `X-Volta-Signature` header.

### Mistake 2: Parsing JSON before verifying signature

The HMAC is computed over the raw bytes. If you parse the JSON first and then re-serialize it, whitespace or key ordering may differ, and the signature will not match. Always verify against the raw request body.

### Mistake 3: Assuming webhooks arrive exactly once

Networks are unreliable. volta may retry a delivery if your app responded slowly but actually processed the event. Design your webhook handler to be idempotent -- use the `X-Volta-Delivery` ID to detect duplicates.

### Mistake 4: Doing heavy work inside the webhook handler

Your handler should return 200 quickly (within 5 seconds). If you need to do heavy processing, enqueue the event and process it asynchronously. A slow response triggers retries and can cause duplicate processing.

### Attack: Replay attack

An attacker intercepts a legitimate webhook and replays it later. Defense: include a timestamp in the payload and reject events older than a threshold (e.g., 5 minutes).

### Attack: URL probing

An attacker registers a webhook URL pointing to an internal service (SSRF). Defense: volta validates webhook URLs against a [whitelist](whitelist.md) of allowed domains and blocks private IP ranges.

---

## Further reading

- [outbox-pattern.md](outbox-pattern.md) -- How volta ensures reliable webhook delivery.
- [retry.md](retry.md) -- Retry strategy for failed deliveries.
- [ingestion.md](ingestion.md) -- How volta receives inbound webhooks (e.g., Stripe).
- [m2m.md](m2m.md) -- Machine-to-machine communication that often uses webhooks.
- [billing.md](billing.md) -- Stripe billing webhooks that volta ingests.
