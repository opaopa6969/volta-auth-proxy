# Idempotency

[日本語版はこちら](idempotency.ja.md)

---

## What is it?

An operation is **idempotent** if doing it once produces the same result as doing it multiple times. Like pressing an elevator button -- pressing it 5 times doesn't call 5 elevators. The result is the same as pressing it once.

In HTTP terms:
- `GET /users/42` -- Idempotent. Reading the same user 10 times doesn't change anything.
- `DELETE /users/42` -- Idempotent. Deleting an already-deleted user has no additional effect.
- `POST /invitations` -- **NOT idempotent.** Sending the same invitation request twice might create two invitations.

---

## Why does it matter?

Networks are unreliable. Requests get lost, timeouts happen, clients retry. If an operation is not idempotent, retries can cause duplicate data:

```
Client: POST /invitations {email: "alice@example.com"}
Server: Creates invitation, starts sending response...
Network: Connection drops!
Client: "Did it work? I'll retry..."
Client: POST /invitations {email: "alice@example.com"}
Server: Creates ANOTHER invitation
Result: Alice gets two invitation emails. Confusing.
```

Idempotent operations are safe to retry. Non-idempotent operations need special handling.

---

## A simple example

### HTTP methods and idempotency

| Method | Idempotent? | Why |
|--------|-------------|-----|
| GET | Yes | Reading doesn't change state |
| PUT | Yes | "Set this to X" -- doing it twice still results in X |
| DELETE | Yes | Deleting something already deleted = no change |
| POST | **No** | "Create something new" -- doing it twice = two things |
| PATCH | It depends | "Change field X to Y" is idempotent; "increment by 1" is not |

### Making POST safe to retry

Common strategies:
1. **Idempotency key:** Client sends a unique ID with the request. Server checks if that ID was already processed.
2. **Unique constraints:** Database rejects duplicates (e.g., unique on email + tenant).
3. **Check-then-create:** Before creating, check if it already exists.

---

## In volta-auth-proxy

volta handles idempotency in invitations through **database-level constraints**. The invitations system uses `ON CONFLICT` clauses and uniqueness checks:

When creating a membership via invitation:
```sql
INSERT INTO memberships(user_id, tenant_id, role)
VALUES (?, ?, ?)
ON CONFLICT(user_id, tenant_id) DO UPDATE SET is_active = true, role = EXCLUDED.role
```

This means: if a user is already a member of this tenant, don't create a duplicate -- just update their role and reactivate them. This makes the "accept invitation" operation idempotent. A user can click an invitation link multiple times without creating duplicate memberships.

For invitation creation itself, volta uses `max_uses` and `used_count` fields to control how many times an invitation can be redeemed, preventing unlimited use of a single invitation code.

The API endpoints that use GET (listing members, tenants, audit logs) are naturally idempotent. POST endpoints (create invitation, suspend tenant) use database constraints and unique keys to prevent harmful duplicates.

---

## See also

- [pagination.md](pagination.md) -- GET requests that are always idempotent
- [api-versioning.md](api-versioning.md) -- How API contracts define expected behavior
