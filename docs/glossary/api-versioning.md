# API Versioning

[日本語版はこちら](api-versioning.ja.md)

---

## What is it?

API versioning is a strategy for evolving an API without breaking existing clients. When you need to change how an endpoint works, you release a new version rather than modifying the existing one. Existing clients continue to use v1 while new clients can use v2.

Think of it like a restaurant menu. If you change a dish's recipe, you can either replace it (breaking the experience for regulars who loved it) or add a "new version" alongside the original.

---

## Why does it matter?

APIs have **consumers** -- mobile apps, frontend code, partner integrations. Once those consumers are built against your API, changing the API breaks them. Without versioning:

- A mobile app deployed to millions of phones can't be updated instantly
- Partner integrations stop working at 3 AM when you deploy
- Frontend code cached in browsers fails after a backend update

Versioning lets you evolve the API while giving consumers time to migrate.

---

## A simple example

### Common versioning strategies

| Strategy | Example | Pros | Cons |
|----------|---------|------|------|
| **URL path** | `/api/v1/users` | Obvious, easy to route | Duplicates endpoints |
| **Header** | `Accept: application/vnd.myapi.v2+json` | Clean URLs | Hard to discover, hard to test in browser |
| **Query param** | `/api/users?version=2` | Simple | Easy to forget |

### What counts as a breaking change?

| Change | Breaking? |
|--------|-----------|
| Adding a new field to a response | No |
| Adding a new optional parameter | No |
| Removing a field from a response | **Yes** |
| Renaming a field | **Yes** |
| Changing a field's type (string -> number) | **Yes** |
| Changing the meaning of a status code | **Yes** |
| Adding a new endpoint | No |

---

## In volta-auth-proxy

volta uses **URL path versioning** with all API endpoints under `/api/v1/`:

```
GET  /api/v1/users/me
GET  /api/v1/tenants/{tenantId}/members
POST /api/v1/tenants/{tenantId}/invitations
GET  /api/v1/admin/tenants
POST /api/v1/admin/keys/rotate
```

### Additive-only JWT claims policy

volta takes an important approach to JWT claim versioning: **additive-only changes.** The `volta_v` claim in every JWT indicates the schema version (currently `1`):

```json
{
  "volta_v": 1,
  "volta_tid": "...",
  "volta_roles": ["ADMIN"]
}
```

The rule is:
- **Adding** new claims (e.g., `volta_permissions`) is safe in any version
- **Removing** or **renaming** a claim requires bumping `volta_v` to `2`
- Downstream services should check `volta_v` and handle both formats during migration

This means a service built for `volta_v: 1` JWTs will continue to work even if new claims are added. It only needs to update when the version number changes.

### Breaking changes policy

volta follows these principles:
1. **v1 stays stable.** No breaking changes within a version.
2. **New versions are additive.** v2 might add features but won't remove v1 endpoints overnight.
3. **Deprecation before removal.** If v1 will eventually be retired, announce it well in advance.
4. **JWT claims are versioned independently** via `volta_v`, separate from the API path version.

---

## See also

- [jwt-payload.md](jwt-payload.md) -- The `volta_v` claim for JWT versioning
- [pagination.md](pagination.md) -- Pagination parameters as part of the API contract
