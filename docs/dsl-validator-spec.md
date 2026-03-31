# DSL Validator Specification

Validates the volta-auth-proxy auth DSL across four YAML files for structural correctness, cross-file reference integrity, state machine invariants, guard expression validity, and completeness.

**Version:** 1
**DSL files validated:**

| File | Purpose |
|------|---------|
| `dsl/auth-machine.yaml` | State machine: states, transitions, guards, actions, context |
| `dsl/protocol.yaml` | Gateway protocol: ForwardAuth, JWT, API, content negotiation |
| `dsl/policy.yaml` | Authorization: roles, permissions, constraints, tenant isolation |
| `dsl/errors.yaml` | Error code registry (single source of truth) |

---

## 1. Structural Validation (Per File)

### 1.1 errors.yaml

| Field | Type | Required | Rule |
|-------|------|----------|------|
| `version` | int | yes | Must be a positive integer |
| `errors` | map | yes | Must contain at least one entry |
| `errors.<CODE>` | map | yes | Key must match `^[A-Z][A-Z0-9_]+$` |
| `errors.<CODE>.status` | int | yes | Must be a valid HTTP status (400-599) |
| `errors.<CODE>.message` | map | yes | Must contain at least `en` key |
| `errors.<CODE>.message.en` | string | yes | Non-empty string |
| `errors.<CODE>.recovery` | map | no | If present, keys must be valid recovery contexts |
| `errors.<CODE>.headers` | map | no | If present, keys and values must be strings |

**Checks:**

- STRUCT-E01: No duplicate error code names (map keys are unique by definition, but warn if the YAML parser silently overwrites).
- STRUCT-E02: Every error code has `status` and `message.en`.
- STRUCT-E03: `status` is an integer in range 400-599.

### 1.2 auth-machine.yaml

| Field | Type | Required | Rule |
|-------|------|----------|------|
| `version` | int | yes | Positive integer |
| `errors_ref` | string | yes | File path to errors registry |
| `initial_state` | string | yes | Must reference a defined state |
| `context` | map | yes | Typed variable tree |
| `merge_strategy` | enum | yes | One of: `append`, `override` |
| `global_transitions` | map | yes | At least one global transition |
| `invariants` | list | no | Each entry has `id` and `rule` |
| `states` | map | yes | At least one state defined |
| `audit_events` | list | yes | List of event name strings |

**Per state:**

| Field | Type | Required | Rule |
|-------|------|----------|------|
| `phase` | int | yes | Positive integer |
| `description` | string | yes | Non-empty |
| `timeout` | map | no | If present, must have `duration_seconds`, `next` |
| `transitions` | map | yes (non-terminal) | At least one transition for non-terminal states |

**Per transition:**

| Field | Type | Required | Rule |
|-------|------|----------|------|
| `trigger` | string | yes | HTTP method+path pattern or `automatic` |
| `guard` | string | no | CEL-like expression |
| `priority` | int | conditional | Required when multiple transitions share the same trigger |
| `actions` | list | no | Each action has `type` |
| `next` | string | conditional | Required unless `next_if` is present |
| `next_if` | list | no | Conditional branching; each entry has `guard` and `next` |

**Per action:**

| Field | Type | Required | Rule |
|-------|------|----------|------|
| `type` | enum | yes | One of: `side_effect`, `http`, `audit`, `guard_check` |
| `action` | string | conditional | Required for `side_effect` and `http` types |
| `event` | string | conditional | Required for `audit` type |
| `check` | string | conditional | Required for `guard_check` type |
| `error_ref` | string | no | If present, must reference a defined error code |
| `template` | string | no | If present, non-empty string |
| `params` | map | no | Free-form parameters |

**Per global transition:**

| Field | Type | Required | Rule |
|-------|------|----------|------|
| `trigger` | string | yes | HTTP method+path or `automatic` |
| `guard` | string | no | CEL-like expression |
| `from_except` | list | yes | List of state names excluded from this transition |
| `actions` | list | no | Same rules as transition actions |
| `next` | string | yes | Must reference a defined state |

**Per context variable:**

| Field | Type | Required | Rule |
|-------|------|----------|------|
| key | string | yes | Valid identifier matching `^[a-z_][a-z0-9_]*$` |
| value (type) | string | yes | One of: `bool`, `int`, `string`, `string?`, `uuid`, `uuid?`, `enum[...]` |

**Checks:**

- STRUCT-M01: All required top-level fields present.
- STRUCT-M02: No duplicate state names.
- STRUCT-M03: No duplicate transition names within a state.
- STRUCT-M04: Every transition has either `next` or `next_if` (not neither, not both unless `next_if` is used for branching with a fallback).
- STRUCT-M05: Every action has a valid `type`.
- STRUCT-M06: `audit` actions have an `event` field.
- STRUCT-M07: `trigger` format is `METHOD /path` or `automatic` or `any`.
- STRUCT-M08: `priority` is a positive integer when present.
- STRUCT-M09: Context variable types match the type grammar: `bool | int | string | uuid | enum[V1, V2, ...] | <type>?` (nullable suffix).
- STRUCT-M10: `timeout.duration_seconds` is a positive integer.
- STRUCT-M11: Every `next_if` entry has both `guard` and `next`.

### 1.3 protocol.yaml

| Field | Type | Required | Rule |
|-------|------|----------|------|
| `version` | int | yes | Positive integer |
| `errors_ref` | string | yes | File path |
| `forward_auth` | map | yes | ForwardAuth contract |
| `jwt` | map | yes | JWT specification |
| `content_negotiation` | map | yes | Response format rules |
| `api` | map | yes | Internal API definition |

**Checks:**

- STRUCT-P01: All required top-level fields present.
- STRUCT-P02: `jwt.algorithm` is a recognized algorithm string.
- STRUCT-P03: `jwt.expiry_seconds` is a positive integer.
- STRUCT-P04: `jwt.claims` each have a `type`.
- STRUCT-P05: API endpoints each have `method`, `path`, `auth`.
- STRUCT-P06: `method` is a valid HTTP method (`GET`, `POST`, `PATCH`, `PUT`, `DELETE`).
- STRUCT-P07: Response error codes reference known error codes.

### 1.4 policy.yaml

| Field | Type | Required | Rule |
|-------|------|----------|------|
| `version` | int | yes | Positive integer |
| `roles` | map | yes | Must have `hierarchy` list |
| `permissions` | map | yes | One entry per role in hierarchy |
| `constraints` | list | yes | Policy constraints |
| `tenant_isolation` | map | yes | Isolation rules |

**Per constraint:**

| Field | Type | Required | Rule |
|-------|------|----------|------|
| `id` | string | yes | Unique identifier |
| `rule` | string | yes | Non-empty |
| `enforcement` | string | yes | Non-empty |
| `error` | string | no | If present, must reference a defined error code |

**Checks:**

- STRUCT-L01: All required top-level fields present.
- STRUCT-L02: Role hierarchy is a non-empty list of unique strings.
- STRUCT-L03: Every role in `permissions` exists in `roles.hierarchy`.
- STRUCT-L04: No duplicate constraint IDs.
- STRUCT-L05: `inherits` references a role that exists in the hierarchy.
- STRUCT-L06: `inherits` references a role lower in the hierarchy (no circular or upward inheritance).

---

## 2. Cross-File Reference Integrity

### 2.1 Error Code References

All error codes used across the DSL must exist in `errors.yaml`.

| Check ID | Source File | Field | Rule |
|----------|------------|-------|------|
| XREF-01 | auth-machine.yaml | `actions[].error_ref` | Every `error_ref` value must be a key in `errors.yaml` `errors` map |
| XREF-02 | policy.yaml | `constraints[].error` | Every constraint `error` value must be a key in `errors.yaml` `errors` map |
| XREF-03 | policy.yaml | `tenant_isolation.rules[].error` | Every isolation rule `error` must exist in `errors.yaml` |
| XREF-04 | protocol.yaml | `forward_auth.response_error.*.code` | Every response error code must exist in `errors.yaml` |

### 2.2 errors_ref File Path

| Check ID | Source File | Rule |
|----------|------------|------|
| XREF-05 | auth-machine.yaml | `errors_ref` must point to a valid file path (`dsl/errors.yaml`) |
| XREF-06 | protocol.yaml | `errors_ref` must point to a valid file path (`dsl/errors.yaml`) |

### 2.3 Role References

| Check ID | Source File | Rule |
|----------|------------|------|
| XREF-07 | auth-machine.yaml | Enum values in `context.membership.role` must match `policy.yaml` `roles.hierarchy` |
| XREF-08 | auth-machine.yaml | Role literals in guards (e.g., `'ADMIN'`, `'OWNER'`) must exist in `policy.yaml` `roles.hierarchy` |

### 2.4 Audit Event References

| Check ID | Source File | Rule |
|----------|------------|------|
| XREF-09 | auth-machine.yaml / policy.yaml | Audit events listed in `policy.yaml` `audit.events` should be a superset of `auth-machine.yaml` `audit_events` |

---

## 3. State Machine Invariants

### 3.1 Initial State

| Check ID | Rule |
|----------|------|
| SM-01 | `initial_state` value must be a key in the `states` map |

### 3.2 Reachability

| Check ID | Rule |
|----------|------|
| SM-02 | Every defined state must be reachable from `initial_state` via some sequence of transitions (including global transitions and `next_if` branches) |

**Algorithm:** Build a directed graph from all transitions (`next` and `next_if[].next` values as edges, plus global transitions applied to eligible states). Perform BFS/DFS from `initial_state`. Any state not visited is unreachable.

### 3.3 No Deadlocks

| Check ID | Rule |
|----------|------|
| SM-03 | Every non-terminal state must have at least one outgoing transition (either a state-level transition or an applicable global transition) |

**Terminal states:** A state is terminal if it is explicitly marked as terminal or if it is a natural sink (only self-loops). For this DSL, `UNAUTHENTICATED` is not terminal (it has outgoing transitions). No state is explicitly terminal. Therefore: every state must have at least one outgoing transition that leads to a different state, OR be reachable by a global transition that leads elsewhere.

### 3.4 Next State References

| Check ID | Rule |
|----------|------|
| SM-04 | Every `next` value in transitions must reference a state defined in the `states` map |
| SM-05 | Every `next` value in `next_if` entries must reference a state defined in the `states` map |
| SM-06 | Every `next` value in `global_transitions` must reference a defined state |
| SM-07 | Every `timeout.next` value must reference a defined state |

### 3.5 Global Transition Coverage

| Check ID | Rule |
|----------|------|
| SM-08 | For every state that represents an authenticated user (all states except `UNAUTHENTICATED`), at least one global logout transition must apply (i.e., the state must not appear in all logout transitions' `from_except` lists) |

**Authenticated states** (by convention): All states except `UNAUTHENTICATED`. The `from_except` lists of `logout_browser` and `logout_api` must not collectively exclude any authenticated state other than `AUTH_PENDING`.

### 3.6 Priority Completeness

| Check ID | Rule |
|----------|------|
| SM-09 | Within each state, if two or more transitions share the same `trigger` value, every one of those transitions must have a `priority` field |
| SM-10 | Priority values for same-trigger transitions must be unique (no ties) |
| SM-11 | Priority values should form a contiguous sequence starting from 1 (warning, not error) |

### 3.7 from_except Validity

| Check ID | Rule |
|----------|------|
| SM-12 | Every state name in `global_transitions[].from_except` must reference a defined state |

---

## 4. Guard Expression Validation

### 4.1 Variable Resolution

| Check ID | Rule |
|----------|------|
| GUARD-01 | Every dotted variable reference in a guard expression (e.g., `session.valid`, `tenant.suspended`) must resolve to a variable defined in `context` |
| GUARD-02 | Nested references must match the context tree structure (e.g., `oidc_flow.state_valid` must match `context.oidc_flow.state_valid`) |

**Extraction:** Parse guard strings to extract all dot-separated identifiers. Identifiers are sequences of `[a-zA-Z_][a-zA-Z0-9_]*` separated by `.`, not enclosed in quotes.

### 4.2 Operator Validation

| Check ID | Rule |
|----------|------|
| GUARD-03 | Only the following operators are permitted: `&&`, `||`, `!`, `==`, `!=`, `>`, `<`, `>=`, `<=`, `in` |
| GUARD-04 | No assignment operators (`=`, `+=`, `-=`, etc.) |

### 4.3 Syntax Checks

| Check ID | Rule |
|----------|------|
| GUARD-05 | Balanced parentheses: every `(` has a matching `)` |
| GUARD-06 | Balanced brackets: every `[` has a matching `]` |
| GUARD-07 | Balanced quotes: every `'` or `"` has a matching close quote |
| GUARD-08 | No empty guard strings |
| GUARD-09 | No dangling boolean operators (e.g., `&&` at end of expression, `|| ||`) |

### 4.4 Type Compatibility (Warnings)

| Check ID | Rule | Severity |
|----------|------|----------|
| GUARD-10 | Boolean context variables used with `==`/`!=` against non-boolean literals | warning |
| GUARD-11 | `in` operator right-hand side should be an array literal `[...]` or a known list variable | warning |
| GUARD-12 | Numeric comparisons (`>`, `<`, `>=`, `<=`) should involve `int` typed context variables | warning |

---

## 5. Completeness Checks

### 5.1 Audit Event Coverage

| Check ID | Rule | Severity |
|----------|------|----------|
| COMP-01 | Every event listed in `auth-machine.yaml` `audit_events` must be emitted by at least one transition's `audit` action | error |
| COMP-02 | Every `audit` action event in transitions must be listed in `audit_events` | error |

### 5.2 Context Variable Usage

| Check ID | Rule | Severity |
|----------|------|----------|
| COMP-03 | Every leaf variable defined in `context` should be referenced by at least one guard expression (across all transitions, global transitions, and `next_if` guards) | warning |

### 5.3 Error Code Usage

| Check ID | Rule | Severity |
|----------|------|----------|
| COMP-04 | Every error code defined in `errors.yaml` should be referenced by at least one DSL file | warning |

### 5.4 Transition Coverage

| Check ID | Rule | Severity |
|----------|------|----------|
| COMP-05 | Every state should be the target (`next`) of at least one transition (other than `initial_state`, which is the entry point) | warning |

---

## 6. Validation Output Format

The validator produces a structured report. Each finding has a severity, check ID, message, and location.

### Severity Levels

| Level | Meaning |
|-------|---------|
| `ERROR` | DSL is invalid. Must be fixed before code generation or deployment. |
| `WARNING` | Potential issue. May indicate dead code or missing coverage. |
| `INFO` | Informational. Summary statistics. |

### Output Schema

```
{
  "valid": boolean,
  "summary": {
    "errors": int,
    "warnings": int,
    "info": int
  },
  "findings": [
    {
      "severity": "ERROR" | "WARNING" | "INFO",
      "check_id": "string",
      "message": "string",
      "file": "string",
      "path": "string (dot-notation to field, e.g. states.AUTH_PENDING.transitions.callback_error.actions[0].error_ref)"
    }
  ]
}
```

The `valid` field is `true` only when `errors` count is zero.

---

## 7. Example Validation Output

### Passing Validation

```
DSL Validator v1 — volta-auth-proxy
====================================

Files:
  dsl/auth-machine.yaml  v3  (8 states, 32 transitions, 3 global)
  dsl/protocol.yaml      v2  (12 API endpoints, 10 JWT claims)
  dsl/policy.yaml        v1  (4 roles, 6 constraints)
  dsl/errors.yaml        v2  (17 error codes)

[PASS]  STRUCT-E01  No duplicate error codes
[PASS]  STRUCT-E02  All error codes have status and message.en
[PASS]  STRUCT-E03  All error statuses in range 400-599
[PASS]  STRUCT-M01  auth-machine.yaml has all required top-level fields
[PASS]  STRUCT-M02  No duplicate state names
[PASS]  STRUCT-M03  No duplicate transition names within any state
[PASS]  STRUCT-M04  All transitions have next or next_if
[PASS]  STRUCT-M05  All actions have valid type
[PASS]  STRUCT-M06  All audit actions have event field
[PASS]  STRUCT-M07  All triggers match expected format
[PASS]  STRUCT-M08  All same-trigger transitions have priority
[PASS]  STRUCT-M09  All context variable types are valid
[PASS]  STRUCT-P01  protocol.yaml has all required top-level fields
[PASS]  STRUCT-L01  policy.yaml has all required top-level fields
[PASS]  STRUCT-L02  Role hierarchy is valid
[PASS]  STRUCT-L03  All permission roles exist in hierarchy
[PASS]  STRUCT-L04  No duplicate constraint IDs
[PASS]  STRUCT-L05  All inherits references are valid
[PASS]  XREF-01   All error_ref in auth-machine.yaml exist in errors.yaml
[PASS]  XREF-02   All constraint errors in policy.yaml exist in errors.yaml
[PASS]  XREF-03   All tenant_isolation errors in policy.yaml exist in errors.yaml
[PASS]  XREF-04   All protocol.yaml response error codes exist in errors.yaml
[PASS]  XREF-05   auth-machine.yaml errors_ref points to valid file
[PASS]  XREF-06   protocol.yaml errors_ref points to valid file
[PASS]  XREF-07   Context membership.role enum matches policy.yaml hierarchy
[PASS]  SM-01     initial_state UNAUTHENTICATED is defined
[PASS]  SM-02     All 8 states reachable from UNAUTHENTICATED
[PASS]  SM-03     No deadlocked states (all non-terminal states have outgoing transitions)
[PASS]  SM-04     All transition next values reference defined states
[PASS]  SM-05     All next_if next values reference defined states
[PASS]  SM-06     All global_transition next values reference defined states
[PASS]  SM-07     All timeout next values reference defined states
[PASS]  SM-08     All authenticated states are covered by logout global transitions
[PASS]  SM-09     All same-trigger transitions have priority
[PASS]  SM-10     No duplicate priorities for same-trigger groups
[PASS]  SM-12     All from_except state names are defined
[PASS]  GUARD-01  All guard variables resolve to context definitions
[PASS]  GUARD-03  All operators are valid CEL-like operators
[PASS]  GUARD-05  All parentheses balanced
[PASS]  GUARD-06  All brackets balanced
[PASS]  GUARD-07  All quotes balanced
[PASS]  GUARD-08  No empty guard strings
[PASS]  COMP-01   All declared audit_events are emitted by at least one transition
[PASS]  COMP-02   All emitted audit events are declared in audit_events

[WARN]  COMP-03   Context variable 'config.support_contact' is never referenced in any guard
                   at: dsl/auth-machine.yaml -> context.config.support_contact
[WARN]  COMP-03   Context variable 'request.return_to' is never referenced in any guard
                   at: dsl/auth-machine.yaml -> context.request.return_to
                   (note: used in action template interpolation, not in guards)
[WARN]  COMP-04   Error code 'INVITATION_EXPIRED' is not referenced by any DSL file
                   at: dsl/errors.yaml -> errors.INVITATION_EXPIRED
[WARN]  COMP-04   Error code 'INVITATION_EXHAUSTED' is not referenced by any DSL file
                   at: dsl/errors.yaml -> errors.INVITATION_EXHAUSTED
[WARN]  COMP-04   Error code 'RATE_LIMITED' is not referenced by any DSL file
                   at: dsl/errors.yaml -> errors.RATE_LIMITED
[WARN]  COMP-04   Error code 'INTERNAL_ERROR' is not referenced by any DSL file
                   at: dsl/errors.yaml -> errors.INTERNAL_ERROR
[WARN]  COMP-04   Error code 'FORBIDDEN' is not referenced by any DSL file
                   at: dsl/errors.yaml -> errors.FORBIDDEN
[WARN]  COMP-04   Error code 'SESSION_REVOKED' is not referenced by any DSL file
                   at: dsl/errors.yaml -> errors.SESSION_REVOKED

[INFO]  SM-11     Priority sequence in AUTHENTICATED for trigger "GET /auth/verify":
                   [1, 2, 3, 4] — contiguous starting from 1

====================================
Result: VALID (0 errors, 8 warnings)
```

### Failing Validation

```
DSL Validator v1 — volta-auth-proxy
====================================

Files:
  dsl/auth-machine.yaml  v3  (8 states, 32 transitions, 3 global)
  dsl/protocol.yaml      v2
  dsl/policy.yaml        v1
  dsl/errors.yaml        v2  (17 error codes)

[FAIL]  STRUCT-M04  Transition 'callback_success' has neither next nor next_if
                    at: dsl/auth-machine.yaml -> states.AUTH_PENDING.transitions.callback_success

[FAIL]  XREF-01   error_ref 'OIDC_TIMEOUT' does not exist in errors.yaml
                   at: dsl/auth-machine.yaml -> states.AUTH_PENDING.transitions.callback_error.actions[0].error_ref
                   defined error codes: AUTHENTICATION_REQUIRED, SESSION_EXPIRED, AUTH_FAILED, ...

[FAIL]  SM-01     initial_state 'INIT' is not defined in states
                   at: dsl/auth-machine.yaml -> initial_state
                   defined states: UNAUTHENTICATED, AUTH_PENDING, INVITE_CONSENT, ...

[FAIL]  SM-02     State 'ACCOUNT_LOCKED' is unreachable from initial_state
                   at: dsl/auth-machine.yaml -> states.ACCOUNT_LOCKED
                   hint: no transition targets this state

[FAIL]  SM-03     State 'NO_TENANT' has no outgoing transitions and is not terminal
                   at: dsl/auth-machine.yaml -> states.NO_TENANT
                   hint: add at least one transition or mark as terminal

[FAIL]  SM-04     Transition next value 'MFA_REQUIRED' is not a defined state
                   at: dsl/auth-machine.yaml -> states.AUTHENTICATED.transitions.login_mfa.next
                   defined states: UNAUTHENTICATED, AUTH_PENDING, ...

[FAIL]  SM-09     Transitions sharing trigger "GET /callback" have inconsistent priority:
                   callback_error has priority 1, callback_state_invalid has NO priority
                   at: dsl/auth-machine.yaml -> states.AUTH_PENDING.transitions

[FAIL]  GUARD-01  Guard variable 'mfa.totp_valid' not found in context
                   at: dsl/auth-machine.yaml -> states.AUTHENTICATED.transitions.verify_mfa.guard
                   defined context roots: session, user, membership, tenant, invite, oidc_flow, request, config, target_session

[FAIL]  GUARD-05  Unbalanced parentheses in guard expression
                   at: dsl/auth-machine.yaml -> states.AUTH_PENDING.transitions.callback_success.next_if[0].guard
                   expression: "invite.present && (invite.valid"

[FAIL]  GUARD-03  Invalid operator '===' in guard expression
                   at: dsl/auth-machine.yaml -> states.AUTHENTICATED.transitions.forward_auth.guard
                   expression: "session.valid === true"
                   valid operators: &&, ||, !, ==, !=, >, <, >=, <=, in

[FAIL]  COMP-01   Audit event 'INVITATION_ACCEPTED' is declared but never emitted
                   at: dsl/auth-machine.yaml -> audit_events
                   hint: no transition has { type: audit, event: INVITATION_ACCEPTED }

[FAIL]  COMP-02   Audit event 'USER_DELETED' is emitted but not declared in audit_events
                   at: dsl/auth-machine.yaml -> states.AUTHENTICATED.transitions.delete_user.actions[1]
                   hint: add USER_DELETED to audit_events list

[FAIL]  XREF-02   Constraint error 'QUOTA_EXCEEDED' does not exist in errors.yaml
                   at: dsl/policy.yaml -> constraints[5].error
                   defined error codes: AUTHENTICATION_REQUIRED, SESSION_EXPIRED, ...

====================================
Result: INVALID (13 errors, 0 warnings)
```

---

## 8. Validation Execution Order

The validator runs checks in dependency order. Early failures may prevent later checks from running.

```
Phase 1: Parse
  Load and parse all 4 YAML files.
  Abort if any file is missing or has YAML syntax errors.

Phase 2: Structural validation (per file, parallel)
  STRUCT-E*  errors.yaml
  STRUCT-M*  auth-machine.yaml
  STRUCT-P*  protocol.yaml
  STRUCT-L*  policy.yaml

Phase 3: Cross-file references (requires Phase 2 pass)
  XREF-*    All cross-file reference checks

Phase 4: State machine invariants (requires Phase 2 pass)
  SM-*      Reachability, deadlocks, next refs, priority, global coverage

Phase 5: Guard validation (requires Phase 2 pass)
  GUARD-*   Variable resolution, operators, syntax

Phase 6: Completeness (requires Phases 2-5 pass)
  COMP-*    Audit coverage, context usage, error usage
```

Phases 2-5 run even if a sibling phase has errors (so all issues are surfaced in one run). Phase 6 completeness checks run regardless but their results are only meaningful if earlier phases pass.

---

## 9. Guard Expression Grammar

The validator parses guard expressions according to this grammar (simplified PEG notation):

```
Expression  <- OrExpr
OrExpr      <- AndExpr ('||' AndExpr)*
AndExpr     <- NotExpr ('&&' NotExpr)*
NotExpr     <- '!' NotExpr / Comparison
Comparison  <- Primary (CompOp Primary)?
CompOp      <- '==' / '!=' / '>=' / '<=' / '>' / '<' / 'in'
Primary     <- '(' Expression ')'
             / ArrayLiteral
             / Literal
             / Variable
ArrayLiteral <- '[' (Literal (',' Literal)*)? ']'
Variable    <- Identifier ('.' Identifier)*
Identifier  <- [a-zA-Z_][a-zA-Z0-9_]*
Literal     <- 'true' / 'false'
             / Integer
             / StringLiteral
Integer     <- [0-9]+
StringLiteral <- "'" [^']* "'"
```

The validator does not evaluate guard expressions; it only checks syntactic and referential correctness.

---

## 10. Implementation Notes

- The validator should be runnable as a standalone CLI: `volta-dsl-validate [--dir dsl/]`
- Exit code 0 on pass, exit code 1 on any errors.
- JSON output mode: `--format json` for CI integration.
- Human-readable output is the default (as shown in examples above).
- The validator should be integrated into CI to run on every change to `dsl/` files.
- Consider running the validator as a pre-commit hook.

### File Discovery

The validator locates files relative to a base directory:

```
<base>/auth-machine.yaml
<base>/protocol.yaml
<base>/policy.yaml
<base>/errors.yaml
```

Default base: `dsl/` relative to repository root.

### Error Accumulation

The validator accumulates all findings rather than failing on the first error. This allows developers to fix multiple issues in a single pass.
