# Compile

[日本語版はこちら](compile.ja.md)

---

## What is it?

Compiling is the process of translating human-readable source code into a form that a computer can execute. In [Java](java.md), the compiler reads `.java` files and produces `.class` files containing bytecode that the [JVM](jvm.md) can run. If the code has errors -- wrong types, missing methods, syntax mistakes -- the compiler refuses to produce output and tells you exactly what is wrong.

Think of it like translating a book from English to Japanese. The translator (compiler) reads the English manuscript (source code) and produces a Japanese version (bytecode). If a sentence does not make grammatical sense in English, the translator cannot translate it and returns the manuscript with red marks showing the problems. You must fix the English before the translation can proceed.

Compilation is one step in the larger [build](build.md) process. `mvn compile` compiles the source code. `mvn package` compiles, tests, and packages it into a [fat JAR](fat-jar.md).

---

## Why does it matter?

Compilation matters because it is your first line of defense against bugs:

- **Type errors caught early** -- The compiler verifies [type safety](type-safe.md). Passing a `String` where a `UUID` is expected? Compile error.
- **Missing methods caught early** -- Calling `user.nmae()` when the method is `user.name()`? Compile error.
- **Syntax errors caught early** -- Forgot a semicolon, mismatched braces, wrong import? Compile error.
- **Fast feedback loop** -- You learn about problems in seconds, not after deploying to production.

For volta-auth-proxy, compilation also checks [jte](jte.md) [templates](template.md). A typo in a template is caught at compile time, not when a user visits the page.

---

## How does it work?

### The Java compilation pipeline

```
  Source Code (.java)
       │
       ▼
  ┌──────────────────┐
  │  Java Compiler   │
  │  (javac)         │
  │                  │
  │  1. Parse syntax │
  │  2. Check types  │
  │  3. Resolve refs │
  │  4. Generate     │
  │     bytecode     │
  └──────────────────┘
       │
       ▼
  Bytecode (.class)
       │
       ▼
  ┌──────────────────┐
  │  JVM             │
  │  Executes        │
  │  bytecode        │
  └──────────────────┘
```

### What the compiler checks

| Check | Example error | Caught at |
|-------|--------------|-----------|
| Syntax | `if (x = 5)` instead of `if (x == 5)` | Compile time |
| Types | `String x = 42;` | Compile time |
| Method resolution | `user.nmae()` (typo) | Compile time |
| Import resolution | `import com.example.Foo;` (class not found) | Compile time |
| Access control | `private` method called from outside | Compile time |
| Generics | `List<String>.add(42)` | Compile time |

### Compiled vs. interpreted languages

| Language | Model | Errors caught | volta role |
|----------|-------|---------------|-----------|
| [Java](java.md) | Compiled to bytecode | At compile time | volta's language |
| Go, Rust, C | Compiled to native | At compile time | -- |
| [JavaScript](javascript.md) | Interpreted | At runtime | Browser/SPA language |
| Python | Interpreted | At runtime | -- |
| TypeScript | Compiled to JavaScript | At compile time (types only) | -- |

### Incremental compilation

Modern compilers only recompile files that changed:

```
  First compile:    All 50 files compiled          → 5 seconds
  Change 1 file:    Only that file recompiled      → 0.3 seconds
  Change template:  Only that template recompiled  → 0.2 seconds
```

This makes the edit-compile-test cycle fast enough for productive development.

### Compile errors vs. runtime errors

```
  Compile error (GOOD -- caught before running):
  ─────────────────────────────────────────────
  src/main/java/UserService.java:42: error: incompatible types
      UUID id = "not-a-uuid";
                ^
      required: UUID
      found:    String

  → Fix: UUID id = UUID.fromString("550e8400-...");


  Runtime error (BAD -- caught while running):
  ─────────────────────────────────────────────
  Exception in thread "main" java.lang.ClassCastException:
      java.lang.String cannot be cast to java.util.UUID
      at UserService.getUser(UserService.java:42)

  → User already saw an error page. Damage done.
```

---

## How does volta-auth-proxy use it?

### Compilation with Maven

volta uses [Maven](maven.md) for building. The compile step:

```bash
# Compile Java source code
mvn compile

# This runs javac on all .java files in src/main/java/
# Output goes to target/classes/
```

### jte template pre-compilation

volta pre-compiles [jte](jte.md) templates during the build, catching template errors at compile time:

```bash
# During mvn compile, the jte Maven plugin:
# 1. Reads all .jte files in src/main/jte/
# 2. Generates Java source code from them
# 3. Compiles the generated Java code
# 4. Any template error = compile failure
```

This means a typo like `${user.nmae}` fails the build immediately, not when a user visits the page.

### The full build pipeline

```
  mvn package
       │
       ├── 1. Compile source code (mvn compile)
       │       javac: .java → .class
       │       jte: .jte → .java → .class
       │
       ├── 2. Run tests (mvn test)
       │       JUnit tests verify behavior
       │
       ├── 3. Package (mvn package)
       │       Assemble .class files + dependencies
       │       into a single fat JAR
       │
       └── Output: target/volta-auth-proxy.jar
```

### Compile-time safety for security

Several security-critical parts of volta rely on compile-time checking:

```java
// Role enum -- misspelling a role is a compile error
if (role == Role.ADMNI) { }  // ✗ compile error: ADMNI not found

// JWT claims -- typed record ensures all claims present
public record VoltaClaims(
    UUID sub,
    UUID voltaTid,
    List<Role> voltaRoles,
    String voltaDisplay
) {}
// Forgetting a field = compile error
```

---

## Common mistakes and attacks

### Mistake 1: Ignoring compiler warnings

The compiler may produce warnings that are not errors -- the code compiles but the compiler suspects a problem. Common warnings:

```
  warning: unchecked cast
  warning: deprecated method
  warning: unused variable
```

Treat warnings as errors. volta's build should fail on warnings to prevent subtle bugs.

### Mistake 2: Assuming "it compiles" means "it works"

Compilation catches type errors, not logic errors. This compiles fine but is wrong:

```java
// Compiles, but the logic is backwards
if (role.isAtLeast(Role.VIEWER)) {
    // Should have checked ADMIN, not VIEWER
    deleteAllMembers();
}
```

This is why tests exist. Compilation + tests together provide strong guarantees.

### Mistake 3: Not compiling templates

Many Java projects use template engines that are not checked at compile time (JSP, FreeMarker with dynamic models). Template errors hide until runtime. volta chose jte specifically because it compiles templates, eliminating this class of bugs.

### Mistake 4: Compiling but not testing

```
  mvn compile   → Only checks types and syntax
  mvn test      → Also runs JUnit tests
  mvn package   → Compile + test + package
  mvn verify    → Compile + test + integration tests + package
```

Running only `mvn compile` gives a false sense of security. Always run at least `mvn package` before deploying.

---

## Further reading

- [build.md](build.md) -- The full build process that includes compilation.
- [type-safe.md](type-safe.md) -- The property that compilation enforces.
- [java.md](java.md) -- The compiled language volta uses.
- [jvm.md](jvm.md) -- The runtime that executes compiled Java bytecode.
- [maven.md](maven.md) -- The build tool that orchestrates compilation.
- [jte.md](jte.md) -- The template engine that compiles templates.
- [fat-jar.md](fat-jar.md) -- The final output of the compile + package process.
