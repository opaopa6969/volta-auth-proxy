# Runtime

[日本語版はこちら](runtime.ja.md)

---

## What is it?

Runtime has two meanings in software, and both are relevant to volta-auth-proxy.

**Meaning 1: A phase of execution.** Runtime is when your code is actually running, as opposed to compile-time (when your code is being compiled into bytecode). A "runtime error" is an error that happens while the program is running, not while it is being built.

**Meaning 2: The environment that executes your code.** The Java Virtual Machine (JVM) is a runtime -- it is the software that reads your compiled bytecode and executes it on the operating system. Without a runtime, your code is just a file sitting on disk.

Think of it like a music box. Compile-time is when the metal cylinder is manufactured with the right bumps (your code compiled to bytecode). The runtime is the music box mechanism itself -- the spring, the comb, and the housing that makes the cylinder actually produce music. Without the mechanism (runtime), the cylinder (bytecode) is just a decorative piece of metal.

---

## Why does it matter?

- **Runtime errors are production errors.** Compile-time errors are caught by the compiler before deployment. Runtime errors happen in front of real users.
- **The runtime determines performance.** The JVM's garbage collector, JIT compiler, and memory management directly affect how fast volta responds to requests.
- **Runtime version matters.** volta requires Java 21 [LTS](lts.md). Running on Java 17 or Java 8 will fail due to missing language features.
- **Runtime configuration affects stability.** JVM heap size, garbage collection strategy, and thread pool sizing are runtime decisions that affect production behavior.
- **Runtime is the attack surface.** The JVM, the operating system, and any runtime libraries are all potential targets for exploits.

---

## How does it work?

### Compile-time vs runtime

```
  Source code (.java files)
       │
       │  COMPILE-TIME
       │  (javac compiler)
       │
       ▼
  Bytecode (.class files / JAR)
       │
       │  RUNTIME
       │  (JVM executes bytecode)
       │
       ▼
  Running application
  (handling HTTP requests)

  ┌───────────────────────────────────────┐
  │            Compile-time               │
  │                                       │
  │  Errors caught:                      │
  │  - Syntax errors                     │
  │  - Type mismatches                   │
  │  - Missing imports                   │
  │                                       │
  │  Tools: javac, Maven, IDE            │
  └───────────────────────────────────────┘
                    │
                    ▼
  ┌───────────────────────────────────────┐
  │              Runtime                  │
  │                                       │
  │  Errors caught:                      │
  │  - NullPointerException              │
  │  - Database connection refused       │
  │  - OutOfMemoryError                  │
  │  - HTTP 500 responses                │
  │                                       │
  │  Tools: JVM, monitoring, logging     │
  └───────────────────────────────────────┘
```

### The JVM as a runtime

The JVM is one of the most sophisticated runtimes in existence:

```
  ┌──────────────────────────────────────┐
  │            JVM Runtime               │
  │                                      │
  │  ┌────────────────┐                 │
  │  │ Class Loader   │  loads .class   │
  │  └───────┬────────┘  files          │
  │          │                           │
  │          ▼                           │
  │  ┌────────────────┐                 │
  │  │ Bytecode       │  interprets     │
  │  │ Interpreter    │  initially      │
  │  └───────┬────────┘                 │
  │          │                           │
  │          ▼                           │
  │  ┌────────────────┐                 │
  │  │ JIT Compiler   │  compiles hot   │
  │  │                │  paths to       │
  │  │                │  native code    │
  │  └───────┬────────┘                 │
  │          │                           │
  │          ▼                           │
  │  ┌────────────────┐                 │
  │  │ Garbage        │  reclaims       │
  │  │ Collector      │  unused memory  │
  │  └────────────────┘                 │
  │                                      │
  │  ┌────────────────┐                 │
  │  │ Thread Manager │  handles        │
  │  │                │  concurrency    │
  │  └────────────────┘                 │
  └──────────────────────────────────────┘
          │
          ▼
  ┌──────────────────────────────────────┐
  │        Operating System              │
  │  (Linux, macOS, Windows)             │
  └──────────────────────────────────────┘
```

### Key JVM runtime features

| Feature | What it does | Impact on volta |
|---------|-------------|-----------------|
| **JIT Compilation** | Compiles hot bytecode to native code | volta gets faster after warmup |
| **Garbage Collection** | Frees unused memory automatically | Pause times affect request latency |
| **Thread Management** | Maps Java threads to OS threads | Handles concurrent ForwardAuth requests |
| **Memory Management** | Heap and stack allocation | Determines how many sessions can be cached |
| **Security Manager** | Sandboxes code execution | (Deprecated in Java 17+) |

### Runtime configuration

```bash
# Starting volta with runtime configuration
java \
  -Xms256m \              # Initial heap size
  -Xmx512m \              # Maximum heap size
  -XX:+UseG1GC \          # Use G1 garbage collector
  -XX:MaxGCPauseMillis=200 \  # Target GC pause time
  -jar volta-auth-proxy.jar
```

---

## How does volta-auth-proxy use it?

### Java 21 LTS runtime

volta requires Java 21, which is a Long Term Support release. Key Java 21 features volta benefits from:

- **Virtual threads (Project Loom)** -- Lightweight threads for handling many concurrent requests efficiently
- **Pattern matching** -- Cleaner code for type checking
- **Record classes** -- Immutable data carriers with less boilerplate
- **Sealed classes** -- Restrict which classes can extend a type
- **Text blocks** -- Multi-line strings for SQL and templates

### Runtime behavior in production

```
  volta-auth-proxy JVM lifecycle:

  1. JVM starts
     └─ Loads classes, initializes Javalin
     └─ Flyway runs migrations
     └─ HikariCP opens connection pool
     └─ Caffeine cache initialized (empty)

  2. Warmup phase (~30 seconds)
     └─ JIT compiler not yet active
     └─ First requests slightly slower
     └─ Caffeine cache populating

  3. Steady state
     └─ JIT has compiled hot paths
     └─ Connection pool at optimal size
     └─ Cache hit rates stabilizing
     └─ GC pauses minimal

  4. Shutdown
     └─ Javalin stops accepting requests
     └─ In-flight requests complete
     └─ HikariCP closes connections
     └─ JVM exits
```

### Runtime errors volta handles

```java
// Database connection lost at runtime
try {
    return dataSource.getConnection();
} catch (SQLException e) {
    // Runtime error: DB is unreachable
    // Log, return 503, alert ops team
}

// Google OIDC unreachable at runtime
try {
    return httpClient.send(tokenRequest);
} catch (HttpTimeoutException e) {
    // Runtime error: Google is slow/down
    // Return user-friendly error page
}
```

---

## Common mistakes and attacks

### Mistake 1: Running on the wrong Java version

volta requires Java 21. Running on Java 17 or 11 will produce `UnsupportedClassVersionError` at startup. Always verify: `java -version`.

### Mistake 2: Insufficient heap memory

If the JVM runs out of heap memory, you get `OutOfMemoryError` and the process crashes. Monitor heap usage and set `-Xmx` appropriately for your load.

### Mistake 3: Ignoring GC pauses

Long garbage collection pauses cause request timeouts. If ForwardAuth checks take 200ms+ occasionally, check GC logs with `-Xlog:gc`.

### Mistake 4: Not monitoring runtime metrics

Without monitoring heap usage, thread counts, and GC frequency, you fly blind. Use JMX or Prometheus metrics to track JVM health.

### Mistake 5: Confusing compile-time safety with runtime safety

Java's type system catches many errors at compile time, but it cannot catch `NullPointerException`, database failures, or network timeouts. Runtime error handling is still essential.

---

## Further reading

- [jvm.md](jvm.md) -- Deep dive into the Java Virtual Machine.
- [lts.md](lts.md) -- Why volta uses Java 21 LTS.
- [java.md](java.md) -- The programming language volta is written in.
- [fat-jar.md](fat-jar.md) -- How volta packages code for runtime execution.
- [connection-pool.md](connection-pool.md) -- Runtime resource management for database connections.
