# Interface / Extension Point

[日本語版はこちら](interface-extension-point.ja.md)

---

## What is it?

An interface (in the programming sense) is a contract that says "here is what this component must be able to do" without specifying how it does it. An extension point is a place in your code where you deliberately leave room for alternative implementations.

Think of it like a power outlet. The outlet (interface) defines the shape and voltage. Any device that matches the outlet's shape (implements the interface) can plug in: a lamp, a phone charger, a toaster. The outlet does not care what the device is -- it only cares that the plug fits. The outlet is an extension point: it is a designed-in spot where you can connect different things.

---

## Why does it matter?

volta-auth-proxy uses Java interfaces as extension points for components that might need alternative implementations in the future. This is a key architectural decision: **design for extensibility without implementing everything today.**

The interfaces `SessionStore`, `AuditSink`, and `NotificationService` are examples of this pattern. Each defines what the component must do, and volta ships with specific implementations while leaving the door open for others.

---

## How Java interfaces work as extension points

A Java interface is a list of methods without their implementations:

```java
// The contract: "A SessionStore must be able to do these things"
interface SessionStore {
    void createSession(UUID sessionId, UUID userId, ...);
    Optional<SessionRecord> findSession(UUID sessionId);
    void touchSession(UUID sessionId, Instant expiresAt);
    void revokeSession(UUID sessionId);
    void revokeAllSessions(UUID userId);
    List<SessionRecord> listUserSessions(UUID userId);
    int countActiveSessions(UUID userId);
    // ...
}
```

Then, concrete implementations provide the "how":

```java
// Implementation A: Store sessions in PostgreSQL
class PostgresSessionStore implements SessionStore {
    @Override
    public void createSession(...) {
        // INSERT INTO sessions ...
    }
    // ... all methods implemented using SQL
}

// Implementation B: Store sessions in Redis
class RedisSessionStore implements SessionStore {
    @Override
    public void createSession(...) {
        // jedis.set("volta:session:" + sessionId, ...)
    }
    // ... all methods implemented using Redis commands
}
```

The rest of volta's code only talks to `SessionStore` -- it never mentions PostgreSQL or Redis directly. This means switching session storage requires zero changes to the auth logic.

---

## volta's extension points

### SessionStore

**What it does:** Creates, reads, updates, and invalidates user sessions.

**Implementations:**
- `PostgresSessionStore` -- Sessions stored in the `sessions` table. Default.
- `RedisSessionStore` -- Sessions stored in Redis for higher throughput.

**Selection:** `SESSION_STORE=postgres` or `SESSION_STORE=redis` in `.env`

```
  volta code:                    SessionStore interface
  ┌─────────────────┐          ┌──────────────────────┐
  │ AuthHandler      │────────►│ createSession()       │
  │ ForwardAuth      │         │ findSession()         │
  │ SessionManager   │         │ touchSession()        │
  │                  │         │ revokeSession()       │
  └─────────────────┘         └──────────┬───────────┘
                                         │
                              ┌──────────┴───────────┐
                              │                      │
                    ┌─────────▼──────┐    ┌─────────▼──────┐
                    │ Postgres       │    │ Redis          │
                    │ SessionStore   │    │ SessionStore   │
                    └────────────────┘    └────────────────┘
```

### AuditSink

**What it does:** Publishes audit events (login, logout, role change, etc.).

**Implementations:**
- `NoopAuditSink` -- Does nothing. Default (audit events go to Postgres audit table via main code path).
- `KafkaAuditSink` -- Streams events to a Kafka topic.
- `ElasticsearchAuditSink` -- Indexes events in Elasticsearch.

**Selection:** `AUDIT_SINK=postgres` or `AUDIT_SINK=kafka` or `AUDIT_SINK=elasticsearch`

### NotificationService

**What it does:** Sends email notifications (invitation emails).

**Implementations:**
- No-op lambda -- Does nothing. Default (invitations use link sharing).
- `SmtpNotificationService` -- Sends via SMTP server.
- `SendGridNotificationService` -- Sends via SendGrid API.

**Selection:** `NOTIFICATION_CHANNEL=none` or `NOTIFICATION_CHANNEL=smtp` or `NOTIFICATION_CHANNEL=sendgrid`

---

## Why you design for extensibility without implementing everything

### The temptation: build it all now

When designing session storage, you might think:

> "We should support PostgreSQL, Redis, Memcached, DynamoDB, MongoDB, and Cassandra from day one."

This is a trap. Each implementation is code to write, test, document, and maintain. If you support 6 session stores but only use 1, you have 5 session stores worth of code that nobody tests in production.

### The volta approach: interface now, implement later

volta's strategy is:

1. **Define the interface** -- Decide what a session store must do
2. **Implement what you need now** -- PostgreSQL (Phase 1)
3. **Implement the next one when there's demand** -- Redis (Phase 2)
4. **Leave the door open** -- Anyone can add DynamoDB, Memcached, etc. by implementing the interface

This is the YAGNI principle ("You Ain't Gonna Need It") combined with the Open/Closed principle ("open for extension, closed for modification"):

```
  Phase 1:
  SessionStore ←── PostgresSessionStore    ← What we need NOW

  Phase 2:
  SessionStore ←── PostgresSessionStore
               ←── RedisSessionStore       ← Added when needed

  Future (if needed):
  SessionStore ←── PostgresSessionStore
               ←── RedisSessionStore
               ←── DynamoSessionStore      ← Added by you or community
```

The interface was there from Phase 1. But the DynamoDB implementation was not written until someone actually needed it. Zero wasted effort.

---

## The factory method pattern

volta uses a static `create()` method on the interface to select the right implementation based on configuration:

```java
interface SessionStore {
    // ... methods ...

    static SessionStore create(AppConfig config, SqlStore store) {
        if ("redis".equalsIgnoreCase(config.sessionStore())) {
            return new RedisSessionStore(config.redisUrl());
        }
        return new PostgresSessionStore(store);
    }
}
```

This is clean because:
- The rest of the code just calls `SessionStore.create(config, store)` and does not know which implementation it gets
- Adding a new implementation means adding one `if` branch and one class
- No dependency injection framework, no XML configuration, no annotation scanning

---

## Further reading

- [java.md](java.md) -- The programming language these interfaces are written in.
- [session-storage-strategies.md](session-storage-strategies.md) -- The SessionStore implementations in detail.
