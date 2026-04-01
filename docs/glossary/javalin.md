# Javalin

[日本語版はこちら](javalin.ja.md)

---

## What is it?

Javalin is a lightweight web framework for Java and Kotlin. It provides the essentials for building web applications -- routing, request/response handling, middleware, and WebSocket support -- without the ceremony and complexity of larger frameworks like Spring Boot or Jakarta EE.

Think of it like the difference between a Swiss Army knife and a full workshop. Spring Boot is the workshop: it has every tool imaginable, but you need to learn where everything is, and you pay for the electricity even if you only use the screwdriver. Javalin is the Swiss Army knife: compact, everything you need for most jobs, and you can carry it in your pocket.

Javalin wraps Jetty (the HTTP server) and provides a clean, functional API. You define routes as lambda expressions, add middleware as simple functions, and the framework gets out of your way. There is no annotation magic, no dependency injection container, no XML configuration. What you write is what runs.

---

## Why does it matter?

Framework choice has long-term consequences for every aspect of a project:

| Concern | Javalin | Spring Boot |
|---------|---------|-------------|
| **Startup time** | ~200ms | 3-8 seconds |
| **Learning curve** | Hours | Weeks |
| **Magic** | None (explicit code) | Heavy (annotations, auto-config) |
| **Dependency count** | ~10 | 100+ |
| **JAR size** | ~5 MB | 30+ MB |
| **Debugging** | Read the code | Read the docs, then the source, then Stack Overflow |
| **Configuration** | Programmatic (Java code) | Annotations + YAML + properties + profiles |

For projects where **control** matters more than **convention**, Javalin is the right tool. You trade Spring Boot's ecosystem and community size for complete understanding of what your code does.

---

## How does it work?

### Basic Javalin application

```java
  import io.javalin.Javalin;

  public class Main {
      public static void main(String[] args) {
          var app = Javalin.create()
              .get("/", ctx -> ctx.result("Hello"))
              .get("/healthz", ctx -> ctx.json(Map.of("status", "ok")))
              .post("/api/users", ctx -> {
                  var body = ctx.bodyAsClass(UserRequest.class);
                  // handle request
                  ctx.json(response);
              })
              .start(8080);
      }
  }
```

Compare this to the Spring Boot equivalent:

```java
  // Spring Boot requires:
  // - @SpringBootApplication on the main class
  // - @RestController on a controller class
  // - @GetMapping / @PostMapping annotations
  // - Component scanning to discover the controller
  // - Auto-configuration to set up Tomcat
  // - Properties file for port configuration
  // Result: 6 files for what Javalin does in 1
```

### Javalin's architecture

```
  Your code (Main.java)
       │
       ▼
  ┌─────────────────────┐
  │  Javalin             │  ← Thin wrapper
  │  - Route matching    │
  │  - Request/Response  │
  │  - Middleware chain   │
  │  - Exception handler │
  └─────────────────────┘
       │
       ▼
  ┌─────────────────────┐
  │  Jetty               │  ← Embedded HTTP server
  │  - HTTP parsing      │
  │  - TLS/SSL           │
  │  - Thread pool       │
  └─────────────────────┘
       │
       ▼
  TCP/HTTP traffic
```

### Middleware in Javalin

```java
  // Middleware is a simple before/after function:
  app.before(ctx -> {
      // runs before every request
      logger.info("{} {}", ctx.method(), ctx.path());
  });

  app.before("/api/*", ctx -> {
      // runs before /api/* requests only
      if (ctx.sessionAttribute("user") == null) {
          throw new UnauthorizedResponse();
      }
  });

  app.after(ctx -> {
      // runs after every request
      ctx.header("X-Request-Id", UUID.randomUUID().toString());
  });
```

### Why Javalin, not other lightweight options?

| Framework | Language | Startup | Notes |
|-----------|----------|---------|-------|
| **Javalin** | Java/Kotlin | ~200ms | Jetty-based, functional API |
| **Spark Java** | Java | ~200ms | Similar to Javalin but less maintained |
| **Vert.x** | Java | ~500ms | Reactive, event-loop -- more complex |
| **Micronaut** | Java | ~1s | Compile-time DI, AOT -- more magic |
| **Quarkus** | Java | ~800ms | GraalVM native -- more tooling |
| **Express.js** | Node.js | ~200ms | Different language/ecosystem |

Javalin was chosen for volta because it aligns with the "choose the hell you understand" philosophy: simple enough to read every line, powerful enough for production.

---

## How does volta-auth-proxy use it?

volta uses Javalin 6.x as its web framework. The entire application is configured in a single `Main.java` file.

### volta's Javalin setup

```java
  var app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.staticFiles.add("/public");
  });

  // Middleware
  app.before(SessionMiddleware::handle);
  app.before("/api/*", AuthMiddleware::handle);

  // Routes
  app.get("/healthz", HealthController::check);
  app.get("/login", AuthController::login);
  app.get("/callback", AuthController::callback);
  app.get("/api/v1/tenants/{tid}/members", MemberController::list);
  app.post("/api/v1/tenants/{tid}/members/invite", MemberController::invite);
  // ... more routes

  app.start(config.port());
```

### Why this design works for volta

| Javalin feature | volta benefit |
|----------------|--------------|
| **Explicit routes** | Every endpoint visible in one file. No scanning surprises. |
| **Lambda handlers** | Handlers are plain Java methods. Easy to test, easy to debug. |
| **Before/after hooks** | Session, CSRF, and auth checks without annotation magic. |
| **Embedded Jetty** | Single JAR deployment. No external server needed. |
| **No DI container** | Objects created with `new`. Constructor = the wiring diagram. |
| **JSON via Jackson** | `ctx.json()` and `ctx.bodyAsClass()` for clean serialization. |

### The "control is king" alignment

Javalin embodies volta's philosophy:

```
  Spring Boot approach:
    "Let the framework handle it."
    @EnableWebSecurity
    @EnableOAuth2Client
    @EnableGlobalMethodSecurity
    (What do these actually do? Read 500 pages of docs.)

  Javalin approach:
    "I handle it. Explicitly."
    app.before("/api/*", ctx -> {
        var session = SessionStore.get(ctx.cookie("__volta_session"));
        if (session == null) throw new UnauthorizedResponse();
        ctx.attribute("session", session);
    });
    (What does this do? Read the code. It is right there.)
```

---

## Common mistakes and attacks

### Mistake 1: Using Javalin for the wrong project

Javalin is excellent for small-to-medium applications where you want control. For a 200-person team building an enterprise SaaS with 500 endpoints, Spring Boot's conventions and ecosystem may be the better choice. Know your scale.

### Mistake 2: Missing Spring Boot's ecosystem

Javalin does not have Spring Security, Spring Data, Spring Cloud, or the thousands of Spring-compatible libraries. You must build or find alternatives yourself. This is a feature (less magic) and a cost (more work) simultaneously.

### Mistake 3: Not adding error handling

Javalin's simplicity means it does not add error handling for you. An unhandled exception returns a raw 500 error. Always add:

```java
  app.exception(Exception.class, (e, ctx) -> {
      logger.error("Unhandled error", e);
      ctx.status(500).json(Map.of("error", "Internal server error"));
  });
```

### Mistake 4: Assuming Javalin cannot scale

Javalin runs on Jetty, which handles millions of requests per day for organizations like Eclipse Foundation. The framework is lightweight; the HTTP server underneath is battle-tested.

---

## Further reading

- [startup.md](startup.md) -- How Javalin enables volta's ~200ms startup.
- [greenfield.md](greenfield.md) -- Why Javalin was chosen for a greenfield project.
- [yagni.md](yagni.md) -- Javalin's minimal approach aligns with YAGNI.
- [Javalin Documentation](https://javalin.io/) -- Official docs and tutorials.
- [Javalin GitHub](https://github.com/javalin/javalin) -- Source code and examples.
