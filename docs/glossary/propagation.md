# Propagation

[日本語版はこちら](propagation.ja.md)

---

## What is it?

Propagation is the process of changes spreading through a system over time. In authentication, it refers to the delay between when a change happens (a session is revoked, a key is rotated, a role is changed) and when every part of the system reflects that change.

Think of it like a news broadcast. A major event happens at 2:00 PM. The TV station reports it at 2:05 PM. Viewers watching live see it immediately. Viewers who recorded the morning show and are watching it on delay do not know until they tune into live TV. The "propagation delay" is the time between the event and when everyone knows about it.

In volta, the primary propagation delay is the JWT expiry window. When you revoke a session, server-side checks reflect the change instantly. But JWTs that were already issued continue to be accepted by downstream services until they expire (up to 5 minutes). That 5-minute window is the propagation delay.

---

## Why does it matter?

Propagation delay creates a gap between intent and reality:

- **Security**: You revoke a compromised session at 14:00. The attacker's JWT works until 14:05. Five minutes of continued access.
- **Access control**: You remove admin role at 14:00. The user's JWT still says `volta_roles: ["admin"]` until 14:05.
- **Key rotation**: You rotate a compromised key at 14:00. Tokens signed with the old key are still accepted until 14:05.

Understanding propagation helps you make informed trade-offs. volta chose 5-minute JWT expiry because it keeps propagation delay short enough for security while enabling stateless verification (no database call per request).

---

## How does it work?

### Sources of propagation delay

```
  Change made          Time to propagate    Why
  ────────────────────────────────────────────────────
  Session deleted      Instant (server)     DB lookup on each request
                       0-5 min (JWT)        Issued JWTs still valid

  Key rotated          Instant (new tokens) New JWTs use new key
                       0-5 min (old tokens) Old-key JWTs expire

  Role changed         Instant (DB)         DB reflects change
                       0-5 min (JWT)        JWT claims are stale

  JWKS cache refresh   0-N min              Downstream cache TTL
```

### volta's propagation model

```
  Time ──────────────────────────────────────────────────►

  14:00:00  Admin revokes user's session
            │
            ├── Server-side: INSTANT
            │   Next session check → 401
            │   No new JWTs can be issued
            │
            └── JWT-based: DELAYED (0-5 min)
                Existing JWTs still valid
                │
  14:00:01  Downstream app checks JWT → VALID (4:59 left)
  14:02:30  Downstream app checks JWT → VALID (2:30 left)
  14:05:00  JWT expires → INVALID
            │
            ▼
            Fully propagated. User locked out everywhere.
```

### The propagation equation

```
  Maximum propagation delay = JWT TTL

  volta:  JWT TTL = 5 minutes
          → Max delay = 5 minutes

  If JWT TTL were 1 hour:
          → Max delay = 1 hour
          → Attacker has 1 hour window

  If JWT TTL were 24 hours:
          → Max delay = 24 hours
          → Unacceptable for most applications

  Trade-off:
  ┌──────────────────────────────────────────────┐
  │  Shorter JWT TTL:                            │
  │  ✓ Faster propagation                       │
  │  ✓ Better security                          │
  │  ✗ More frequent silent refreshes            │
  │  ✗ More load on token endpoint               │
  │                                              │
  │  Longer JWT TTL:                             │
  │  ✓ Fewer refreshes                          │
  │  ✓ Less server load                         │
  │  ✗ Slower propagation                       │
  │  ✗ Larger attack window                     │
  │                                              │
  │  volta chose 5 min: good balance.            │
  └──────────────────────────────────────────────┘
```

### Where propagation is instant vs. delayed

```
  ┌────────────────────────────────────────────────┐
  │  INSTANT (server-side state check):            │
  │  ├── Session lookup in database                │
  │  ├── Cookie validation                         │
  │  └── Token endpoint (issues new JWTs)          │
  │                                                │
  │  DELAYED (stateless JWT check):                │
  │  ├── Downstream apps verifying JWT signature   │
  │  ├── API gateways checking JWT claims          │
  │  └── Microservices trusting JWT roles          │
  └────────────────────────────────────────────────┘

  volta-auth-proxy itself checks sessions (instant).
  Downstream apps check JWTs (delayed up to 5 min).
```

---

## How does volta-auth-proxy use it?

### Dual verification model

volta uses two layers with different propagation characteristics:

```
  Layer 1: Session (instant propagation)
  ┌──────────────────────────────────────────┐
  │  Every request to volta checks session   │
  │  Session deleted → instant 401           │
  │  No propagation delay                    │
  └──────────────────────────────────────────┘

  Layer 2: JWT (delayed propagation, max 5 min)
  ┌──────────────────────────────────────────┐
  │  Downstream apps verify JWT signature    │
  │  JWT claims are point-in-time snapshot   │
  │  Changes propagate when JWT expires      │
  │  and client gets new JWT via refresh     │
  └──────────────────────────────────────────┘
```

### Key rotation propagation

When `POST /api/v1/admin/keys/rotate` is called:

```
  T+0:00  New key active. Old key retired.
          New JWTs signed with new key.

  T+0:00  Downstream with cached JWKS:
    to    May still verify old-key tokens ✓
  T+5:00  (old key still in JWKS during transition)

  T+5:00  All old-key JWTs expired.
          Old key can be removed from JWKS.

  Propagation complete.
```

### Silent refresh as propagation mechanism

[Silent refresh](silent-refresh.md) is how JWT changes propagate to clients:

```
  Role change:  admin → member (at T+0:00)

  T+0:00  Old JWT: volta_roles=["admin"]  (still valid)
  T+4:00  Old JWT about to expire
          volta-sdk-js calls token endpoint
          New JWT: volta_roles=["member"]  (reflects change)
  T+4:00+ All subsequent requests use updated JWT

  Silent refresh propagated the role change.
```

---

## Common mistakes and attacks

### Mistake 1: Assuming instant propagation for JWTs

Developers expect `DELETE session → immediate lockout everywhere`. This is only true for server-side session checks. JWT-based checks have a propagation delay equal to the JWT TTL.

### Mistake 2: Very long JWT TTLs

A 24-hour JWT means 24 hours of propagation delay. If a key is compromised, attackers have a full day. Keep JWT TTLs short (volta: 5 minutes).

### Mistake 3: Not considering JWKS cache TTL

If downstream services cache the JWKS endpoint for 1 hour, key rotation propagation is delayed by up to 1 hour plus JWT TTL. Recommend short JWKS cache TTLs (5-15 minutes).

### Mistake 4: Sensitive operations relying solely on JWT claims

For operations like "delete all tenant data" or "promote user to admin," do not rely only on JWT claims (which may be stale). Verify against the database for critical operations.

### Attack: Exploiting the propagation window

An attacker whose session is revoked races to use their still-valid JWT (up to 5 minutes) to perform as many actions as possible. Mitigation: for critical actions, always check session validity server-side, not just JWT validity.

---

## Further reading

- [revoke.md](revoke.md) -- The action that triggers propagation
- [invalidation.md](invalidation.md) -- Making things invalid (which then propagates)
- [silent-refresh.md](silent-refresh.md) -- The mechanism that delivers propagated changes to clients
- [graceful-transition.md](graceful-transition.md) -- Managing propagation during key rotation
- [jwt.md](jwt.md) -- The self-contained token that causes propagation delay
- [session.md](session.md) -- The server-side state that propagates instantly
