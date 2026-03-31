# Session Storage Strategies

[日本語版はこちら](session-storage-strategies.ja.md)

---

## What is it?

When a user logs in, the server needs to remember that they're logged in. "Session storage" is where that memory lives. There are three main approaches:

1. **Cookie-only (client-side):** Everything is stored in the cookie itself (encrypted). The server is stateless.
2. **Server-side with in-memory store (Redis/Memcached):** The cookie holds just a session ID; the actual data lives in a fast cache.
3. **Server-side with database (Postgres/MySQL):** The cookie holds a session ID; the actual data lives in a relational database.

---

## Why does it matter?

The choice affects security, scalability, and what you can do with sessions:

| Strategy | Pros | Cons |
|----------|------|------|
| **Cookie-only** | No server state, easy to scale, no DB needed | Size limited (~4KB), can't revoke individual sessions, must encrypt carefully |
| **Redis** | Very fast reads, built-in TTL expiry, easy to scale | Extra infrastructure, data loss on restart (unless persisted), no complex queries |
| **Postgres** | Full query power (list sessions, audit, revoke), durable, no extra infra if you already use Postgres | Slower reads than Redis, needs cleanup of expired rows |

### Key question: Can you revoke a session?

With cookie-only, if a user reports a stolen device, you can't invalidate just that session. You'd have to change the encryption key, which logs out everyone. With server-side storage, you just delete the row.

---

## A simple example

**Cookie-only:**
```
Cookie: session=ENCRYPTED{user_id:123, tenant_id:456, expires:2025-01-01T17:00}
Server: (knows nothing -- decrypts cookie on each request)
```

**Server-side (Postgres):**
```
Cookie: session=a1b2c3d4-uuid-here

Database:
| id         | user_id | tenant_id | expires_at          | invalidated_at |
|------------|---------|-----------|---------------------|----------------|
| a1b2c3d4.. | 123     | 456       | 2025-01-01 17:00:00 | NULL           |
```

---

## In volta-auth-proxy

volta uses **server-side session storage in Postgres**. The session cookie contains only a UUID (the session ID). All session data lives in the `sessions` table:

```sql
sessions(id, user_id, tenant_id, return_to, created_at, last_active_at,
         expires_at, invalidated_at, ip_address, user_agent, csrf_token)
```

### Why Postgres instead of Redis?

1. **volta already uses Postgres** for users, tenants, and memberships. No extra infrastructure.
2. **Sessions need rich queries:** "list all sessions for this user," "invalidate all sessions for this tenant," "show sessions with IP addresses for audit." These are natural SQL queries.
3. **Durability matters:** If the server restarts, sessions survive. With default Redis, they might not.
4. **Performance is sufficient:** Session lookups are primary-key queries on a UUID, which Postgres handles in microseconds. For volta's scale, Redis is not needed.

The cookie is set with `HttpOnly; SameSite=Lax` and conditionally `Secure` (when on HTTPS), so JavaScript cannot read the session ID.

---

## See also

- [sliding-window-expiry.md](sliding-window-expiry.md) -- How session expiry works
- [session-hijacking.md](session-hijacking.md) -- Threats to sessions regardless of storage
