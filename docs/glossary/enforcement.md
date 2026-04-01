# Enforcement

[日本語版はこちら](enforcement.ja.md)

---

## What is it?

Enforcement is the act of actively preventing violations of rules. Having a rule is not enough -- you need something that checks every action and blocks the ones that break the rules. A policy without enforcement is a suggestion. A policy with enforcement is a law.

Think of it like speed limits on a highway. The sign says "60 km/h" -- that is the policy. But without police officers or speed cameras, drivers will go 120. The police officer IS the enforcement. volta-auth-proxy does not just define rules in [YAML DSL files](dsl.md); it enforces them on every single request.

In volta, enforcement happens at multiple points: ForwardAuth checks on every proxied request, API authorization on every internal API call, and [guard](guard.md) checks on every [state machine](state-machine.md) transition.

---

## Why does it matter?

The gap between "we have a rule" and "we enforce the rule" is where security breaches happen:

- **Unenforced role check**: policy.yaml says ADMIN+ can manage members, but the code does not check -- any VIEWER can delete members
- **Client-side only enforcement**: The UI hides the "Delete" button from non-admins, but the API accepts DELETE from anyone
- **Partial enforcement**: ForwardAuth checks roles for App A but someone forgot to configure it for App B
- **Enforcement gaps**: The CSRF check runs on POST but not on DELETE

---

## How does it work?

### Enforcement points

```
  Request from browser
  │
  ├── [1] Traefik ForwardAuth ──► volta /auth/verify
  │   │
  │   ├── Session valid?          (enforcement: authentication)
  │   ├── Tenant active?          (enforcement: tenant status)
  │   ├── Role in allowed_roles?  (enforcement: app access)
  │   │
  │   └── Pass? → Forward to app with X-Volta-* headers
  │       Fail? → 401 or 403
  │
  ├── [2] volta Internal API
  │   │
  │   ├── JWT valid?              (enforcement: authentication)
  │   ├── JWT tenant == path tenant? (enforcement: tenant isolation)
  │   ├── Role >= required_role?  (enforcement: RBAC)
  │   ├── Constraint satisfied?   (enforcement: business rules)
  │   │   e.g., "cannot remove last OWNER"
  │   │
  │   └── Pass? → Execute operation
  │       Fail? → 403 or 400
  │
  └── [3] State machine transitions
      │
      ├── Guard expression true?  (enforcement: transition rules)
      ├── CSRF token valid?       (enforcement: anti-CSRF)
      └── Rate limit not exceeded? (enforcement: abuse prevention)
```

### Defense in depth

volta enforces rules at multiple layers. Even if one layer fails, another catches the violation:

```
  Layer 1: Traefik
  ┌──────────────────────────────────────────────┐
  │ Only routes to volta for /auth/* paths       │
  │ Only routes to apps for configured domains   │
  └──────────────────────────────────────────────┘
            │
  Layer 2: volta ForwardAuth
  ┌──────────────────────────────────────────────┐
  │ Session validation                           │
  │ Tenant status check                          │
  │ Role-based app access check                  │
  └──────────────────────────────────────────────┘
            │
  Layer 3: volta Internal API
  ┌──────────────────────────────────────────────┐
  │ JWT verification                             │
  │ Tenant isolation (path == JWT tenant)         │
  │ Role hierarchy check                         │
  │ Business rule constraints                    │
  └──────────────────────────────────────────────┘
            │
  Layer 4: Database constraints
  ┌──────────────────────────────────────────────┐
  │ Foreign keys, NOT NULL, unique constraints   │
  │ Last line of defense                         │
  └──────────────────────────────────────────────┘
```

---

## How does volta-auth-proxy use it?

### ForwardAuth enforcement

`AppRegistry.java` matches incoming requests to configured apps and enforces `allowed_roles`:

```yaml
# volta-config.yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
  - id: app-admin
    url: https://admin.example.com
    allowed_roles: [ADMIN, OWNER]
```

When a MEMBER tries to access `admin.example.com`, volta returns 403 ROLE_INSUFFICIENT. The app never even sees the request.

### Tenant isolation enforcement

Every API call that includes `{tenantId}` in the path is checked against the JWT's `volta_tid` claim:

```yaml
# dsl/protocol.yaml
tenant_check:
  rule: "Path param {tenantId} MUST equal JWT claim volta_tid"
  error: TENANT_ACCESS_DENIED
```

This prevents User A (tenant-1) from accessing User B's data (tenant-2) by manipulating the URL.

### Constraint enforcement

`dsl/policy.yaml` defines constraints that are enforced in Java code:

```yaml
# policy.yaml
constraints:
  - id: last_owner
    rule: "A tenant MUST have at least one OWNER at all times"
    enforcement: "Block role change / removal if it would leave zero OWNERs"
    error: "LAST_OWNER_CANNOT_CHANGE"
```

The Java code enforces this before every role change and member removal:

```java
// Before changing role or removing member:
long ownerCount = store.countOwners(tenantId);
if (ownerCount <= 1 && targetRole != "OWNER") {
    throw new ApiException("LAST_OWNER_CANNOT_CHANGE", 400);
}
```

### Rate limit enforcement

`RateLimiter.java` enforces the rate limits defined in `policy.yaml`:

```yaml
rate_limits:
  - endpoint: "GET /login"
    limit: 10
    window: "1 minute"
    key: ip
  - endpoint: "/api/v1/*"
    limit: 200
    window: "1 minute"
    key: user_id
```

### CSRF enforcement

CSRF tokens are enforced on all HTML form submissions (POST, DELETE, PATCH) but exempt for JSON API requests:

```yaml
# policy.yaml
csrf:
  scope: "HTML form POST/DELETE/PATCH only"
  exempt: "JSON API requests"
  validation: "Before handler compares form._csrf with session.csrf_token"
```

---

## Common mistakes and attacks

### Mistake 1: Client-side only enforcement

Hiding a button in the UI is not enforcement. If the API endpoint still accepts the request, the rule is not enforced. Always enforce on the server.

### Mistake 2: Enforce-then-proceed without atomicity

Checking "is this the last OWNER?" and then removing them in separate transactions creates a race condition. Two simultaneous requests could both see "2 OWNERs" and each remove one, leaving zero. volta checks and acts within the same database transaction.

### Mistake 3: Inconsistent enforcement across endpoints

If `/api/v1/members` checks roles but `/api/v1/members/{id}` does not, attackers will find the gap. volta applies authorization middleware to all `/api/v1/*` routes uniformly.

### Attack: TOCTOU (Time of Check to Time of Use)

An attacker exploits the gap between when a check happens and when the action executes. For example: volta checks that a member exists, then the attacker deletes the member before the next line of code runs. volta prevents this by using database transactions and SELECT ... FOR UPDATE where needed.

---

## Further reading

- [forwardauth.md](forwardauth.md) -- The primary enforcement point for app access.
- [rbac.md](rbac.md) -- The role-based rules being enforced.
- [guard.md](guard.md) -- Enforcement at the state machine level.
- [policy-engine.md](policy-engine.md) -- Future enforcement via jCasbin.
- [hierarchy.md](hierarchy.md) -- How role hierarchy is enforced.
- [csrf.md](csrf.md) -- CSRF enforcement details.
- [tenant.md](tenant.md) -- Tenant isolation enforcement.
