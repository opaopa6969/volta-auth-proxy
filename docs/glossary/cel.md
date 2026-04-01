# CEL (Common Expression Language)

[日本語版はこちら](cel.ja.md)

---

## What is it?

CEL (Common Expression Language) is a lightweight expression language designed by Google for evaluating conditions. It lets you write short, readable rules like `request.auth.role == "OWNER"` or `user.email.endsWith("@company.com")` and evaluate them at runtime without compiling code.

Think of it like a spreadsheet formula. In Excel, you write `=IF(A1 > 100, "big", "small")` and the spreadsheet evaluates it instantly. CEL is the same idea, but for security rules and access policies. You write a human-readable condition, and the system evaluates it against the current request to decide "allow" or "deny."

CEL was originally created for Google's infrastructure -- it powers access policies in Google Cloud IAM, Firebase Security Rules, and Kubernetes admission control. It is designed to be safe: no loops, no side effects, no file access. A CEL expression can only read data and return a value. This makes it ideal for security policies where you cannot afford a runaway expression crashing your server.

---

## Why does it matter?

Security rules need to be configurable without recompiling code. Hard-coding rules like `if (user.role == "ADMIN")` works for simple cases, but as policies grow more complex, you need a way to express them declaratively -- outside the application code.

Options include:

| Approach | Pros | Cons |
|----------|------|------|
| Hard-coded in Java | Fast, type-safe | Requires recompile and redeploy |
| JSON/YAML config | Easy to read | Limited expressiveness, no logic |
| Full scripting (JS, Lua) | Very flexible | Security risk, side effects, performance |
| **CEL** | Safe, fast, readable | Learning curve, limited to expressions |

CEL hits the sweet spot: expressive enough for real policies, safe enough that untrusted users could theoretically write them (though volta does not expose this).

---

## How does it work?

### CEL syntax basics

```
  // Comparison
  request.method == "POST"

  // Boolean logic
  user.role == "OWNER" || user.role == "ADMIN"

  // String operations
  user.email.endsWith("@example.com")

  // List membership
  request.path in ["/api/v1/health", "/api/v1/status"]

  // Ternary
  user.verified ? "allowed" : "denied"

  // Nested access
  request.headers["Authorization"].startsWith("Bearer ")

  // Numeric comparison
  request.body.amount <= 10000
```

### CEL evaluation model

```
  ┌──────────────┐     ┌──────────────────────┐
  │  CEL          │     │  Context (variables)  │
  │  Expression   │     │                      │
  │               │     │  user.role = "MEMBER" │
  │  user.role    │     │  user.email = "a@b.c" │
  │  == "OWNER"   │────►│  request.method = GET │
  │               │     │  request.path = /api  │
  └──────────────┘     └──────────────────────┘
         │
         ▼
  ┌──────────────┐
  │  Result:      │
  │  false        │
  └──────────────┘
```

### What makes CEL safe

| Property | What it means |
|----------|--------------|
| **No loops** | Cannot write `while(true)` -- guaranteed to terminate |
| **No side effects** | Cannot write to files, send HTTP requests, or modify state |
| **No variable assignment** | Cannot create variables or change input data |
| **Bounded execution** | Evaluates in predictable time, proportional to expression size |
| **Type-safe** | Type errors caught before evaluation |

---

## How does volta-auth-proxy use it?

volta's DSL uses CEL-like syntax for **guard expressions** -- conditions that control when auth rules apply.

### Guard expressions in volta DSL

In volta's configuration DSL, guards determine which requests match a rule:

```yaml
rules:
  - name: "owner-only-admin"
    guard: "request.path.startsWith('/api/v1/admin') && user.role == 'OWNER'"
    action: allow

  - name: "member-read-only"
    guard: "request.method == 'GET' && user.role == 'MEMBER'"
    action: allow

  - name: "block-free-email-signup"
    guard: "user.email.endsWith('@gmail.com') || user.email.endsWith('@yahoo.com')"
    action: deny
```

### Why CEL-like (not full CEL)

volta uses a CEL-inspired syntax rather than the full CEL specification. This is a deliberate YAGNI decision:

| Feature | Full CEL | volta's CEL-like |
|---------|----------|-----------------|
| Boolean operators | Yes | Yes |
| String methods | All | Subset (startsWith, endsWith, contains, matches) |
| Numeric comparison | Yes | Yes |
| List operations | Full (map, filter, exists) | Basic (in) |
| Macros | Yes | No |
| Custom functions | Yes | No (Phase 2) |

The subset covers 95% of real-world guard expressions. Adding full CEL would mean adding a CEL runtime dependency -- against volta's philosophy of minimal dependencies.

### How guards are evaluated

```
  Incoming HTTP request
         │
         ▼
  Build context object:
  ┌────────────────────────┐
  │  request.method = POST │
  │  request.path = /api.. │
  │  request.headers = ... │
  │  user.id = "u-123"     │
  │  user.role = "MEMBER"  │
  │  user.email = "a@b.c"  │
  │  tenant.id = "t-456"   │
  │  tenant.plan = "free"  │
  └────────────────────────┘
         │
         ▼
  Evaluate each rule's guard expression
  against the context
         │
    ┌────┴─────┐
    │          │
  Match     No match
    │          │
    ▼          ▼
  Apply     Try next
  action    rule
```

### Mermaid state diagram generation

volta's DSL can generate mermaid diagrams from guard expressions, visualizing the rule evaluation flow. See [mermaid.md](mermaid.md) for details.

---

## Common mistakes and attacks

### Mistake 1: Over-complex guard expressions

```
  BAD:  guard: "request.path.startsWith('/api/v1/tenants/')
         && request.path.split('/').size() >= 6
         && request.path.split('/')[5] == 'members'
         && (user.role == 'OWNER' || (user.role == 'MEMBER'
         && request.method == 'GET'))"

  GOOD: Split into multiple rules:
    - name: "owner-manages-members"
      path: "/api/v1/tenants/{tid}/members/**"
      guard: "user.role == 'OWNER'"
      action: allow

    - name: "member-views-members"
      path: "/api/v1/tenants/{tid}/members/**"
      method: GET
      guard: "user.role == 'MEMBER'"
      action: allow
```

### Mistake 2: Trusting client-side data in guards

Guards should evaluate server-verified data (JWT claims, session data), not raw request data that can be spoofed (custom headers, query parameters).

### Mistake 3: Using CEL for business logic

CEL is for access control decisions (allow/deny). Complex business logic belongs in application code where it can be properly tested and debugged.

### Mistake 4: Not testing guard expressions

Guard expressions are code. They need test cases just like Java code. A typo in a guard (`user.rolee` instead of `user.role`) silently fails and may allow unauthorized access.

---

## Further reading

- [mermaid.md](mermaid.md) -- How volta generates diagrams from DSL rules.
- [authentication-vs-authorization.md](authentication-vs-authorization.md) -- CEL guards handle authorization.
- [tenant-resolution.md](tenant-resolution.md) -- How tenant context is provided to guard expressions.
- [CEL Specification](https://github.com/google/cel-spec) -- The official CEL language spec.
- [Google CEL Go Implementation](https://github.com/google/cel-go) -- Reference implementation.
