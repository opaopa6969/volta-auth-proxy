# Delegation

[日本語版はこちら](delegation.ja.md)

---

## What is it?

Delegation is giving one system the responsibility for a task that another system would otherwise have to do itself. Instead of every application implementing its own login page, session management, and role checks, they all delegate those tasks to a single auth gateway. The applications trust the gateway's decisions without questioning them.

Think of it like hiring a receptionist for an office building. Each company on each floor could hire their own receptionist to check visitor IDs. But it is far more efficient (and secure) to have one receptionist in the lobby who checks everyone. When a visitor arrives at floor 5, the company there trusts that the lobby receptionist already verified them. That trust IS delegation.

In volta-auth-proxy, downstream applications delegate ALL authentication and coarse-grained authorization to volta via the [ForwardAuth](forwardauth.md) pattern. Apps never see passwords, never manage sessions, never check roles at the gate. volta does it for them.

---

## Why does it matter?

Without delegation, every app must implement auth from scratch:

- **Duplicated code**: 10 apps means 10 login flows, 10 session stores, 10 bugs to fix
- **Inconsistent security**: App A uses secure cookies, App B uses localStorage -- attackers target the weakest link
- **No single logout**: Logging out of App A does not log you out of App B
- **No unified audit trail**: Auth events are scattered across 10 different log formats
- **Onboarding hell**: Every new app developer must become an auth expert

With delegation to volta:

- **One implementation**: Auth logic exists in exactly one place
- **Consistent security**: Same cookie flags, same session policy, same CSRF protection
- **Single logout**: volta invalidates the session, all apps lose access simultaneously
- **Unified audit**: All auth events flow through volta's [audit log](audit-log.md)
- **Developer freedom**: App developers focus on business logic, not auth

---

## How does it work?

### The delegation pattern

```
  WITHOUT delegation:
  ┌──────────┐  ┌──────────┐  ┌──────────┐
  │ App A    │  │ App B    │  │ App C    │
  │ ┌──────┐ │  │ ┌──────┐ │  │ ┌──────┐ │
  │ │Login │ │  │ │Login │ │  │ │Login │ │
  │ │Session│ │  │ │Session│ │  │ │Session│ │
  │ │Roles │ │  │ │Roles │ │  │ │Roles │ │
  │ └──────┘ │  │ └──────┘ │  │ └──────┘ │
  │ Business │  │ Business │  │ Business │
  │ Logic    │  │ Logic    │  │ Logic    │
  └──────────┘  └──────────┘  └──────────┘
  3x the work, 3x the bugs, 3x the attack surface

  WITH delegation:
  ┌──────────────────────────────────┐
  │ volta-auth-proxy                 │
  │ ┌──────┐ ┌──────┐ ┌──────┐     │
  │ │Login │ │Session│ │Roles │     │
  │ └──────┘ └──────┘ └──────┘     │
  └────────────┬─────────────────────┘
               │ X-Volta-* headers
  ┌────────────┼────────────┬────────────┐
  │ App A      │ App B      │ App C      │
  │ Business   │ Business   │ Business   │
  │ Logic only │ Logic only │ Logic only │
  └────────────┘────────────┘────────────┘
  1x the work, 1x the bugs, 1x the attack surface
```

### Trust boundary

Delegation creates a trust boundary. Apps behind volta trust the [headers](header.md) volta sends:

```
  Trust boundary
  ─────────────────────────────────
  Internet │ volta         │ Internal network
           │               │
  User ───►│ Authenticate  │──► X-Volta-User-Id: uuid
           │ Authorize     │──► X-Volta-Roles: ADMIN
           │ Session mgmt  │──► X-Volta-Tenant-Id: uuid
           │               │
  ─────────────────────────────────
  Untrusted │ Gateway      │ Trusted (apps trust volta)
```

### What is delegated vs what is not

| Delegated to volta | NOT delegated (app responsibility) |
|-------------------|------------------------------------|
| Login/logout | Business logic authorization ("can this user edit THIS document?") |
| Session management | Application-specific data |
| Coarse-grained RBAC | Fine-grained permissions |
| CSRF protection | Input validation |
| JWT issuance | JWT verification (apps verify via JWKS) |
| Audit logging (auth events) | Audit logging (business events) |

---

## How does volta-auth-proxy use it?

### ForwardAuth delegation

Every request to a downstream app passes through Traefik, which delegates auth to volta:

```
  Browser → Traefik → volta (/auth/verify) → 200? → App
                                            → 401? → Redirect to /login
```

The app never participates in the auth decision. It receives pre-verified identity headers.

### Internal API delegation

Apps can also delegate user management operations via volta's [internal API](internal-api.md):

```
  App needs to list team members:
  ┌──────────┐                          ┌──────────────────┐
  │ App      │── GET /api/v1/tenants/   │ volta-auth-proxy │
  │          │   {tid}/members          │                  │
  │          │   Authorization: Bearer  │  Validates JWT   │
  │          │   {user_jwt}      ──────►│  Checks role     │
  │          │                          │  Queries DB      │
  │          │◄── 200 [{members}] ──────│  Returns data    │
  └──────────┘                          └──────────────────┘
```

The app delegates member listing to volta instead of maintaining its own user database.

### Service token delegation

Background jobs and [service tokens](service-token.md) enable machine-to-machine delegation:

```java
// AuthService.java
if (token.startsWith("volta-service:")) {
    String provided = token.substring("volta-service:".length());
    if (config.serviceToken().equals(provided)) {
        return Optional.of(new AuthPrincipal(
            new UUID(0L, 0L),
            "service@volta.local",
            "service-token", ...));
    }
}
```

Background jobs delegate identity verification to volta using a static service token.

---

## Common mistakes and attacks

### Mistake 1: Partial delegation

Delegating login to volta but implementing your own session management in the app. Now you have two session systems that can disagree. Delegate all or nothing for each concern.

### Mistake 2: Not trusting the delegate

If your app re-validates the JWT on every request AND hits volta's API to double-check the user's role, you have defeated the purpose of delegation. Trust the X-Volta-* headers -- that is what they are for.

### Mistake 3: Delegating too much

volta handles authentication and coarse RBAC. Delegating fine-grained business rules ("can this user edit this specific invoice?") to volta would make volta a bottleneck and a single point of failure for business logic.

### Attack: Forging delegated headers

An attacker sends a request directly to the app (bypassing Traefik and volta) with fake `X-Volta-User-Id` headers. Defense: apps must only be reachable via the internal Docker network, never exposed to the internet directly.

---

## Further reading

- [forwardauth.md](forwardauth.md) -- The mechanism that implements auth delegation.
- [header.md](header.md) -- The X-Volta-* headers that carry delegated identity.
- [downstream-app.md](downstream-app.md) -- Apps that delegate auth to volta.
- [internal-api.md](internal-api.md) -- API for delegating user management.
- [service-token.md](service-token.md) -- Machine-to-machine delegation.
- [jwt.md](jwt.md) -- The signed token that proves delegation decisions.
