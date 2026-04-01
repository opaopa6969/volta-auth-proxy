# Guard

[日本語版はこちら](guard.ja.md)

---

## What is it?

A guard is a boolean condition that must be true for a [state machine](state-machine.md) [transition](transition.md) to fire. When a trigger event arrives, the system evaluates the guard expression. If the guard is true, the transition proceeds. If the guard is false, the transition is blocked and the system either tries the next matching transition or returns an error.

Think of it like a bouncer at a club door. The door (trigger) is there for everyone, but the bouncer (guard) checks your ID before letting you through. "Are you on the guest list AND over 21?" -- that is a guard. The door does not open unless both conditions are satisfied.

In volta-auth-proxy, guards are written as CEL-like boolean expressions inside `dsl/auth-machine.yaml`. They reference typed context variables like `session.valid`, `tenant.active`, and `membership.role`.

---

## Why does it matter?

Without guards, any trigger would cause any transition. A user could go from UNAUTHENTICATED directly to AUTHENTICATED by visiting `/callback` with no valid OIDC flow. Guards are the security checkpoints of the state machine.

- **Security**: Guards prevent unauthorized state transitions (e.g., accessing admin pages without the ADMIN role)
- **Correctness**: Guards ensure transitions only happen when preconditions are met
- **Disambiguation**: When multiple transitions share the same trigger, guards determine which one fires
- **Readability**: Guard expressions are declarative -- you can read what conditions are required without tracing code

---

## How does it work?

### Guard expression syntax

volta uses a CEL-like (Common Expression Language) syntax:

```
  Operators:   &&  ||  !  ==  !=  >  <  >=  <=  in
  Variables:   session.valid, tenant.active, membership.role, etc.
  Literals:    true, false, integers, "strings", ['arrays']
  Evaluation:  Short-circuit, left to right
```

### Examples

```yaml
# Simple boolean
guard: "session.valid"

# Compound condition
guard: "session.valid && tenant.active"

# Negation
guard: "!request.accept_json"

# Role check with 'in' operator
guard: "membership.role in ['ADMIN', 'OWNER']"

# Complex multi-condition
guard: "oidc_flow.state_valid && oidc_flow.nonce_valid && oidc_flow.email_verified"

# Invitation check
guard: "invite.valid && !invite.expired && !invite.used && invite.email_match"
```

### Context variables

Guards reference typed variables defined in the `context` section of `auth-machine.yaml`:

```
  ┌─────────────────────────────────────────────┐
  │ context:                                     │
  │   session:                                   │
  │     valid: bool                              │
  │     expired: bool                            │
  │     tenant_id: uuid?                         │
  │   user:                                      │
  │     exists: bool                             │
  │     active: bool                             │
  │     tenant_count: int                        │
  │   membership:                                │
  │     exists: bool                             │
  │     role: enum[OWNER, ADMIN, MEMBER, VIEWER] │
  │   tenant:                                    │
  │     active: bool                             │
  │     suspended: bool                          │
  │   invite:                                    │
  │     valid: bool                              │
  │     expired: bool                            │
  │     email_match: bool                        │
  │   oidc_flow:                                 │
  │     state_valid: bool                        │
  │     nonce_valid: bool                        │
  │   request:                                   │
  │     accept_json: bool                        │
  └─────────────────────────────────────────────┘
```

### Guard evaluation order (priority)

When multiple transitions share the same trigger, guards are evaluated in `priority` order:

```yaml
# AUTH_PENDING transitions for GET /callback
callback_error:
  trigger: "GET /callback"
  guard: "oidc_flow.has_error_param"
  priority: 1                          # checked FIRST

callback_state_invalid:
  trigger: "GET /callback"
  guard: "!oidc_flow.state_valid"
  priority: 2                          # checked second

callback_nonce_invalid:
  trigger: "GET /callback"
  guard: "oidc_flow.state_valid && !oidc_flow.nonce_valid"
  priority: 3                          # checked third

callback_success:
  trigger: "GET /callback"
  guard: "oidc_flow.state_valid && oidc_flow.nonce_valid && oidc_flow.email_verified"
  priority: 5                          # checked last
```

The first guard that evaluates to `true` wins. This is why error checks have lower priority numbers (checked first) than the success path.

### Template expressions vs guard expressions

There is an important distinction in the DSL:

```
  Guard expressions:           Template expressions:
  ┌──────────────────────┐    ┌──────────────────────────────────┐
  │ guard: "a || b"      │    │ target: "{x || y}"               │
  │ "||" = logical OR    │    │ "||" = COALESCE (fallback)       │
  │ Result: true/false   │    │ Result: value of x, or y if null │
  └──────────────────────┘    └──────────────────────────────────┘
```

---

## How does volta-auth-proxy use it?

### ForwardAuth guard

The most critical guard in the system protects the ForwardAuth endpoint:

```yaml
# AUTHENTICATED state
forward_auth:
  trigger: "GET /auth/verify"
  guard: "session.valid && tenant.active"
  priority: 4
  actions:
    - { type: side_effect, action: touch_session }
    - { type: http, action: return_volta_headers }
  next: AUTHENTICATED
```

This guard ensures that every request forwarded to downstream apps has a valid session AND an active tenant. If either fails, lower-priority transitions handle the error cases (401, 403).

### Invitation acceptance guards

The invitation flow uses multiple guards to handle every edge case:

```yaml
accept_already_member:
  guard: "membership.exists"           # Already a member? → error
  priority: 1

accept_expired:
  guard: "invite.expired"              # Expired? → show expired page
  priority: 2

accept_email_mismatch:
  guard: "!invite.email_match"         # Wrong email? → error
  priority: 3

accept:
  guard: "invite.valid && !invite.expired && !invite.used && invite.email_match"
  priority: 4                          # All checks pass → accept
```

### Content negotiation guard

volta uses guards to decide response format:

```yaml
login_browser:
  trigger: "GET /login"
  guard: "!request.accept_json"        # Browser → redirect to Google
  next: AUTH_PENDING

login_api:
  trigger: "GET /login"
  guard: "request.accept_json"         # API/SPA → return JSON error
  next: UNAUTHENTICATED
```

---

## Common mistakes and attacks

### Mistake 1: Overlapping guards without priority

If two guards can both be true simultaneously and there is no priority, the result is ambiguous. volta requires explicit `priority` values on all transitions with the same trigger.

### Mistake 2: Guards that can never be true

A guard like `"session.valid && session.expired"` is a contradiction -- it can never be true. This creates a dead transition that wastes space and confuses readers.

### Mistake 3: Forgetting negation cases

If you guard the success path but forget the failure path, users who fail the guard get no response. volta handles this by defining transitions for every guard outcome (success, expired, invalid, mismatch, etc.).

### Attack: Guard bypass via parameter manipulation

An attacker might try to set `oidc_flow.state_valid` to true by manipulating request parameters. volta's guards reference server-side context (database lookups, session state), NOT raw request parameters. The context is computed by the Java code before guard evaluation.

---

## Further reading

- [transition.md](transition.md) -- Guards are part of transitions.
- [state-machine.md](state-machine.md) -- The machine that evaluates guards.
- [dsl.md](dsl.md) -- Where guards are defined.
- [invariant.md](invariant.md) -- Global rules complementing per-transition guards.
- [nonce.md](nonce.md) -- The nonce checked by `oidc_flow.nonce_valid`.
- [state.md](state.md) -- The OIDC state checked by `oidc_flow.state_valid`.
- [csrf.md](csrf.md) -- CSRF tokens validated by `guard_check` actions.
