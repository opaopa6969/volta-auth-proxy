# Retry

[日本語版はこちら](retry.ja.md)

---

## What is it?

A retry is the act of attempting a failed operation again, usually after a short delay. In distributed systems, transient failures are inevitable -- a server might be temporarily overloaded, a network connection might drop, or a database might be briefly unavailable. Instead of immediately giving up, the system waits a moment and tries again.

Think of it like calling a friend who does not pick up. You do not call once and declare the friendship over. You wait a minute, call again, maybe wait five minutes and try once more. If they still do not answer after several attempts, you leave a voicemail (dead letter). The key is that you space out your attempts and eventually give up -- you do not call 1,000 times in a row.

Retries are fundamental to reliable communication between services, especially for [webhook](webhook.md) delivery, [M2M](m2m.md) token refresh, and [outbox](outbox-pattern.md) event processing.

---

## Why does it matter?

In distributed systems, failure is the norm, not the exception:

```
  Without retries:
  ┌──────────┐     POST /webhook     ┌──────────┐
  │  volta   │ ─────────────────────►│ Your App │
  │          │                       │ (down!)  │
  │          │     Connection refused │          │
  │          │ ◄─────────────────────│          │
  │          │                       │          │
  │  "Oh well,│                      │          │
  │   event   │                      │          │
  │   lost."  │                      │          │
  └──────────┘                       └──────────┘

  With retries:
  ┌──────────┐     POST /webhook     ┌──────────┐
  │  volta   │ ─────────────────────►│ Your App │
  │          │     Connection refused │ (down!)  │
  │          │ ◄─────────────────────│          │
  │          │                       │          │
  │ (wait 10s)│                      │ (restart)│
  │          │     POST /webhook     │          │
  │          │ ─────────────────────►│          │
  │          │     200 OK            │ (up!)    │
  │          │ ◄─────────────────────│          │
  │          │                       │          │
  │ "Delivered│                      │"Got it!" │
  │  on 2nd   │                      │          │
  │  try."    │                      │          │
  └──────────┘                       └──────────┘
```

Without retries, a momentary network hiccup or service restart causes permanent data loss. With retries, the system is resilient to transient failures.

---

## How does it work?

### Retry strategies

#### 1. Fixed interval

```
  Attempt 1 → fail → wait 10s
  Attempt 2 → fail → wait 10s
  Attempt 3 → fail → wait 10s
  Attempt 4 → success!

  Problem: if the server is overloaded, all clients
  retry at the same interval → thundering herd
```

#### 2. Exponential backoff

```
  Attempt 1 → fail → wait 10s
  Attempt 2 → fail → wait 30s   (10 * 3)
  Attempt 3 → fail → wait 90s   (30 * 3)
  Attempt 4 → fail → wait 270s  (90 * 3)
  Attempt 5 → success!

  Better: gives the server progressively more
  time to recover
```

#### 3. Exponential backoff with jitter

```
  Attempt 1 → fail → wait 10s + random(0-5s)
  Attempt 2 → fail → wait 30s + random(0-15s)
  Attempt 3 → fail → wait 90s + random(0-45s)

  Best: prevents multiple clients from retrying
  at exactly the same moment (thundering herd)
```

### When to retry vs when not to

| HTTP status | Retry? | Reason |
|------------|--------|--------|
| 200-299 | No | Success! |
| 400 Bad Request | **No** | Your request is wrong. Retrying won't fix it. |
| 401 Unauthorized | **Once** | Token may have expired. Refresh and retry once. |
| 403 Forbidden | **No** | You lack permission. Retrying won't help. |
| 404 Not Found | **No** | Resource doesn't exist. |
| 408 Request Timeout | **Yes** | Server was busy. Try again. |
| 429 Too Many Requests | **Yes** | Rate limited. Respect `Retry-After` header. |
| 500 Internal Server Error | **Yes** | Server bug, might be transient. |
| 502 Bad Gateway | **Yes** | Upstream issue, likely transient. |
| 503 Service Unavailable | **Yes** | Server overloaded, try later. |
| Connection refused | **Yes** | Server might be restarting. |
| Timeout | **Yes** | Network issue, might be temporary. |

### The retry budget

A retry budget limits the total number of retries to prevent cascade failures:

```
  ┌─────────────────────────────────────────────┐
  │  Retry Budget: max 10% of total requests    │
  │                                              │
  │  If volta sends 1000 webhooks/min:           │
  │    Max retries allowed: 100/min              │
  │                                              │
  │  If 500 webhooks fail (server down):         │
  │    Only 100 will be retried immediately      │
  │    Remaining 400 queued for next cycle       │
  │                                              │
  │  This prevents volta from overwhelming       │
  │  an already struggling server                │
  └─────────────────────────────────────────────┘
```

---

## How does volta-auth-proxy use it?

### Webhook delivery retries (outbox worker)

The [outbox](outbox-pattern.md) worker uses exponential backoff to retry failed webhook deliveries:

```
  ┌────────────────────────────────────────────┐
  │  volta Webhook Retry Schedule               │
  │                                            │
  │  Attempt 1: immediate                      │
  │  Attempt 2: +10 seconds                    │
  │  Attempt 3: +30 seconds                    │
  │  Attempt 4: +90 seconds                    │
  │  Attempt 5: +270 seconds (~4.5 min)        │
  │  Attempt 6: +810 seconds (~13.5 min)       │
  │  Attempt 7: +2430 seconds (~40 min)        │
  │  Attempt 8: +3600 seconds (1 hour, max)    │
  │                                            │
  │  Max attempts: 10                          │
  │  After max: status = FAILED, alert ops     │
  └────────────────────────────────────────────┘
```

```sql
-- Outbox worker retry logic
UPDATE webhook_outbox
SET attempts = attempts + 1,
    next_retry = NOW() + INTERVAL '1 second' *
      LEAST(POWER(3, attempts) * 10, 3600)
WHERE id = :event_id;
```

### volta-sdk-js retry on 401

The volta JavaScript SDK automatically retries on 401 Unauthorized by refreshing the [session](session.md):

```
  volta-sdk-js                    volta-auth-proxy
  =============                   ================

  1. GET /api/data
     Cookie: volta_session=abc123
  ──────────────────────────────────────────►

                                  Session expired
  ◄──────────────────────────────────────────
     401 Unauthorized

  2. (automatic) POST /auth/refresh
     Cookie: volta_refresh=xyz789
  ──────────────────────────────────────────►

                                  New session issued
  ◄──────────────────────────────────────────
     Set-Cookie: volta_session=new456

  3. (automatic retry) GET /api/data
     Cookie: volta_session=new456
  ──────────────────────────────────────────►

                                  Success
  ◄──────────────────────────────────────────
     200 OK + data
```

This happens transparently. The application code does not need to handle token refresh.

### Stripe webhook ingestion retries

When volta's [ingestion](ingestion.md) endpoint is temporarily down, Stripe retries delivery with its own schedule. volta must be idempotent to handle these retries correctly.

### M2M token retry

When an [M2M](m2m.md) service receives a 401, it should re-authenticate via [client credentials](client-credentials.md) and retry the original request -- similar to the SDK behavior.

---

## Common mistakes and attacks

### Mistake 1: Retrying non-retryable errors

Retrying a 400 Bad Request or 403 Forbidden will never succeed. It wastes resources and can trigger rate limiting. Only retry on transient failures (5xx, timeouts, connection errors).

### Mistake 2: No maximum retry limit

Retrying forever fills queues, exhausts connections, and can cascade to other systems. Always set a max retry count and move failed items to a dead letter queue.

### Mistake 3: No backoff

Retrying immediately at full speed when a server is overloaded makes the overload worse. Always use exponential backoff with jitter.

### Mistake 4: Not being idempotent

If your webhook handler is not idempotent, retries cause duplicate side effects (e.g., sending the same email twice, charging twice). Always design handlers to tolerate receiving the same event multiple times.

### Attack: Retry amplification (DDoS)

An attacker sends requests that always fail, triggering retries that amplify the load. Defense: retry budgets, circuit breakers, and monitoring retry rates.

### Attack: Exploiting retry delays

An attacker knows the retry schedule and times their attack to coincide with retry storms. Defense: add jitter to retry intervals so retries are not predictable.

---

## Further reading

- [outbox-pattern.md](outbox-pattern.md) -- The outbox worker that performs webhook retries.
- [webhook.md](webhook.md) -- Webhook delivery that relies on retries.
- [ingestion.md](ingestion.md) -- Receiving retried webhooks from external providers.
- [session.md](session.md) -- Session refresh triggered by 401 retry.
- [AWS Architecture Blog: Exponential Backoff and Jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/) -- Detailed analysis of backoff strategies.
