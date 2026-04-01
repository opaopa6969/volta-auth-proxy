# Transition

[日本語版はこちら](transition.ja.md)

---

## What is it?

A transition is the movement from one [state](state-machine.md) to another in a state machine. It is triggered by an event (usually an HTTP request), controlled by a [guard](guard.md) condition, and may execute actions along the way. A transition is the fundamental unit of behavior in volta's auth system: "When THIS happens, IF these conditions hold, DO these things, and move to THAT state."

Think of it like crossing a border between countries. The border crossing (transition) happens at a checkpoint (trigger). The border guard (guard condition) checks your passport. If approved, you go through customs (actions) and arrive in the new country (next state). You cannot sneak across -- every crossing is documented and controlled.

In volta-auth-proxy, every transition is explicitly defined in `dsl/auth-machine.yaml`. There are approximately 30+ transitions across 8 states, plus global transitions that apply from multiple states.

---

## Why does it matter?

Transitions are where things actually happen in the auth system. Login, logout, callback processing, tenant switching, session revocation -- these are all transitions. If a transition is missing or misconfigured:

- **Users get stuck**: No path from their current state to where they need to be
- **Security holes appear**: An unguarded transition lets unauthorized users through
- **Edge cases break**: An invitation acceptance that does not check for email mismatch
- **Debugging is impossible**: Without named transitions, errors are just "something went wrong"

---

## How does it work?

### Anatomy of a transition

```
  Transition = Trigger + Guard + Actions + Next State
  ┌──────────────────────────────────────────────────────┐
  │ login_browser:                                        │
  │   trigger: "GET /login"        ← What event?         │
  │   guard: "!request.accept_json" ← What condition?    │
  │   actions:                      ← What side effects? │
  │     - create_oidc_flow                               │
  │     - redirect to Google                             │
  │   next: AUTH_PENDING            ← Where to go?       │
  └──────────────────────────────────────────────────────┘
```

### Trigger types

```
  HTTP triggers:
  ┌──────────────────────────────────────┐
  │ "GET /login"                         │
  │ "POST /auth/logout"                  │
  │ "GET /callback"                      │
  │ "GET /invite/{code}"                 │
  │ "POST /auth/switch-tenant"           │
  │ "DELETE /auth/sessions/{id}"         │
  └──────────────────────────────────────┘

  Automatic triggers:
  ┌──────────────────────────────────────┐
  │ trigger: automatic                   │
  │ (evaluated on every request)         │
  │ Example: session_timeout             │
  └──────────────────────────────────────┘
```

### Action types

Each transition can have multiple actions, executed in order:

| Type | Purpose | Example |
|------|---------|---------|
| `side_effect` | Database write, state mutation | `create_session`, `invalidate_session` |
| `http` | HTTP response (terminal) | `redirect`, `json_ok`, `json_error`, `render_html` |
| `audit` | Audit log entry | `event: LOGIN_SUCCESS` |
| `guard_check` | Validation that can fail | `csrf_token_valid` |

### Action merge strategy

When a transition has both top-level actions and conditional branches (`next_if`), the merge strategy is `append`:

```yaml
callback_success:
  trigger: "GET /callback"
  guard: "oidc_flow.state_valid && oidc_flow.nonce_valid && oidc_flow.email_verified"
  actions:                              # These execute FIRST:
    - { type: side_effect, action: upsert_user }
    - { type: side_effect, action: delete_oidc_flow }
    - { type: side_effect, action: create_session }
    - { type: audit, event: LOGIN_SUCCESS }
  next_if:                              # Then branch-specific:
    - guard: "invite.present && invite.valid"
      next: INVITE_CONSENT
      actions:
        - { type: http, action: redirect, target: "/invite/{invite.code}/accept" }

    - guard: "user.tenant_count == 1"
      next: AUTHENTICATED
      actions:
        - { type: side_effect, action: auto_select_tenant }
        - { type: http, action: redirect, target: "{request.return_to || config.default_app_url}" }
```

Execution order: `upsert_user` -> `delete_oidc_flow` -> `create_session` -> `audit` -> (branch actions). If top-level actions fail, branch actions do NOT execute.

### Self-transitions

A transition can go back to the same state:

```yaml
# AUTHENTICATED → AUTHENTICATED (stay in same state)
forward_auth:
  trigger: "GET /auth/verify"
  guard: "session.valid && tenant.active"
  next: AUTHENTICATED
```

---

## How does volta-auth-proxy use it?

### The complete login flow as transitions

```
  UNAUTHENTICATED                AUTH_PENDING
  ┌──────────────┐              ┌──────────────┐
  │              ─┼─ login ────►│              ─┼─ callback_success ──►
  │              ─┼─ login_api  │              ─┼─ callback_error ────►
  │   (self)      │             │              ─┼─ callback_state_inv ►
  └──────────────┘              │              ─┼─ callback_nonce_inv ►
                                │   (timeout)   │
                                └──────────────┘

  After callback_success, next_if branches to:
  ├── INVITE_CONSENT  (if invite present)
  ├── AUTHENTICATED   (if 1 tenant)
  ├── TENANT_SELECT   (if multiple tenants)
  └── NO_TENANT       (if 0 tenants, no invite)
```

### Global transitions

These transitions apply across multiple states:

```yaml
global_transitions:
  logout_browser:
    trigger: "POST /auth/logout"
    guard: "!request.accept_json"
    from_except: [UNAUTHENTICATED, AUTH_PENDING]
    actions:
      - { type: side_effect, action: invalidate_session }
      - { type: side_effect, action: clear_cookie }
      - { type: audit, event: LOGOUT }
      - { type: http, action: redirect, target: "/login" }
    next: UNAUTHENTICATED
```

The `from_except` field means "this transition applies from every state EXCEPT these." Logout works from AUTHENTICATED, TENANT_SELECT, NO_TENANT, INVITE_CONSENT, and TENANT_SUSPENDED.

### Transition inventory

| From | Transition | To | Trigger |
|------|-----------|-----|---------|
| UNAUTHENTICATED | login_browser | AUTH_PENDING | GET /login |
| UNAUTHENTICATED | login_api | UNAUTHENTICATED | GET /login |
| AUTH_PENDING | callback_success | (branched) | GET /callback |
| AUTH_PENDING | callback_error | UNAUTHENTICATED | GET /callback |
| INVITE_CONSENT | accept | AUTHENTICATED | POST /invite/{code}/accept |
| TENANT_SELECT | select | AUTHENTICATED | POST /auth/switch-tenant |
| AUTHENTICATED | forward_auth | AUTHENTICATED | GET /auth/verify |
| AUTHENTICATED | switch_tenant | AUTHENTICATED | POST /auth/switch-tenant |
| AUTHENTICATED | revoke_session | AUTHENTICATED | DELETE /auth/sessions/{id} |
| (global) | logout | UNAUTHENTICATED | POST /auth/logout |
| (global) | session_timeout | UNAUTHENTICATED | automatic |

---

## Common mistakes and attacks

### Mistake 1: Missing error transitions

If you define `callback_success` but forget `callback_error`, users whose OIDC provider returns an error get no response. volta defines transitions for every possible outcome of every trigger.

### Mistake 2: Actions after a terminal HTTP response

An `http` action is terminal -- it sends the response. Any action listed after it would never execute. volta's convention places `http` actions last in the action list.

### Mistake 3: Circular transitions with no exit

If State A transitions to State B and State B only transitions back to State A, users are trapped. The `no_deadlock` [invariant](invariant.md) catches this: every state must have a path to AUTHENTICATED or UNAUTHENTICATED.

### Attack: Replaying transitions

An attacker captures a valid `GET /callback?code=...&state=...` URL and replays it. volta prevents this by deleting the OIDC flow record after first use (`delete_oidc_flow` action). The second replay finds no matching flow and fails.

---

## Further reading

- [state-machine.md](state-machine.md) -- The machine that transitions operate within.
- [guard.md](guard.md) -- Conditions that control whether transitions fire.
- [dsl.md](dsl.md) -- Where transitions are defined.
- [invariant.md](invariant.md) -- Rules that all transitions must satisfy.
- [forwardauth.md](forwardauth.md) -- The ForwardAuth transition in detail.
- [invitation-flow.md](invitation-flow.md) -- Invitation-related transitions.
