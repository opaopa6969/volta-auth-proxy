# Internal API

[日本語版はこちら](internal-api.ja.md)

---

## What is it?

An internal API is an API that is only accessible within your own network -- not exposed to the public internet. External users cannot reach it directly. Only your own services, running in the same network, can call it.

Think of it like an intercom system inside an office building. People inside the building can call any extension. People outside the building cannot dial the intercom from the street. The intercom is an "internal" communication system -- useful for coordination between departments, but invisible to the outside world.

---

## Why does it matter?

volta-auth-proxy exposes an internal API at `/api/v1/*` that lets your applications perform user and tenant management operations. This API is the bridge between volta (which handles auth) and your apps (which handle business logic). Understanding the distinction between internal and external APIs is crucial for security and architecture.

---

## Internal vs external APIs

```
  The Internet (public)
  ┌─────────────────────────────────────────────────────────┐
  │                                                         │
  │  External/Public APIs:                                  │
  │  - https://api.stripe.com/v1/charges                    │
  │  - https://maps.googleapis.com/maps/api/geocode         │
  │  - volta's /auth/* endpoints (login, callback, verify)  │
  │                                                         │
  │  Anyone on the internet can reach these.                │
  └─────────────────────────────────────────────────────────┘
          │
          │  Firewall / Network boundary
          │
  ┌───────▼─────────────────────────────────────────────────┐
  │  Your private network (internal)                        │
  │                                                         │
  │  Internal APIs:                                         │
  │  - volta's /api/v1/* (user, tenant, member CRUD)        │
  │  - Your database (PostgreSQL on port 5432)              │
  │  - Inter-service communication                          │
  │                                                         │
  │  Only your services can reach these.                    │
  └─────────────────────────────────────────────────────────┘
```

| Aspect | External/Public API | Internal API |
|--------|-------------------|-------------|
| Who can access | Anyone on the internet | Only services in your network |
| Authentication | User credentials, OAuth tokens | Service tokens, network-level trust |
| Rate limiting | Strict (protect from abuse) | Relaxed (trusted callers) |
| Documentation | Public docs, SDKs | Internal docs, team knowledge |
| Versioning | Careful (breaking changes affect customers) | Flexible (you control all callers) |
| Examples | Auth0 Management API, Stripe API | volta `/api/v1/*`, database connections |

---

## volta's internal API: /api/v1/*

volta's internal API lets your downstream apps delegate auth operations back to volta. Instead of your app directly modifying the `users`, `tenants`, or `memberships` tables, it calls volta's API:

### Key endpoints

```
  App delegation via volta's internal API:

  User management:
    GET    /api/v1/tenants/{tenantId}/members          List members
    PATCH  /api/v1/tenants/{tenantId}/members/{userId}  Change role
    DELETE /api/v1/tenants/{tenantId}/members/{userId}  Remove member

  Tenant management:
    GET    /api/v1/tenants/{tenantId}                   Get tenant info
    PATCH  /api/v1/tenants/{tenantId}                   Update tenant

  Invitation management:
    POST   /api/v1/tenants/{tenantId}/invitations       Create invitation
    GET    /api/v1/tenants/{tenantId}/invitations        List invitations
    DELETE /api/v1/tenants/{tenantId}/invitations/{id}   Cancel invitation

  Session management:
    GET    /api/v1/users/{userId}/sessions              List sessions
    DELETE /api/v1/users/{userId}/sessions/{sessionId}   Revoke session
    DELETE /api/v1/users/{userId}/sessions               Revoke all sessions
```

### Authentication for internal API

volta's internal API uses two authentication methods:

1. **Service token:** A static token set via `VOLTA_SERVICE_TOKEN` environment variable. Your backend services include this in the `Authorization` header:

```
Authorization: Bearer <VOLTA_SERVICE_TOKEN>
```

2. **User JWT:** The `X-Volta-JWT` from ForwardAuth. When a user is already authenticated via the proxy, their JWT can authorize API calls scoped to their permissions.

### Why apps delegate to volta instead of writing directly to the DB

```
  Option A: App writes directly to volta's database (BAD)
  ┌─────────┐     ┌──────────────┐
  │ Your App │────►│ volta's DB    │  ← Tight coupling. Schema changes break your app.
  └─────────┘     │ (users,       │     No validation. No audit logging.
                  │  tenants,     │     Your app must know volta's schema.
                  │  memberships) │
                  └──────────────┘

  Option B: App calls volta's internal API (GOOD)
  ┌─────────┐     ┌──────────────┐     ┌──────────────┐
  │ Your App │────►│ volta API     │────►│ volta's DB    │
  └─────────┘     │ /api/v1/*     │     └──────────────┘
                  │               │
                  │ - Validates   │  ← Clean boundary. Validation included.
                  │ - Audit logs  │     Audit logging automatic.
                  │ - Enforces    │     Business rules enforced.
                  │   rules       │     Schema changes don't affect your app.
                  └──────────────┘
```

The internal API provides a stable contract between volta and your application. volta's database schema can change without breaking your app, because the API contract remains stable.

---

## Network security for internal APIs

Internal APIs must not be reachable from the public internet. Common approaches:

1. **Docker network isolation:** volta and your apps share a Docker network. The API port is not published to the host.
2. **Firewall rules:** Only allow traffic to `/api/v1/*` from known internal IPs.
3. **Reverse proxy configuration:** Traefik routes `/api/v1/*` only from internal services, not from external requests.

```yaml
# Traefik: route /api/v1/* only from internal network
http:
  routers:
    volta-internal-api:
      rule: "PathPrefix(`/api/v1/`) && ClientIP(`10.0.0.0/8`)"
      service: volta-service
```

---

## Further reading

- [api.md](api.md) -- API concepts in general.
- [forwardauth.md](forwardauth.md) -- The external-facing auth mechanism.
- [api-versioning.md](api-versioning.md) -- Why volta uses `/api/v1/`.
- [private-vs-public.md](private-vs-public.md) -- Public vs private in cryptography and networking.
