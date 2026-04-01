# Type-Safe

[日本語版はこちら](type-safe.ja.md)

---

## What is it?

Type-safe means the [compiler](compile.md) checks that your code uses the right kinds of data in the right places, and catches mistakes before the program runs. If a function expects a number and you accidentally pass it a piece of text, a type-safe language will refuse to [compile](compile.md), forcing you to fix the mistake immediately.

Think of it like a key and a lock. A type-safe system ensures that only the right key fits the right lock. If you try to use a car key to open your front door, the system stops you immediately (at compile time) rather than letting you try and fail (at runtime). Without type safety, you would not know the key was wrong until you stood at the door -- or worse, the lock might accept the wrong key and open for anyone.

[Java](java.md), which volta-auth-proxy uses, is a type-safe language. [JavaScript](javascript.md) is not (though TypeScript adds type-checking on top). The [jte](jte.md) template engine that volta uses is type-safe, which is one of the reasons it was chosen over alternatives.

---

## Why does it matter?

Without type safety, bugs hide until production:

- A [template](template.md) references `user.nmae` instead of `user.name` -- works in development if you never hit that code path, crashes at 3 AM in production
- A function returns `null` where a `String` was expected -- NullPointerException at runtime
- An API handler reads `tenantId` as a `String` but the database expects a `UUID` -- fails when the first real request arrives

Type safety catches these bugs at compile time:

- **Faster feedback** -- You learn about the mistake in seconds (compile error), not hours (production crash)
- **Safer refactoring** -- Rename a field and the compiler tells you every file that needs updating
- **Self-documenting code** -- Types serve as documentation that is always up to date
- **Fewer tests needed** -- The compiler eliminates entire categories of bugs that would otherwise need unit tests

---

## How does it work?

### Static types vs. dynamic types

```
  Static typing (Java - type-safe):
  ─────────────────────────────────
  String name = "Taro";
  int age = 30;

  name = 42;          // ✗ COMPILE ERROR: incompatible types
  age = "thirty";     // ✗ COMPILE ERROR: incompatible types

  // The compiler catches these BEFORE the program runs.


  Dynamic typing (JavaScript - not type-safe):
  ─────────────────────────────────────────────
  let name = "Taro";
  let age = 30;

  name = 42;          // ✓ no error (name is now a number)
  age = "thirty";     // ✓ no error (age is now a string)

  // These "work" until something downstream expects a specific type.
```

### How the compiler uses types

```
  Source code                    Compiler                     Result
  ───────────                    ────────                     ──────

  void greet(String name) {     Checks: is "Taro"            ✓ Compiles
      print("Hi " + name);     a String? Yes.
  }
  greet("Taro");


  void greet(String name) {     Checks: is 42                ✗ Error:
      print("Hi " + name);     a String? NO.                "int cannot
  }                                                          be converted
  greet(42);                                                 to String"
```

### Type safety in Java records

volta uses Java records for data classes, which are inherently type-safe:

```java
// The compiler enforces that every Tenant has these exact types
public record Tenant(
    UUID id,           // must be a UUID, not a String
    String name,       // must be a String, not an int
    String slug,       // must be a String
    Instant createdAt  // must be an Instant, not a String
) {}

// This compiles:
new Tenant(UUID.randomUUID(), "ACME Corp", "acme", Instant.now());

// This does NOT compile:
new Tenant("not-a-uuid", "ACME Corp", "acme", "2024-01-01");
// Error: String cannot be converted to UUID
// Error: String cannot be converted to Instant
```

### Type safety in templates (jte vs. others)

This is where type safety makes the biggest practical difference for volta:

```
  Traditional template (Thymeleaf, FreeMarker):
  ─────────────────────────────────────────────
  Template:   <h1>${user.nmae}</h1>          ← typo
  Compile:    ✓ (template not checked)
  Runtime:    ✗ ERROR when page is rendered
  Discovery:  User reports "page is broken"

  Type-safe template (jte):
  ─────────────────────────
  Template:   <h1>${user.nmae}</h1>          ← typo
  Compile:    ✗ ERROR: cannot find symbol "nmae" in record User
  Runtime:    never reached
  Discovery:  Developer sees error immediately during build
```

### The cost of type safety

Type safety is not free. You must:

- Declare types explicitly (though Java's `var` helps reduce verbosity)
- Define data classes/records for structured data
- Handle type conversions explicitly (e.g., `UUID.fromString(str)`)
- Wait for compilation (though incremental compilation is fast)

The trade-off is overwhelmingly worth it for server-side applications like volta, where a type error could mean a security bypass.

---

## How does volta-auth-proxy use it?

### Java's type system throughout the codebase

volta uses Java's type system to prevent bugs at every layer:

```java
// Route parameter extraction -- type-safe
UUID memberId = UUID.fromString(ctx.pathParam("memberId"));
// If the path parameter is not a valid UUID, this throws immediately.
// No silent conversion to a wrong type.

// Role comparison -- type-safe enum
public enum Role { OWNER, ADMIN, MEMBER, VIEWER }

if (role.isAtLeast(Role.ADMIN)) { ... }
// Cannot accidentally compare a role to a string like "admin"
// Cannot misspell a role name -- the compiler catches it
```

### Type-safe jte templates

volta's [jte](jte.md) templates declare their expected data type:

```java
// login.jte
@param LoginPage page

<h1>Welcome to ${page.tenantName()}</h1>
<input type="hidden" value="${page.csrfToken()}">
```

If `LoginPage` record does not have a `tenantName()` method, the template will not compile. If someone renames the method, the compiler will flag every template that references it.

### Type-safe configuration

volta uses typed configuration objects instead of raw string maps:

```java
// Type-safe config
public record VoltaConfig(
    int port,
    String googleClientId,
    String googleClientSecret,
    Duration sessionTimeout,
    Duration jwtExpiry
) {}

// Usage -- the compiler ensures correct types
var config = new VoltaConfig(
    7070,
    env("GOOGLE_CLIENT_ID"),
    env("GOOGLE_CLIENT_SECRET"),
    Duration.ofHours(8),
    Duration.ofMinutes(5)
);
```

You cannot accidentally set `port` to `"seven thousand"` or `sessionTimeout` to an integer.

### Type-safe database results

When volta reads from the database, it maps results to typed records:

```java
public record UserRow(UUID id, String email, String displayName, Instant createdAt) {}

UserRow user = db.queryOne(
    "SELECT id, email, display_name, created_at FROM users WHERE id = ?",
    userId,
    rs -> new UserRow(
        rs.getObject("id", UUID.class),
        rs.getString("email"),
        rs.getString("display_name"),
        rs.getTimestamp("created_at").toInstant()
    )
);
```

The compiler ensures every field has the right type. A mismatch is caught immediately.

---

## Common mistakes and attacks

### Mistake 1: Using Object or Map everywhere

Java allows you to bypass type safety using `Object` or `Map<String, Object>`:

```java
// NOT type-safe -- you lose all compiler checks
Map<String, Object> user = new HashMap<>();
user.put("name", "Taro");
user.put("age", "thirty");  // String instead of int -- no error!

// Type-safe -- compiler catches misuse
record User(String name, int age) {}
new User("Taro", "thirty");  // ✗ COMPILE ERROR
```

volta avoids `Map<String, Object>` for data passing. It uses typed records and classes.

### Mistake 2: Ignoring compiler warnings

Java's compiler warns about unchecked type casts, raw types, and other potential type-safety violations. Suppressing these warnings with `@SuppressWarnings("unchecked")` defeats the purpose of type safety.

### Mistake 3: Casting without checking

```java
// Dangerous -- ClassCastException at runtime
String name = (String) ctx.attribute("userId");  // userId is actually a UUID

// Safe -- use the right type from the start
UUID userId = ctx.attribute("userId");  // properly typed
```

### Attack 1: Type confusion in dynamic languages

In [JavaScript](javascript.md) (not type-safe), `"0" == false` is `true`, and `"" == 0` is `true`. These type coercion rules have caused security vulnerabilities where authentication checks pass when they should fail. Java's type system prevents this entire category of bugs.

### Mistake 4: Not using generics

```java
// NOT type-safe -- List can contain anything
List roles = new ArrayList();
roles.add("ADMIN");
roles.add(42);         // no error, but breaks later
String role = (String) roles.get(1);  // ClassCastException!

// Type-safe -- List can only contain Role
List<Role> roles = new ArrayList<>();
roles.add(Role.ADMIN);
roles.add(42);         // ✗ COMPILE ERROR
```

---

## Further reading

- [compile.md](compile.md) -- The process that enforces type safety.
- [jte.md](jte.md) -- volta's type-safe template engine.
- [java.md](java.md) -- The type-safe language volta uses.
- [javascript.md](javascript.md) -- A dynamically-typed language (contrast).
- [template.md](template.md) -- Where type safety prevents rendering bugs.
- [variable.md](variable.md) -- Variables have types in type-safe languages.
