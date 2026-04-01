# CRUD

[日本語版はこちら](crud.ja.md)

---

## What is it?

CRUD stands for Create, Read, Update, Delete -- the four fundamental operations you can perform on any piece of data. Almost every application, from a simple to-do list to a complex SaaS platform, boils down to these four operations on various resources.

Think of it like a filing cabinet. You can **Create** a new folder and put it in, **Read** a folder to see what is inside, **Update** a folder by replacing or adding documents, and **Delete** a folder by removing it entirely. Those are the only four things you can do with a filing cabinet, and they are the only four things most software does with data.

In volta-auth-proxy, the [internal API](internal-api.md) provides CRUD operations for users, tenants, members, and invitations. Each operation maps to an HTTP method and is protected by [RBAC](rbac.md) rules.

---

## Why does it matter?

CRUD is the mental model that makes APIs predictable. When a developer sees a REST API, they immediately know what to expect:

- **Consistency**: Every resource follows the same pattern (list, get, create, update, delete)
- **Discoverability**: If you can list members, you can probably also create and delete them
- **Authorization mapping**: CRUD maps cleanly to permissions ("ADMIN can Create and Delete, MEMBER can only Read")
- **Testing**: You can test all four operations systematically for every resource
- **Documentation**: API docs follow a predictable structure

---

## How does it work?

### CRUD to HTTP mapping

```
  ┌──────────┬─────────┬──────────────────────────────────┐
  │ CRUD     │ HTTP    │ Example                          │
  │──────────│─────────│──────────────────────────────────│
  │ Create   │ POST    │ POST /api/v1/tenants/{id}/invitations   │
  │ Read     │ GET     │ GET  /api/v1/tenants/{id}/members       │
  │ Update   │ PATCH   │ PATCH /api/v1/tenants/{id}/members/{mid}│
  │ Delete   │ DELETE  │ DELETE /api/v1/tenants/{id}/members/{mid}│
  └──────────┴─────────┴──────────────────────────────────┘

  Note: volta uses PATCH (partial update), not PUT (full replace).
  This is intentional: you can update display_name without
  sending every other field.
```

### CRUD and REST

REST APIs organize resources as nouns (members, invitations, tenants) and use HTTP methods as verbs (GET, POST, PATCH, DELETE). This maps directly to CRUD:

```
  Resource: /api/v1/tenants/{tenantId}/members

  GET    /members          → List all members      (Read)
  GET    /members/{id}     → Get one member        (Read)
  POST   /members          → Add a member          (Create)
  PATCH  /members/{id}     → Change member role    (Update)
  DELETE /members/{id}     → Remove a member       (Delete)
```

### CRUD and authorization

Each CRUD operation typically requires a different permission level:

```
  ┌──────────┬───────────────┬──────────────────────┐
  │ Operation│ Required role │ Why                  │
  │──────────│───────────────│──────────────────────│
  │ Read     │ MEMBER+       │ See who is on the team│
  │ Create   │ ADMIN+        │ Invite new members    │
  │ Update   │ ADMIN+        │ Change roles          │
  │ Delete   │ ADMIN+        │ Remove members        │
  └──────────┴───────────────┴──────────────────────┘
```

---

## How does volta-auth-proxy use it?

### Member CRUD

```yaml
# From dsl/protocol.yaml
- method: GET
  path: /tenants/{tenantId}/members
  auth: "MEMBER+"
  description: List tenant members (paginated)

- method: GET
  path: /tenants/{tenantId}/members/{memberId}
  auth: "MEMBER+"
  description: Single member detail

- method: PATCH
  path: /tenants/{tenantId}/members/{memberId}
  auth: "ADMIN+"
  description: Change member role
  guards:
    - "Cannot promote above own role"
    - "Last OWNER cannot be demoted"

- method: DELETE
  path: /tenants/{tenantId}/members/{memberId}
  auth: "ADMIN+"
  description: Remove member (deactivate)
  guards:
    - "Cannot remove last OWNER"
    - "Cannot remove self"
```

Note: there is no `POST /members` because members are created via the [invitation flow](invitation-flow.md), not by direct creation.

### Invitation CRUD

```
  Create:  POST   /tenants/{tid}/invitations     ADMIN+
  Read:    GET    /tenants/{tid}/invitations     ADMIN+
  Delete:  DELETE /tenants/{tid}/invitations/{id} ADMIN+
  Update:  (not supported — invitations are immutable once created)
```

### Response format

All CRUD operations follow a consistent response format defined in `protocol.yaml`:

```json
// Single resource (Read one):
{
  "data": { "id": "uuid", "role": "ADMIN", ... },
  "meta": { "request_id": "uuid" }
}

// Collection (Read many):
{
  "data": [{ ... }, { ... }],
  "meta": { "total": 150, "limit": 20, "offset": 0, "request_id": "uuid" }
}

// Error:
{
  "error": { "code": "ROLE_INSUFFICIENT", "message": "...", "status": 403, "request_id": "uuid" }
}
```

### Soft delete vs hard delete

volta uses soft delete for members: `DELETE /members/{id}` sets `membership.is_active = false` rather than deleting the database row. This preserves audit history and allows reactivation. The member record still exists but is excluded from active queries.

```java
// SqlStore.java effect of DELETE /members/{id}:
// UPDATE tenant_members SET is_active = false WHERE id = ?
// Also: invalidate all sessions for this user in this tenant
```

---

## Common mistakes and attacks

### Mistake 1: Inconsistent HTTP methods

Using `POST /members/delete/{id}` instead of `DELETE /members/{id}`. This breaks the CRUD-to-HTTP mapping and confuses API consumers. volta follows standard REST conventions.

### Mistake 2: No pagination on Read (list)

Returning all 10,000 members in one response kills performance. volta paginates all list endpoints with `?offset=0&limit=20` (max 100).

### Mistake 3: Hard deleting audit-relevant data

Deleting a member row means you lose the record of who was in the tenant and when. volta soft-deletes members and never deletes audit log entries.

### Attack: Mass deletion via enumeration

An attacker who can DELETE members might iterate through all member IDs. volta's [guards](guard.md) prevent this: you cannot remove the last OWNER, you cannot remove yourself, and all deletions are logged in the [audit log](audit-log.md).

---

## Further reading

- [internal-api.md](internal-api.md) -- The API that exposes CRUD operations.
- [rbac.md](rbac.md) -- Role requirements for each CRUD operation.
- [membership.md](membership.md) -- The resource that CRUD operates on.
- [invitation-flow.md](invitation-flow.md) -- How members are created (instead of direct POST).
- [audit-log.md](audit-log.md) -- All CRUD operations are logged.
- [pagination.md](pagination.md) -- How list (Read) operations are paginated.
