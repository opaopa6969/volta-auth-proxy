# Dependencies

[日本語版はこちら](dependencies.ja.md)

---

## What is it?

Dependencies are external packages, libraries, or modules that your project needs to function. Instead of writing every piece of code from scratch, you rely on code that other people have already written, tested, and maintained.

Think of it like cooking with store-bought ingredients. You could grow your own tomatoes, mill your own flour, and raise your own chickens -- but most chefs buy ingredients from suppliers. A dependency is an ingredient you did not make yourself. Your `pom.xml` file (in a Maven project) is like a shopping list: "I need Javalin 6.x, HikariCP 5.x, Caffeine 3.x, and Flyway 10.x." Maven reads this list and downloads everything for you.

The critical thing about dependencies: you are trusting someone else's code to run inside your application. If a dependency has a bug, your app has a bug. If a dependency has a security vulnerability, your app has a security vulnerability.

---

## Why does it matter?

- **Saves enormous development time.** Writing your own database connection pool or JWT library would take months. HikariCP and nimbus-jose-jwt already exist and are battle-tested.
- **Security risk.** Every dependency is code you did not write and may not fully understand. Supply-chain attacks target popular libraries.
- **Version conflicts.** If library A needs version 1.0 of library C, and library B needs version 2.0 of library C, you have a conflict.
- **Maintenance burden.** Dependencies release updates, security patches, and breaking changes. You must keep up.
- **Transitive dependencies.** Your dependencies have their own dependencies. A single line in `pom.xml` can pull in dozens of JARs.

---

## How does it work?

### Maven dependency management

volta-auth-proxy uses [Maven](maven.md) to manage dependencies. Dependencies are declared in `pom.xml`:

```xml
<dependencies>
    <!-- Web framework -->
    <dependency>
        <groupId>io.javalin</groupId>
        <artifactId>javalin</artifactId>
        <version>6.3.0</version>
    </dependency>

    <!-- Database connection pool -->
    <dependency>
        <groupId>com.zaxxer</groupId>
        <artifactId>HikariCP</artifactId>
        <version>5.1.0</version>
    </dependency>

    <!-- In-memory cache -->
    <dependency>
        <groupId>com.github.ben-manes.caffeine</groupId>
        <artifactId>caffeine</artifactId>
        <version>3.1.8</version>
    </dependency>
</dependencies>
```

### How Maven resolves dependencies

```
  pom.xml says: "I need Javalin 6.3.0"
       │
       ▼
  Maven checks local cache (~/.m2/repository)
       │
       ├── Found? → Use it
       │
       └── Not found? → Download from Maven Central
                              │
                              ▼
                   ┌──────────────────┐
                   │  Maven Central   │
                   │  (repository)    │
                   │                  │
                   │  javalin-6.3.0   │
                   │    └─ needs:     │
                   │      jetty 11.x  │
                   │      slf4j 2.x   │
                   └──────────────────┘
                              │
                   Also downloads Javalin's
                   own dependencies (transitive)
```

### Direct vs transitive dependencies

```
  Your pom.xml declares:        Maven also downloads:
  ┌───────────────────┐         ┌───────────────────┐
  │ Javalin           │────────▶│ Jetty (web server) │
  │ HikariCP          │         │ SLF4J (logging)    │
  │ Caffeine          │         │ Jackson (JSON)     │
  │ Flyway            │         │ PostgreSQL driver   │
  │ jte               │         │ ... dozens more     │
  └───────────────────┘         └───────────────────┘
     Direct (you chose)           Transitive (pulled in)
       ~10-15 entries              ~50-100 JARs total
```

### Dependency scope

Maven dependencies have scopes that control when they are available:

| Scope | When available | Example |
|-------|---------------|---------|
| `compile` (default) | Build + Runtime | Javalin, HikariCP |
| `runtime` | Runtime only | PostgreSQL JDBC driver |
| `test` | Test only | JUnit, Mockito |
| `provided` | Build only, server supplies at runtime | Servlet API (not used in volta) |

---

## How does volta-auth-proxy use it?

### volta's dependency philosophy

volta intentionally keeps dependencies minimal. Every dependency is a risk, and volta's design philosophy values understanding every line of code that runs in the auth layer.

### Key dependencies

| Dependency | Purpose | Why this one? |
|-----------|---------|---------------|
| **Javalin** | HTTP framework | Lightweight, no magic, full control |
| **HikariCP** | [Connection pool](connection-pool.md) | Fastest Java connection pool |
| **Caffeine** | [In-memory cache](in-memory.md) | Fastest Java cache, smart eviction |
| **Flyway** | [Database migration](migration.md) | Industry standard, reliable |
| **jte** | Template engine | Type-safe, compiled, fast |
| **nimbus-jose-jwt** | [JWT](jwt.md) handling | Comprehensive, well-maintained |
| **PostgreSQL JDBC** | Database driver | Official driver for Postgres |
| **SLF4J + Logback** | Logging | Industry standard |

### What volta intentionally does NOT depend on

| Not used | Why |
|----------|-----|
| Spring / Spring Boot | Too much magic, too many transitive dependencies |
| Hibernate / JPA | SQL is clearer; no ORM complexity |
| Keycloak SDK | Self-hosted auth, no vendor dependency |
| Node.js / npm | Supply chain risk, dependency explosion |

### Checking for vulnerabilities

```bash
# Maven has a plugin for checking known vulnerabilities
mvn org.owasp:dependency-check-maven:check

# This scans all dependencies (including transitive) against
# the National Vulnerability Database (NVD)
```

---

## Common mistakes and attacks

### Mistake 1: Adding dependencies without evaluation

Every dependency added to `pom.xml` runs inside your application with full access. Before adding one, ask: How many transitive dependencies does it bring? Is it actively maintained? Does it have known vulnerabilities?

### Mistake 2: Not pinning versions

```xml
<!-- BAD: version range, unpredictable builds -->
<version>[1.0,2.0)</version>

<!-- GOOD: exact version, reproducible builds -->
<version>6.3.0</version>
```

### Mistake 3: Ignoring transitive dependency updates

Your direct dependencies may be up to date, but their transitive dependencies might have critical vulnerabilities. Use `mvn dependency:tree` to see the full tree.

### Mistake 4: Supply chain attack blindness

Attackers compromise popular libraries or publish typosquatted packages (e.g., `javelin` instead of `javalin`). Always verify groupId and artifactId carefully.

### Mistake 5: Never updating dependencies

Old dependencies accumulate security vulnerabilities. Java 21 [LTS](lts.md) provides a stable base, but your libraries still need periodic updates.

---

## Further reading

- [maven.md](maven.md) -- The build tool that manages volta's dependencies.
- [hikaricp.md](hikaricp.md) -- volta's connection pool dependency.
- [caffeine-cache.md](caffeine-cache.md) -- volta's caching dependency.
- [external-dependency.md](external-dependency.md) -- Risks of depending on external code.
- [fat-jar.md](fat-jar.md) -- How dependencies get packaged into a single deployable file.
