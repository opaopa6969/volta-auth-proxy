# Library

[日本語版はこちら](library.ja.md)

---

## What is it?

A library is a reusable package of code that solves a specific problem. You import it into your project, call its functions when you need them, and it returns results. Your code remains in control -- the library is a tool you pick up and use, not a structure you live inside.

Think of it like a set of power tools. When you are building a bookshelf, you own the project. You decide what to build and when. When you need to drill a hole, you pick up the drill, use it, and put it back. The drill does not tell you what to build or when to build it. That is a library -- a tool you reach for when you need it.

This is the fundamental difference from a [framework](framework.md). A framework is the workshop that organizes your work and tells you where to put things. A library is a tool that sits on the shelf until you grab it. In technical terms: with a library, **you** control the flow. With a framework, the **framework** controls the flow.

---

## Why does it matter?

Libraries are the building blocks of every modern application. Without them:

- You would rewrite JSON parsing for every project
- You would implement your own cryptographic algorithms (a terrible idea)
- You would write your own database connection pooling
- You would build your own HTTP client from raw sockets
- Development time would increase by orders of magnitude

Libraries let you stand on the shoulders of experts. The people who wrote [HikariCP](hikaricp.md) spent years optimizing database connection pooling. The people who wrote nimbus-jose-jwt spent years hardening JWT parsing against attacks. You get that expertise for free by adding a dependency.

---

## How does it work?

### Using a library

In [Java](java.md), libraries are distributed as JAR files (Java Archive -- essentially a ZIP of compiled classes). You declare them as dependencies in your [Maven](maven.md) pom.xml:

```xml
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
    <version>5.1.0</version>
</dependency>
```

Maven downloads the JAR from a [repository](repository.md), adds it to your classpath, and you can use it:

```java
// YOUR code calls the library
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:postgresql://localhost:5432/volta");
HikariDataSource ds = new HikariDataSource(config);

// The library returns a result -- you decide what to do with it
Connection conn = ds.getConnection();
```

Notice: **your code** is in control. You decide when to create the pool, when to get a connection, and what to do with it.

### The dependency tree

Libraries often depend on other libraries. This creates a tree:

```
  volta-auth-proxy
  ├── javalin (framework, but also a dependency)
  │   ├── jetty-server
  │   ├── jetty-webapp
  │   └── slf4j-api
  ├── hikaricp (library)
  │   └── slf4j-api
  ├── nimbus-jose-jwt (library)
  │   └── jcip-annotations
  ├── caffeine (library)
  │   └── checker-qual
  ├── postgresql (library - JDBC driver)
  └── jte (library - template engine)
```

Maven resolves this tree automatically, downloading transitive dependencies. This is why a project with 10 direct dependencies might have 50+ JARs in the final [fat JAR](fat-jar.md).

### Library vs. framework vs. SDK

| Concept | Control | Example | Relationship |
|---------|---------|---------|--------------|
| Library | You call it | HikariCP, Caffeine, nimbus-jose-jwt | Tool on a shelf |
| [Framework](framework.md) | It calls you | Javalin, Spring Boot | Workshop structure |
| [SDK](sdk.md) | Mixed | volta-sdk-js | Collection of libraries + tools for a platform |

### Types of libraries

| Type | Example in volta | Purpose |
|------|-----------------|---------|
| Database driver | postgresql JDBC | Communicates with [Postgres](database.md) |
| Connection pool | [HikariCP](hikaricp.md) | Manages reusable DB connections |
| Cache | [Caffeine](caffeine-cache.md) | In-memory caching |
| Crypto/JWT | nimbus-jose-jwt | JWT creation and verification |
| Template engine | [jte](jte.md) | HTML rendering with data |
| Migration | [Flyway](flyway.md) | Database schema versioning |
| HTTP client | java.net.http (built-in) | Calling external APIs (Google OIDC) |
| Logging | SLF4J + Logback | Structured log output |

---

## How does volta-auth-proxy use it?

volta-auth-proxy deliberately chooses small, focused libraries instead of a large framework that bundles everything. Each library solves one problem well.

### The volta library stack

```
  ┌─────────────────────────────────────────────┐
  │              volta-auth-proxy                │
  │                                              │
  │  ┌──────────┐  ┌──────────┐  ┌───────────┐  │
  │  │ HikariCP │  │ Caffeine │  │ nimbus-   │  │
  │  │ (DB pool)│  │ (cache)  │  │ jose-jwt  │  │
  │  └──────────┘  └──────────┘  └───────────┘  │
  │                                              │
  │  ┌──────────┐  ┌──────────┐  ┌───────────┐  │
  │  │ Flyway   │  │  jte     │  │ PostgreSQL│  │
  │  │ (migrate)│  │(template)│  │  (driver)  │  │
  │  └──────────┘  └──────────┘  └───────────┘  │
  │                                              │
  │  ┌──────────────────────────────────────┐    │
  │  │         Javalin (framework)          │    │
  │  │         Jetty (HTTP server)          │    │
  │  └──────────────────────────────────────┘    │
  └─────────────────────────────────────────────┘
```

### Why small libraries instead of Spring Boot?

Spring Boot is a [framework](framework.md) that bundles database access (Spring Data JPA), caching (Spring Cache), security (Spring Security), templating, and more into one package. volta takes the opposite approach:

- **HikariCP** instead of Spring Data's connection management -- explicit pool configuration, no magic `@Transactional` annotations
- **Caffeine** instead of Spring Cache -- direct API calls like `cache.get(key, loader)`, no `@Cacheable` annotations
- **nimbus-jose-jwt** instead of Spring Security's JWT support -- full control over algorithm checks and claim validation
- **Flyway** instead of Hibernate auto-DDL -- explicit [SQL](sql.md) migrations, no ORM generating schemas

Each library is independent. If one needs to be replaced, the others are unaffected. This is the opposite of a framework where everything is interconnected.

### Dependency management in pom.xml

All libraries are declared in the project's pom.xml with explicit versions. volta does not use Maven BOM (Bill of Materials) or parent POM inheritance to manage versions, keeping the dependency list self-contained and auditable.

---

## Common mistakes and attacks

### Mistake 1: Using a library without understanding it

Adding a library to handle JWTs does not mean JWTs are secure. You still need to understand which algorithms to accept, what claims to validate, and how to handle expiration. volta's JwtService.java wraps nimbus-jose-jwt with explicit RS256 enforcement -- it does not blindly trust the library's defaults.

### Mistake 2: Too many libraries for simple tasks

Every library adds to the dependency tree, increasing the [fat JAR](fat-jar.md) size and the attack surface. If you need to generate a random string, use `java.util.UUID.randomUUID()` instead of adding Apache Commons Lang. volta uses Java standard library features wherever they are sufficient.

### Attack 1: Supply chain attacks

A malicious actor publishes a library with a similar name to a popular one (typosquatting), or compromises an existing library's build pipeline. When you add the dependency, you run their code. This is why volta pins explicit versions and uses [Maven](maven.md) (which uses checksums) rather than npm (which has had more supply chain incidents).

### Mistake 3: Not updating libraries

Libraries receive security patches. Running an old version of nimbus-jose-jwt might leave you vulnerable to JWT parsing attacks that were fixed months ago. But updating blindly is also dangerous -- always read the changelog.

### Attack 2: Transitive dependency exploitation

Even if your direct dependencies are secure, their dependencies might not be. A vulnerability in a logging library (like Log4Shell in Log4j) can affect every application that depends on it, even indirectly. Use `mvn dependency:tree` to audit your full dependency tree.

### Mistake 4: Confusing a library with a framework

If you wrap a library in so many abstractions that it starts controlling your code flow, you have accidentally built a [framework](framework.md). Keep library usage simple and direct.

---

## Further reading

- [framework.md](framework.md) -- How a framework differs from a library (inversion of control).
- [maven.md](maven.md) -- How volta manages library dependencies.
- [fat-jar.md](fat-jar.md) -- How libraries get packaged into the final JAR.
- [hikaricp.md](hikaricp.md) -- An example of a focused library volta uses.
- [caffeine-cache.md](caffeine-cache.md) -- Another focused library in volta's stack.
- [sdk.md](sdk.md) -- When a collection of libraries becomes an SDK.
- [external-dependency.md](external-dependency.md) -- The risks of depending on external code.
