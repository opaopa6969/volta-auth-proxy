# Framework

[日本語版はこちら](framework.ja.md)

---

## What is it?

A framework is a pre-built structure that provides the skeleton of an application. Instead of writing everything from scratch -- how to listen for network requests, how to parse URLs, how to send responses -- you plug your code into the framework's structure, and it handles the plumbing for you.

Think of it like a house frame. When you build a house, you don't start by figuring out how to make walls stand up. The frame is already there -- walls, roof structure, floor joists. You decide where the kitchen goes, what color to paint the walls, and what furniture to put inside. A framework is the house frame. Your business logic is the furniture.

The key distinction between a framework and a [library](library.md) is **inversion of control**. With a library, your code calls the library. With a framework, the framework calls your code. You write handler functions, register them, and the framework decides when to invoke them. This is sometimes called the "Hollywood Principle" -- don't call us, we'll call you.

---

## Why does it matter?

Without a framework, building a web application means:

- Writing a raw [HTTP](http.md) server from scratch
- Parsing request URLs, query parameters, and bodies by hand
- Managing threads and concurrency yourself
- Implementing routing (matching URL paths to handler code)
- Handling error responses, content types, and status codes manually
- Reinventing security basics (CSRF protection, header sanitization)

Frameworks eliminate thousands of lines of boilerplate so you can focus on what makes your app unique -- the [business logic](business-logic.md).

---

## How does it work?

### The core pattern

Every web framework follows roughly the same pattern:

```
  Incoming HTTP Request
         │
         ▼
  ┌──────────────────┐
  │    Framework      │
  │                   │
  │  1. Parse request │
  │  2. Match route   │──── GET /users/:id  ->  your handler
  │  3. Run middleware │──── auth check, logging, etc.
  │  4. Call handler   │──── YOUR CODE runs here
  │  5. Send response  │
  └──────────────────┘
         │
         ▼
  HTTP Response back to client
```

You register routes and handlers. The framework takes care of everything else.

### Framework vs. library: the control flow

```
  Library (you are in control):          Framework (it is in control):
  ┌───────────────┐                      ┌───────────────┐
  │  Your Code    │                      │  Framework    │
  │               │                      │               │
  │  result =     │                      │  onRequest -> │──> your handler()
  │   lib.doX()   │──> Library           │  onError ->   │──> your errorHandler()
  │               │                      │  onStart ->   │──> your init()
  │  use result   │<── returns           │               │
  └───────────────┘                      └───────────────┘

  You call the library.                  The framework calls you.
```

### Types of frameworks

| Type | Examples | Purpose |
|------|----------|---------|
| Web framework (lightweight) | Javalin, Express, Sinatra, Flask | Minimal routing + HTTP handling |
| Web framework (full-stack) | Spring Boot, Django, Rails | Routing + ORM + templating + auth + everything |
| Frontend framework | React, Vue, Angular | Browser-side UI rendering |
| Testing framework | JUnit, Jest, pytest | Structuring and running tests |

### Lightweight vs. full-stack

This is an important distinction, especially for volta-auth-proxy's philosophy:

```
  Lightweight (Javalin):              Full-stack (Spring Boot):
  ┌─────────────────────┐            ┌─────────────────────────────┐
  │  HTTP routing        │            │  HTTP routing                │
  │  Request/response    │            │  Request/response            │
  │  Middleware hooks     │            │  Middleware (filters)        │
  │  WebSocket support   │            │  Dependency injection        │
  │                      │            │  ORM (JPA/Hibernate)         │
  │  That's it.          │            │  Security (Spring Security)  │
  │  You pick the rest.  │            │  Templating (Thymeleaf)      │
  │                      │            │  Caching framework           │
  └─────────────────────┘            │  Job scheduling              │
                                     │  50+ auto-configurations     │
                                     └─────────────────────────────┘
```

A lightweight framework gives you just enough to handle HTTP. You choose your own [database](database.md) library, your own [template](template.md) engine, your own caching layer. A full-stack framework bundles everything together, which can be convenient but also leads to [config hell](config-hell.md).

### The Javalin example

Javalin is the framework volta-auth-proxy uses. Here is the simplest possible Javalin app:

```java
import io.javalin.Javalin;

public class Main {
    public static void main(String[] args) {
        var app = Javalin.create().start(7070);
        app.get("/hello", ctx -> ctx.result("Hello World"));
    }
}
```

In 4 lines, you have a working [HTTP](http.md) [server](server.md) listening on [port](port.md) 7070 that responds to `GET /hello`. The framework handles:

- Starting a Jetty server
- Listening for TCP connections
- Parsing HTTP requests
- Matching the URL path `/hello` to your lambda
- Sending the response with correct headers

You wrote zero networking code.

---

## How does volta-auth-proxy use it?

volta-auth-proxy uses **Javalin** as its web framework. The choice is deliberate -- Javalin is lightweight, has no magic annotations, no dependency injection container, no auto-configuration. You can read the code and understand what it does.

### Application setup in Main.java

```java
var app = Javalin.create(config -> {
    config.staticFiles.add("/public");
    config.jetty.sessionHandler(SessionConfig::fileSessionHandler);
    // template engine, etc.
});
```

The framework is configured with explicit method calls, not [YAML](yaml.md) files or annotation scanning.

### Route registration

Routes are registered directly:

```java
app.get("/auth/login",      ctx -> authController.loginPage(ctx));
app.post("/auth/callback",  ctx -> authController.callback(ctx));
app.get("/api/v1/users/me", ctx -> userController.me(ctx));
```

Each line maps an HTTP method + path to a handler function. No hidden routing files. No convention-over-configuration. You see exactly what URL triggers what code.

### Middleware via before/after hooks

Javalin's [middleware](middleware.md) system uses `before()` and `after()` hooks:

```java
app.before("/api/*", ctx -> authMiddleware.requireAuth(ctx));
app.before("/api/v1/admin/*", ctx -> authMiddleware.requireRole(ctx, Role.ADMIN));
```

These run before the route handler, allowing volta to check authentication and [roles](role.md) without repeating code in every [endpoint](endpoint.md).

### Why Javalin and not Spring Boot?

volta-auth-proxy's philosophy (see [config-hell.md](config-hell.md) and [native-implementation.md](native-implementation.md)):

- **No annotation magic** -- Spring Boot uses `@RestController`, `@Autowired`, `@Configuration`, etc. These annotations trigger behavior that is invisible in the code. Javalin has zero annotations.
- **No dependency injection** -- Spring manages object creation through a container. volta creates objects explicitly with `new`.
- **No auto-configuration** -- Spring Boot scans the classpath and enables features automatically. volta enables features manually in Main.java.
- **Debuggable** -- When something goes wrong in Javalin, the stack trace shows your code, not 47 layers of Spring proxies.

---

## Common mistakes and attacks

### Mistake 1: Choosing a framework based on popularity alone

Spring Boot is the most popular Java web framework. That does not make it the right choice for every project. A single-process identity gateway does not need the complexity of Spring's enterprise features. Choosing a framework that is too large for your use case leads to [complexity of configuration](complexity-of-configuration.md) and [tech debt](what-is-tech-debt.md).

### Mistake 2: Fighting the framework

Every framework has opinions about how code should be structured. If you find yourself writing workarounds for the framework's patterns, you may have chosen the wrong framework. volta chose Javalin specifically because its opinions match volta's philosophy: explicit over implicit, simple over clever.

### Mistake 3: Assuming the framework handles security

A framework provides HTTP plumbing, not security guarantees. Javalin does not automatically validate JWTs, check CSRF tokens, or enforce authorization. volta implements all security checks explicitly in [middleware](middleware.md) and handlers. Never assume the framework "takes care of it."

### Attack 1: Framework-specific vulnerabilities

Frameworks are software, and software has bugs. Spring4Shell (CVE-2022-22965) was a remote code execution vulnerability in Spring Framework. Express.js has had prototype pollution issues. When you use a framework, you inherit its vulnerability surface. Keeping the framework updated is essential.

### Mistake 4: Wrapping the framework in another abstraction

Some teams create a "framework on top of the framework" -- a custom base class, a custom routing layer, etc. This makes the framework harder to upgrade and the code harder for new developers to understand. volta uses Javalin's API directly, without custom wrapper layers.

---

## Further reading

- [library.md](library.md) -- The difference between a framework and a library.
- [middleware.md](middleware.md) -- How frameworks like Javalin handle cross-cutting concerns.
- [java.md](java.md) -- The language volta's framework runs on.
- [jvm.md](jvm.md) -- The runtime that executes Javalin.
- [config-hell.md](config-hell.md) -- Why volta chose a lightweight framework.
- [native-implementation.md](native-implementation.md) -- The philosophy behind building your own vs. using a full-stack framework.
- [Javalin documentation](https://javalin.io/documentation) -- Official Javalin docs.
