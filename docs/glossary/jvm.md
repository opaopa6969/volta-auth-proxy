# JVM (Java Virtual Machine)

[日本語版はこちら](jvm.ja.md)

---

## What is it?

The Java Virtual Machine (JVM) is a program that runs Java applications. When you write Java code, it gets compiled into "bytecode" -- an intermediate format that is not specific to any operating system. The JVM reads this bytecode and translates it into instructions your specific computer understands.

Think of it as a universal translator. You write your instructions in Java, and the JVM translates them into whatever language the computer speaks -- whether it is Windows, Mac, or Linux.

---

## Why does it matter?

The JVM matters in the auth world because of one word: **Keycloak**. Keycloak is the most well-known open-source identity server, and it runs on the JVM. Understanding the JVM explains why Keycloak uses ~512MB of RAM just to start, why it takes ~30 seconds to boot, and why volta-auth-proxy -- despite also being written in [Java](java.md) -- uses only ~30MB and starts in ~200ms.

The difference is not the language. The difference is what you put on top of the JVM.

---

## What "runs on the JVM" means

When someone says "this application runs on the JVM," they mean:

1. The application is written in Java (or Kotlin, Scala, Clojure, Groovy -- all JVM languages)
2. The application needs a JVM installed to run
3. The JVM itself consumes memory and CPU before your application code even starts

```
  What happens when you start a JVM application:

  1. JVM starts                          [~50-100MB just for the JVM]
  2. Load application classes            [class loading, verification]
  3. Initialize frameworks               [the real cost lives here]
  4. JIT compile hot paths               [performance improves over time]
  5. Application is ready                [finally accepting requests]

  Steps 1-4 take ~200ms for Javalin.
  Steps 1-4 take ~30 seconds for Keycloak/Quarkus with all its extensions.
```

The JVM itself is not the problem. The JVM is actually remarkably fast and efficient. The problem is what frameworks do during step 3.

---

## Why Keycloak is heavy (JVM overhead + framework overhead)

Keycloak previously ran on WildFly (a full Java EE application server) and now runs on Quarkus. In both cases, it loads an enormous amount of functionality at startup:

```
  Keycloak at startup loads:
  ┌─────────────────────────────────┐
  │ JVM base                        │  ~50MB
  │ Quarkus runtime                 │  ~30MB
  │ Hibernate ORM                   │  ~20MB
  │ RESTEasy (JAX-RS)               │  ~15MB
  │ Infinispan (distributed cache)  │  ~30MB
  │ Keycloak identity engine        │  ~50MB
  │ Admin console (React SPA)       │  (served from memory)
  │ Theme engine (FreeMarker)       │  ~10MB
  │ Protocol mappers (OIDC, SAML)   │  ~20MB
  │ Event listeners                 │  ~10MB
  │ User federation SPI             │  ~10MB
  │ Dozens more subsystems...       │  ~???MB
  ├─────────────────────────────────┤
  │ Total: ~512MB+ at idle          │
  └─────────────────────────────────┘
```

Keycloak is not slow because of Java. Keycloak is slow because it is a **full-featured enterprise identity platform** that loads everything whether you need it or not. The Swiss Army knife analogy applies: even if you only want to cut bread, you are carrying all 30 tools.

---

## Why volta is light despite being Java

volta-auth-proxy is also written in Java. It also runs on the JVM. But it uses [Javalin](https://javalin.io/), a minimal web framework:

```
  volta at startup loads:
  ┌─────────────────────────────────┐
  │ JVM base                        │  ~15MB (with -Xmx tuning)
  │ Javalin (embedded Jetty)        │  ~5MB
  │ HikariCP (connection pool)      │  ~2MB
  │ JJWT (JWT library)              │  ~1MB
  │ volta application code          │  ~5MB
  │ jte templates (compiled)        │  ~2MB
  ├─────────────────────────────────┤
  │ Total: ~30MB at idle            │
  └─────────────────────────────────┘
```

The difference is stark:

| Metric | Keycloak | volta |
|--------|----------|-------|
| RAM at idle | ~512MB+ | ~30MB |
| Startup time | ~30 seconds | ~200ms |
| Framework | Quarkus + dozens of extensions | Javalin (minimal) |
| Approach | Load everything | Load only what's needed |

volta achieves this by making opinionated choices:

- **No ORM.** Raw SQL via JDBC. No Hibernate class loading, no entity scanning, no lazy proxy generation.
- **No full application server.** Javalin is a thin wrapper around Jetty. No CDI, no JAX-RS, no bean discovery.
- **No distributed cache.** Sessions in PostgreSQL (or Redis). No Infinispan cluster management.
- **No plugin system.** volta is one application, not a platform. No SPI scanning, no extension loading.

---

## The JVM is not the enemy

It is common to hear "Java is slow" or "JVM applications are heavy." This is inaccurate. The JVM itself is one of the most optimized pieces of software ever created:

- **JIT compilation** makes long-running Java applications faster than many "compiled" languages
- **G1 / ZGC garbage collectors** handle memory efficiently with minimal pauses
- **Virtual threads** (Java 21+) make concurrent programming lightweight

The heaviness comes from **frameworks**, not from the JVM. Choosing a minimal framework (Javalin, Spark, Helidon SE) versus a full-stack framework (Spring Boot, Quarkus with extensions, Micronaut with all features) makes a bigger difference than choosing a different programming language.

volta proves this: same JVM, same language, 17x less memory than Keycloak.

---

## Further reading

- [java.md](java.md) -- The Java programming language itself.
- [keycloak.md](keycloak.md) -- The heavy JVM application volta avoids.
- [fat-jar.md](fat-jar.md) -- How volta packages into a single JAR.
- [hikaricp.md](hikaricp.md) -- The lightweight connection pool volta uses.
