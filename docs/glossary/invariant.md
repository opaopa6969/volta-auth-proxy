# Invariant

[日本語版はこちら](invariant.ja.md)

---

## What is it?

An invariant is a rule that must ALWAYS be true, no matter what happens. It is not a guard on a single transition -- it is a global property of the entire system. If an invariant is ever violated, the system has a bug. Invariants catch design flaws before they become runtime bugs.

Think of it like the rules of physics in a building. "Every room must have at least one exit" is an invariant. It does not matter how the building is designed -- this rule must hold. If a room has no exit, it is a fire safety violation, period. You do not check this rule at the door; you verify it when the building is designed.

In volta-auth-proxy, invariants are declared in `dsl/auth-machine.yaml` as formal properties that the [state machine](state-machine.md) must satisfy. They prevent deadlocks, ensure reachability, and guarantee that all references are valid.

---

## Why does it matter?

Guards protect individual transitions. Invariants protect the entire system. Without invariants:

- **Deadlocks**: A user reaches a state with no outgoing transitions and is stuck forever
- **Unreachable states**: A state exists in the DSL but no transition leads to it (wasted code, confusing documentation)
- **Dangling references**: A transition references error code `FOOBAR` that does not exist in `errors.yaml`
- **Missing safety nets**: Authenticated users cannot log out because the logout transition was accidentally excluded from their state

---

## How does it work?

### Invariants vs Guards vs Constraints

```
  ┌────────────────────────────────────────────────────────────┐
  │ Guard:       Checked at RUNTIME, per TRANSITION            │
  │              "session.valid && tenant.active"               │
  │              If false → transition blocked                  │
  │                                                            │
  │ Constraint:  Checked at RUNTIME, per OPERATION             │
  │              "A tenant MUST have at least one OWNER"        │
  │              If violated → operation rejected (400 error)   │
  │                                                            │
  │ Invariant:   Checked at DESIGN TIME, GLOBALLY              │
  │              "Every state has at least one exit"            │
  │              If violated → the DSL has a bug                │
  └────────────────────────────────────────────────────────────┘
```

### Types of invariants

**Structural invariants** (about the shape of the state machine):
- Every state has outgoing transitions
- Every `next` value refers to a defined state
- No orphaned states

**Reachability invariants** (about paths through the machine):
- From any state, AUTHENTICATED or UNAUTHENTICATED is reachable
- Logout is reachable from every authenticated state

**Referential invariants** (about cross-file consistency):
- Every error code in actions exists in `errors.yaml`
- Every template name refers to an existing template

### How to verify invariants

Invariants can be verified by static analysis of the DSL files -- no running code needed:

```
  1. Parse auth-machine.yaml
  2. Build a directed graph of states and transitions
  3. For each invariant:
     ┌─────────────────────────────────────────────────────┐
     │ no_deadlock:                                        │
     │   For each non-terminal state S:                    │
     │     count outgoing transitions from S               │
     │     ASSERT count > 0                                │
     │                                                     │
     │ reachable_auth:                                     │
     │   For each state S:                                 │
     │     BFS/DFS from S                                  │
     │     ASSERT AUTHENTICATED or UNAUTHENTICATED in path │
     │                                                     │
     │ no_undefined_refs:                                  │
     │   For each transition T:                            │
     │     ASSERT T.next is in defined states              │
     │                                                     │
     │ error_codes_defined:                                │
     │   For each action A with error_ref:                 │
     │     ASSERT A.error_ref is in errors.yaml            │
     └─────────────────────────────────────────────────────┘
  4. If any assertion fails → DSL bug, fix before deploying
```

---

## How does volta-auth-proxy use it?

### Declared invariants in `dsl/auth-machine.yaml`

```yaml
invariants:
  - id: no_deadlock
    rule: "Every non-terminal state MUST have at least one reachable outgoing transition"

  - id: reachable_auth
    rule: "From any state, there MUST exist a path to AUTHENTICATED or UNAUTHENTICATED"

  - id: logout_always_possible
    rule: "From any authenticated state, logout MUST be reachable (via global_transitions)"

  - id: no_undefined_refs
    rule: "Every 'next' value MUST reference a defined state"

  - id: error_codes_defined
    rule: "Every error code used in actions MUST exist in dsl/errors.yaml"
```

### How `no_deadlock` caught a real bug

In version 1 of the DSL, INVITE_CONSENT had a `cancel` transition that went to `AUTHENTICATED_NO_TENANT` -- a state that did not exist. The `no_undefined_refs` invariant would have caught this. In version 2, the cancel transition was fixed to go to `NO_TENANT`.

Similarly, the `callback_nonce_invalid` transition was added in version 3 to fix a deadlock: without it, a valid state but invalid nonce would match no transition in AUTH_PENDING, leaving the user stuck.

### Constraints in `dsl/policy.yaml`

`policy.yaml` defines runtime constraints (a related but distinct concept):

```yaml
constraints:
  - id: last_owner
    rule: "A tenant MUST have at least one OWNER at all times"
    enforcement: "Block role change / removal if it would leave zero OWNERs"
    error: "LAST_OWNER_CANNOT_CHANGE"

  - id: promote_limit
    rule: "A user cannot promote another user above their own role"

  - id: concurrent_sessions
    rule: "A user can have at most MAX_CONCURRENT_SESSIONS active sessions"
    default: 5
```

These are invariants of the data model, enforced at runtime by Java code.

### Invariant verification in practice

```
  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
  │ Developer     │     │ CI Pipeline  │     │ Production   │
  │ edits DSL     │────►│ Verify       │────►│ Deploy       │
  │               │     │ invariants   │     │              │
  └──────────────┘     └──────┬───────┘     └──────────────┘
                              │
                        FAIL? │ Block deploy.
                              │ "no_deadlock violated:
                              │  MFA_REQUIRED has no
                              │  outgoing transitions"
```

---

## Common mistakes and attacks

### Mistake 1: Invariants without enforcement

Writing invariants in comments but never checking them is like having fire exits on the blueprint but not building them. volta declares invariants in the DSL so they can be verified automatically.

### Mistake 2: Too few invariants

If you only check `no_deadlock`, you might miss that error codes reference nonexistent entries. Each category of invariant catches a different class of bug.

### Mistake 3: Confusing invariants with guards

An invariant is "this must always be true." A guard is "this must be true for this specific transition." If you put invariant-level checks in guards, you are checking at the wrong level -- too late, too scattered.

### Mistake 4: Not updating invariants when the system evolves

When Phase 3 adds MFA_REQUIRED, the `reachable_auth` invariant must still hold. If MFA_REQUIRED has no path to AUTHENTICATED, the invariant catches the bug immediately.

---

## Further reading

- [state-machine.md](state-machine.md) -- The machine that invariants constrain.
- [guard.md](guard.md) -- Per-transition conditions (different from invariants).
- [dsl.md](dsl.md) -- Where invariants are declared.
- [transition.md](transition.md) -- The transitions that invariants verify.
- [rbac.md](rbac.md) -- Role-based constraints defined in `policy.yaml`.
