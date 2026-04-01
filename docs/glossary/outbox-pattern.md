# Outbox Pattern

[日本語版はこちら](outbox-pattern.ja.md)

---

## What is it?

The outbox pattern is a reliability technique for sending messages (like [webhooks](webhook.md)) after a database operation. Instead of performing the database write AND sending the message in two separate steps (which can fail independently), you write both the business data and the outgoing message to the database in a single transaction. A separate background worker then reads pending messages from the "outbox" table and delivers them.

Think of it like a post office inside a bank. When you deposit money and need to send a receipt to someone, the bank does not hand you the receipt to mail yourself (you might forget). Instead, the bank writes your deposit AND drops the receipt into its internal outbox. A mail carrier checks the outbox periodically and delivers all pending receipts. Even if the mail carrier is sick today, the receipts are safe in the outbox and will be delivered tomorrow.

The outbox pattern solves the "dual write problem" -- the challenge of keeping a database and an external system (like a message queue or webhook endpoint) in sync.

---

## Why does it matter?

Without the outbox pattern, you face two risky approaches:

```
  Approach 1: Send first, then save (data loss risk)
  ┌────────────────────────────────────────────┐
  │  1. Send webhook: user.created        ✓    │
  │  2. Save user to database             ✗    │
  │     (DB fails! User not saved,             │
  │      but webhook already sent!)             │
  │     → Downstream thinks user exists         │
  │     → But user does NOT exist               │
  └────────────────────────────────────────────┘

  Approach 2: Save first, then send (message loss risk)
  ┌────────────────────────────────────────────┐
  │  1. Save user to database             ✓    │
  │  2. Send webhook: user.created        ✗    │
  │     (Network fails! User saved,            │
  │      but webhook NOT sent!)                 │
  │     → User exists in volta                  │
  │     → Downstream never learns about it      │
  └────────────────────────────────────────────┘

  Outbox pattern: Save both atomically, deliver later
  ┌────────────────────────────────────────────┐
  │  1. BEGIN TRANSACTION                      │
  │     a. Save user to database          ✓    │
  │     b. Save webhook event to outbox   ✓    │
  │  2. COMMIT                            ✓    │
  │     (Both succeed or both fail)            │
  │                                            │
  │  3. Outbox worker (async):                 │
  │     a. Read pending events from outbox     │
  │     b. Send webhook                        │
  │     c. Mark event as delivered             │
  │     d. If send fails → retry later         │
  └────────────────────────────────────────────┘
```

The outbox pattern guarantees that **if the business operation succeeds, the message will eventually be delivered** (at-least-once delivery).

---

## How does it work?

### The outbox table

```sql
CREATE TABLE webhook_outbox (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    event_type  VARCHAR(100) NOT NULL,   -- e.g., "user.created"
    payload     JSONB NOT NULL,          -- the webhook body
    target_url  VARCHAR(2048) NOT NULL,  -- where to POST
    status      VARCHAR(20) DEFAULT 'PENDING',
    attempts    INT DEFAULT 0,
    next_retry  TIMESTAMP,
    created_at  TIMESTAMP DEFAULT NOW(),
    delivered_at TIMESTAMP
);
```

### The write path

```
  Application thread:
  ┌──────────────────────────────────────────────┐
  │  BEGIN TRANSACTION                            │
  │                                               │
  │  INSERT INTO users (id, email, tenant_id)     │
  │  VALUES ('user-uuid', 'a@b.com', 'acme');    │
  │                                               │
  │  INSERT INTO webhook_outbox                   │
  │    (id, tenant_id, event_type, payload,       │
  │     target_url, status)                       │
  │  VALUES                                       │
  │    ('evt-uuid', 'acme', 'user.created',       │
  │     '{"user_id":"user-uuid","email":"a@b"}',  │
  │     'https://app.com/webhook', 'PENDING');    │
  │                                               │
  │  COMMIT                                       │
  └──────────────────────────────────────────────┘
```

### The outbox worker

```
  Background worker (runs every N seconds):
  ┌──────────────────────────────────────────────┐
  │                                               │
  │  1. SELECT * FROM webhook_outbox              │
  │     WHERE status = 'PENDING'                  │
  │     AND next_retry <= NOW()                   │
  │     ORDER BY created_at ASC                   │
  │     LIMIT 50                                  │
  │     FOR UPDATE SKIP LOCKED;                   │
  │                                               │
  │  2. For each event:                           │
  │     a. POST payload to target_url             │
  │        (with HMAC signature)                  │
  │                                               │
  │     b. If HTTP 2xx:                           │
  │        UPDATE status='DELIVERED',             │
  │               delivered_at=NOW()              │
  │                                               │
  │     c. If HTTP 4xx/5xx or timeout:            │
  │        UPDATE attempts=attempts+1,            │
  │               next_retry=NOW()+backoff(n)     │
  │                                               │
  │     d. If attempts > max_retries:             │
  │        UPDATE status='FAILED'                 │
  │        (alert ops team)                       │
  │                                               │
  └──────────────────────────────────────────────┘
```

### Exponential backoff

```
  Attempt 1: retry after 10 seconds
  Attempt 2: retry after 30 seconds
  Attempt 3: retry after 90 seconds
  Attempt 4: retry after 270 seconds (~4.5 min)
  Attempt 5: retry after 810 seconds (~13.5 min)
  ...
  Max: retry after 1 hour
```

### FOR UPDATE SKIP LOCKED

The `FOR UPDATE SKIP LOCKED` clause is critical for concurrent workers. If multiple outbox worker instances run simultaneously, each one picks up different pending events without blocking:

```
  Worker A: picks event 1, 2, 3 (locks them)
  Worker B: skips 1, 2, 3 → picks event 4, 5, 6
  Worker C: skips 1-6 → picks event 7, 8, 9
```

---

## How does volta-auth-proxy use it?

### volta's webhook outbox worker

volta implements the outbox pattern for all outbound [webhooks](webhook.md). The worker is a scheduled thread inside the volta process (no external message broker needed):

```
  volta-auth-proxy process:
  ┌────────────────────────────────────────────┐
  │                                            │
  │  ┌──────────────────┐                      │
  │  │  HTTP Handlers   │                      │
  │  │  (Javalin)       │  ← user requests     │
  │  │                  │                      │
  │  │  user.create()   │                      │
  │  │    → INSERT user │                      │
  │  │    → INSERT outbox│                     │
  │  └──────────────────┘                      │
  │                                            │
  │  ┌──────────────────┐                      │
  │  │  Outbox Worker   │  ← background thread │
  │  │  (ScheduledExec) │                      │
  │  │                  │                      │
  │  │  poll()          │                      │
  │  │    → SELECT outbox│                     │
  │  │    → POST webhook │                     │
  │  │    → UPDATE status│                     │
  │  └──────────────────┘                      │
  │                                            │
  └────────────────────────────────────────────┘
```

### Events that trigger outbox writes

| Operation | Outbox event | Webhook payload |
|-----------|-------------|-----------------|
| User joins tenant | `user.created` | user_id, email, roles |
| User removed | `user.deleted` | user_id, tenant_id |
| Role changed | `user.role_changed` | user_id, old_roles, new_roles |
| [Invitation](invitation-code.md) accepted | `invitation.accepted` | invitation_id, user_id |
| Tenant [suspended](suspension.md) | `tenant.suspended` | tenant_id, reason |
| [Billing](billing.md) plan changed | `billing.plan_changed` | tenant_id, old_plan, new_plan |
| [M2M](m2m.md) client created | `m2m.client_created` | client_id, scopes |

### Why volta chose outbox over message queues

| Feature | Message queue (Kafka, RabbitMQ) | Outbox pattern |
|---------|-------------------------------|----------------|
| Extra infrastructure | Yes (broker needed) | No (just the DB) |
| Atomic with DB write | Requires 2PC or saga | Yes (same transaction) |
| Ordering guarantee | Partition-based | created_at ordering |
| Operational complexity | High | Low |
| Suitable for volta's scale | Overkill | Right-sized |

volta prioritizes simplicity and self-containment. The outbox pattern needs only PostgreSQL -- no Kafka, no RabbitMQ, no extra moving parts.

---

## Common mistakes and attacks

### Mistake 1: Sending the webhook directly (no outbox)

If you send the webhook inside the HTTP handler and the send fails, you either lose the event or must add complex retry logic to the handler itself. The outbox decouples sending from handling.

### Mistake 2: Not using a database transaction

The outbox only works if the business write and the outbox insert are in the same transaction. If they are separate, you are back to the dual write problem.

### Mistake 3: Not handling duplicates on the receiver side

The outbox guarantees at-least-once delivery, not exactly-once. A receiver may get the same event twice if the worker crashes after sending but before marking the event as delivered. Receivers must be idempotent.

### Mistake 4: No dead letter handling

After max retries, failed events should be moved to a dead letter table and an alert should be raised. Silently dropping events defeats the purpose of the outbox.

### Attack: Outbox table manipulation

If an attacker gains database access, they could insert fake events into the outbox table, causing the worker to send forged webhooks. Defense: restrict direct DB access, validate outbox entries, and sign payloads with HMAC.

---

## Further reading

- [webhook.md](webhook.md) -- The webhooks that the outbox delivers.
- [retry.md](retry.md) -- The retry strategy used by the outbox worker.
- [ingestion.md](ingestion.md) -- Inbound webhook processing (the receiving side).
- [Microservices Patterns, Ch. 3](https://microservices.io/patterns/data/transactional-outbox.html) -- Chris Richardson's outbox pattern reference.
