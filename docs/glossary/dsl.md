# DSL (Domain-Specific Language)

[日本語版はこちら](dsl.ja.md)

---

## What is it?

A Domain-Specific Language (DSL) is a small, purpose-built language designed to solve problems in one specific area. Unlike general-purpose languages such as Java or Python that can do anything, a DSL is intentionally limited so it can do one thing extremely well. SQL is a DSL for databases. Regular expressions are a DSL for pattern matching. HTML is a DSL for describing web pages.

Think of it like a TV remote control versus a universal remote. A TV remote has exactly the buttons you need -- power, volume, channel -- and anyone can pick it up and use it. A universal remote can control everything in your house, but you need a manual to figure it out. A DSL is the TV remote: fewer buttons, but exactly the right ones.

volta-auth-proxy defines ALL of its authentication behavior in 4 YAML DSL files. These files are not just configuration -- they are the authoritative specification of how the entire system works. If a behavior is not described in the DSL, it does not exist.

---

## Why does it matter?

Without a DSL, authentication logic scatters across dozens of Java files, Javalin routes, database migrations, and configuration properties. Bugs hide in corners. Nobody can see the full picture.

- **Readability**: A new developer can read 4 YAML files and understand the entire auth system in an afternoon
- **Single source of truth**: No conflicting definitions in code, docs, and config
- **Testability**: The DSL is structured enough to generate tests automatically
- **AI-friendliness**: AI tools can parse YAML and generate documentation, test cases, and code from the same files
- **Auditability**: Security reviewers can audit 4 files instead of 20+ Java classes

---

## How does it work?

### The spectrum of languages

```
  General-purpose                                    Domain-specific
  ◄────────────────────────────────────────────────────────────────►
  Java       Python      Terraform     SQL      regex     volta DSL
  (anything) (anything)  (infra)       (data)   (match)   (auth)
```

### Internal vs External DSL

```
  External DSL:
  ┌───────────────────────────────────────────┐
  │ Has its own syntax and parser.            │
  │ Examples: SQL, regex, Terraform HCL       │
  │ volta: YAML files parsed at startup       │
  └───────────────────────────────────────────┘

  Internal DSL:
  ┌───────────────────────────────────────────┐
  │ Hosted inside a general-purpose language. │
  │ Examples: RSpec (Ruby), Gradle (Groovy)   │
  │ volta: does NOT use this approach         │
  └───────────────────────────────────────────┘
```

volta uses an **external DSL** written in YAML. The Java application reads and interprets the YAML files at startup. The DSL itself has:

- **Context variables**: Typed variables like `session.valid`, `tenant.active`, `membership.role`
- **Guard expressions**: CEL-like boolean conditions, e.g., `"session.valid && !tenant.suspended"`
- **Action types**: `side_effect`, `http`, `audit`, `guard_check`
- **Transition rules**: State A + trigger + guard = State B + actions

### Why YAML and not a custom syntax?

```
  Custom syntax:          YAML:
  ┌──────────────────┐    ┌──────────────────────────────┐
  │ state AUTH {      │    │ states:                      │
  │   on login ->     │    │   AUTHENTICATED:             │
  │     if valid      │    │     transitions:             │
  │     goto DONE     │    │       forward_auth:          │
  │ }                 │    │         guard: "session.valid"│
  └──────────────────┘    │         next: AUTHENTICATED   │
                          └──────────────────────────────┘
  Needs custom parser      Standard YAML parser (SnakeYAML)
  No editor support        Syntax highlighting everywhere
  Learning curve            Already known by most devs
```

---

## How does volta-auth-proxy use it?

### The 4 DSL files

```
  dsl/
  ├── auth-machine.yaml   ← State machine: 8 states, all transitions
  ├── protocol.yaml       ← ForwardAuth contract, JWT spec, API endpoints
  ├── policy.yaml         ← Role hierarchy, permissions, constraints
  └── errors.yaml         ← Every error code, message, recovery action
```

Each file has a specific responsibility:

| File | Defines | Example |
|------|---------|---------|
| `auth-machine.yaml` | States, transitions, guards, actions | `UNAUTHENTICATED → AUTH_PENDING` via `GET /login` |
| `protocol.yaml` | Gateway-to-app contract | `X-Volta-User-Id` header, JWT claims, API endpoints |
| `policy.yaml` | Authorization rules | `OWNER > ADMIN > MEMBER > VIEWER` |
| `errors.yaml` | Error responses | `SESSION_EXPIRED: 401, "Your session has expired"` |

### Cross-referencing between files

The DSL files reference each other explicitly:

```yaml
# auth-machine.yaml
errors_ref: "dsl/errors.yaml"   # Error codes come from errors.yaml

# protocol.yaml
errors_ref: "dsl/errors.yaml"   # Same error codes

# auth-machine.yaml transition
actions:
  - { type: http, action: json_error, error_ref: AUTHENTICATION_REQUIRED }
    # AUTHENTICATION_REQUIRED is defined in errors.yaml
```

### Guard expression syntax

The DSL defines its own mini-expression language for guards:

```yaml
# CEL-like syntax defined in auth-machine.yaml
# Operators: &&, ||, !, ==, !=, >, <, >=, <=, in
# Variables: from context section (session.*, user.*, membership.*, etc.)

guard: "session.valid && tenant.active"
guard: "membership.role in ['ADMIN', 'OWNER']"
guard: "oidc_flow.state_valid && oidc_flow.nonce_valid && oidc_flow.email_verified"
```

### How the Java code uses the DSL

The DSL files are the specification. The Java code (`AuthService.java`, `Main.java`) is the implementation that follows the specification. When there is a discrepancy between the DSL and the code, the DSL is correct and the code has a bug.

```
  DSL (specification)              Java (implementation)
  ┌──────────────────────┐        ┌──────────────────────────────┐
  │ auth-machine.yaml    │───────►│ AuthService.java             │
  │   transition:        │  reads │   authenticate()             │
  │     guard: "..."     │        │   issueSession()             │
  │     next: STATE      │        │   verify()                   │
  │     actions: [...]   │        │                              │
  └──────────────────────┘        └──────────────────────────────┘
  │                                │
  │  errors.yaml         ───────►  │  ApiException.java          │
  │  policy.yaml         ───────►  │  AppConfig + AppRegistry    │
  │  protocol.yaml       ───────►  │  HttpSupport.java           │
```

---

## Common mistakes and attacks

### Mistake 1: Treating DSL files as "just config"

DSL files are not config. Config is `PORT=8080`. DSL files define behavior: states, transitions, guards, error messages. If you change a guard expression in `auth-machine.yaml`, you are changing the security model, not tuning a setting.

### Mistake 2: Duplicating logic outside the DSL

If you add a permission check in Java code that is not reflected in `policy.yaml`, you now have two sources of truth. When they disagree, bugs and security holes appear. Always update the DSL first, then implement in code.

### Mistake 3: Making the DSL too powerful

A DSL should be limited by design. If you add loops, recursion, and general computation, you have built a general-purpose language with bad tooling. volta's DSL intentionally has no loops, no functions (until Phase 2), and no dynamic evaluation.

### Mistake 4: No versioning

volta's DSL files include explicit `version` fields. When the DSL evolves, the version increments. This prevents old code from misinterpreting new DSL constructs.

---

## Further reading

- [state-machine.md](state-machine.md) -- The state machine defined by `auth-machine.yaml`.
- [guard.md](guard.md) -- Guard expressions in the DSL.
- [transition.md](transition.md) -- How transitions are defined in the DSL.
- [single-source-of-truth.md](single-source-of-truth.md) -- Why the DSL is the single source of truth.
- [yaml.md](yaml.md) -- The data format used by the DSL.
- [invariant.md](invariant.md) -- Formal rules the DSL enforces.
