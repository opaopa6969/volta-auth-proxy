# State Machine

[日本語版はこちら](state-machine.ja.md)

---

## What is it?

A state machine is a model that describes a system as a set of defined states and the rules for moving between them. At any moment, the system is in exactly one state. It can only change state when a specific event (trigger) happens and certain conditions (guards) are met. The combination of current state + trigger + guard determines the next state.

Think of it like a board game. Your piece sits on exactly one square. You can only move when it is your turn (trigger), and only if the dice roll meets certain conditions (guard). You cannot teleport to a random square -- you follow the rules. A state machine works the same way: no skipping, no ambiguity, no undefined behavior.

volta-auth-proxy models its entire authentication flow as an 8-state machine. Every user is always in exactly one of these 8 states, and every possible action is a defined transition.

---

## Why does it matter?

Without a state machine, auth logic becomes a tangled web of if/else branches scattered across multiple files. Edge cases are missed. Users get stuck in impossible states. Security holes appear at boundaries nobody tested.

- **No undefined behavior**: Every state has defined exits. Users cannot get "stuck"
- **Security by design**: You can prove that unauthenticated users cannot reach authenticated states without going through the login flow
- **Testability**: Every transition is enumerable -- you can test all of them
- **Debugging**: When something goes wrong, you know exactly which state the user is in and which transition failed
- **Documentation**: The state machine diagram IS the documentation

---

## How does it work?

### Core concepts

```
  ┌─────────────────────────────────────────────────┐
  │  State Machine = States + Transitions + Guards   │
  │                                                  │
  │  State:      Where the system is now             │
  │  Trigger:    What event happened                 │
  │  Guard:      Condition that must be true         │
  │  Action:     Side effect performed               │
  │  Transition: State A ──(trigger + guard)──► State B │
  └─────────────────────────────────────────────────┘
```

### A simple example

```
  ┌──────────┐  insert coin   ┌──────────┐  push button  ┌──────────┐
  │  LOCKED  │ ──────────────►│  READY   │ ─────────────►│ VENDING  │
  └──────────┘                └──────────┘               └──────────┘
       ▲                           │                          │
       │         timeout           │       item dispensed     │
       └───────────────────────────┘          └───────────────┘
```

This vending machine has 3 states, 4 transitions, and clear rules. You cannot get a drink without inserting a coin first.

### Deterministic transitions

A critical property: given the same state + trigger + guard values, the result is always the same. There is no randomness, no "sometimes this, sometimes that."

```
  State: UNAUTHENTICATED
  Trigger: GET /login
  Guard: !request.accept_json
  ──────────────────────────────
  Result: ALWAYS → AUTH_PENDING (redirect to Google)
          Never anything else.
```

### Global transitions

Some transitions apply from many states. Instead of repeating them in every state definition, they are declared once as global:

```
  Global: logout
  From: any state except UNAUTHENTICATED, AUTH_PENDING
  Result: → UNAUTHENTICATED

  Global: session_timeout
  From: any state except UNAUTHENTICATED
  Result: → UNAUTHENTICATED
```

---

## How does volta-auth-proxy use it?

### The 8 states

```
  ┌─────────────────────┐
  │   UNAUTHENTICATED   │ ◄── No session. Must log in.
  └─────────┬───────────┘
            │ GET /login
            ▼
  ┌─────────────────────┐
  │    AUTH_PENDING      │ ◄── Waiting for OIDC callback.
  └─────────┬───────────┘
            │ GET /callback (success)
            ▼
       ┌────┴────┬──────────────┐
       ▼         ▼              ▼
  ┌─────────┐ ┌───────────┐ ┌──────────────┐
  │NO_TENANT│ │TENANT_SEL. │ │INVITE_CONSENT│
  └─────────┘ └─────┬─────┘ └──────┬───────┘
       │            │               │
       │            ▼               │
       │    ┌───────────────┐       │
       └───►│ AUTHENTICATED │◄──────┘
            └───────┬───────┘
                    │ tenant.suspended
                    ▼
            ┌─────────────────┐
            │TENANT_SUSPENDED │
            └─────────────────┘
```

### State definitions from `dsl/auth-machine.yaml`

| State | Description | Phase |
|-------|-------------|-------|
| `UNAUTHENTICATED` | No valid session | 1 |
| `AUTH_PENDING` | Redirected to IdP, waiting for callback | 1 |
| `INVITE_CONSENT` | Showing invitation consent screen | 1 |
| `TENANT_SELECT` | User has multiple tenants, must choose | 1 |
| `NO_TENANT` | Authenticated but no tenant membership | 1 |
| `AUTHENTICATED` | Fully authenticated, tenant confirmed, JWT available | 1 |
| `TENANT_SUSPENDED` | Current tenant is suspended | 1 |
| `MFA_REQUIRED` | Authenticated but MFA not completed (Phase 3) | 3 |

### Transition example: login flow

```yaml
# From auth-machine.yaml
UNAUTHENTICATED:
  transitions:
    login_browser:
      trigger: "GET /login"
      guard: "!request.accept_json"
      actions:
        - { type: side_effect, action: create_oidc_flow }
        - { type: http, action: redirect, target: google_authorize_url }
      next: AUTH_PENDING
```

This reads as: "When the system is in UNAUTHENTICATED and receives `GET /login` from a browser (not JSON), create an OIDC flow, redirect to Google, and move to AUTH_PENDING."

### Invariants that prevent bugs

The state machine declares [invariants](invariant.md) -- rules that must always hold:

```yaml
invariants:
  - id: no_deadlock
    rule: "Every non-terminal state MUST have at least one reachable outgoing transition"
  - id: reachable_auth
    rule: "From any state, there MUST exist a path to AUTHENTICATED or UNAUTHENTICATED"
  - id: logout_always_possible
    rule: "From any authenticated state, logout MUST be reachable"
```

### Java implementation

`AuthService.java` implements the state machine. The `authenticate()` method resolves the current state from the session, and route handlers implement individual transitions:

```java
// AuthService.java
public Optional<AuthPrincipal> authenticate(Context ctx) {
    // Read session cookie → look up session → determine current state
    // If no session: UNAUTHENTICATED
    // If session.valid && tenant.active: AUTHENTICATED
    // etc.
}
```

---

## Common mistakes and attacks

### Mistake 1: Implicit states

If your code has an auth check like `if (user != null && tenant != null && !suspended)`, you have an implicit state machine. The problem: nobody can see all the states or prove there are no gaps. volta makes every state explicit and named.

### Mistake 2: Missing transitions

If a user in TENANT_SELECT has no way to reach AUTHENTICATED (e.g., the "select" button is broken), they are deadlocked. The `no_deadlock` invariant prevents this.

### Mistake 3: No timeout on pending states

AUTH_PENDING has a 600-second timeout. Without it, a user who starts login but never completes the OIDC callback would stay in AUTH_PENDING forever, consuming resources.

### Attack: State confusion

An attacker might try to skip states -- e.g., calling `/auth/verify` (an AUTHENTICATED transition) while in UNAUTHENTICATED. Because volta checks the current state before every transition, this returns 401 immediately.

---

## Further reading

- [transition.md](transition.md) -- How transitions work in detail.
- [guard.md](guard.md) -- Conditions that control transitions.
- [invariant.md](invariant.md) -- Rules the state machine must satisfy.
- [dsl.md](dsl.md) -- The DSL that defines the state machine.
- [state.md](state.md) -- The OIDC state parameter (different concept).
