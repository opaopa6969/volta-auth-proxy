# Variable

[日本語版はこちら](variable.ja.md)

---

## What is it?

A variable is a named container that holds a value. You give it a name, put a value inside, and use the name to refer to the value later. In programming, variables store data like numbers, text, user IDs, and configuration values. When the program runs, it reads the value from the variable instead of having the value hardcoded.

Think of it like a labeled box. You write "port number" on the outside of the box and put "7070" inside. Later, when you need the port number, you look at the box labeled "port number" and find 7070. You can also change what is inside the box without changing the label. Tomorrow you might put "8080" in the same box.

In volta-auth-proxy, the most important type of variable is the **[environment variable](environment-variable.md)** -- a variable set outside the program that configures how it behaves.

---

## Why does it matter?

Without variables:

- Every value would be hardcoded: `port = 7070` everywhere. Changing the port means editing every file.
- Configuration would be impossible. You could not run the same program with different settings.
- Data processing would be impossible. You could not store a user's ID from a request and use it later to query the database.
- Secrets would be embedded in source code. Your Google Client ID would be in Main.java, visible to anyone who reads the code.

Variables enable:

- **Flexibility** -- Change behavior without changing code
- **Reusability** -- Same code, different values
- **Abstraction** -- Name a concept once, use it everywhere
- **Security** -- Keep secrets outside the code, in [environment variables](environment-variable.md)

---

## How does it work?

### Variable basics in Java

```java
// Declare and assign a variable
String tenantName = "ACME Corp";
int port = 7070;
UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
boolean isAdmin = true;

// Use the variable
System.out.println("Welcome to " + tenantName);  // "Welcome to ACME Corp"
app.start(port);                                    // starts on 7070
```

### Variable types in Java

In a [type-safe](type-safe.md) language like Java, every variable has a type:

| Type | What it holds | Example |
|------|--------------|---------|
| `String` | Text | `"ACME Corp"`, `"taro@example.com"` |
| `int` | Integer number | `7070`, `5`, `0` |
| `long` | Large integer | `1711899700L` (Unix timestamp) |
| `boolean` | True or false | `true`, `false` |
| `UUID` | Unique identifier | `550e8400-e29b-41d4-a716-446655440000` |
| `Duration` | Time period | `Duration.ofMinutes(5)` |
| `List<Role>` | List of roles | `[ADMIN, MEMBER]` |

### Scope: where variables live

Variables exist within a specific scope -- the region of code where they are accessible:

```java
public class Example {
    // Class-level variable: accessible throughout the class
    private final String appName = "volta-auth-proxy";

    public void handleRequest(Context ctx) {
        // Method-level variable: accessible only inside this method
        String userId = ctx.pathParam("userId");

        if (userId != null) {
            // Block-level variable: accessible only inside this if-block
            UUID parsed = UUID.fromString(userId);
        }
        // 'parsed' does not exist here -- out of scope
    }
}
```

```
  ┌─────────────────────────────────────────┐
  │  Class scope                             │
  │  appName = "volta-auth-proxy"            │
  │                                          │
  │  ┌───────────────────────────────────┐   │
  │  │  Method scope                      │   │
  │  │  userId = "550e8400-..."           │   │
  │  │                                    │   │
  │  │  ┌─────────────────────────────┐   │   │
  │  │  │  Block scope                 │   │   │
  │  │  │  parsed = UUID(550e8400...) │   │   │
  │  │  └─────────────────────────────┘   │   │
  │  └───────────────────────────────────┘   │
  └─────────────────────────────────────────┘
```

### Mutable vs. immutable variables

```java
// Mutable (can change)
String name = "Taro";
name = "Hanako";           // ✓ allowed

// Immutable (cannot change after assignment)
final String name = "Taro";
name = "Hanako";           // ✗ COMPILE ERROR: cannot assign to final variable
```

volta prefers `final` variables wherever possible. Immutable variables are safer because their value cannot be accidentally changed by another part of the code.

### Environment variables

[Environment variables](environment-variable.md) are a special category -- they are set outside the program, in the OS or container environment:

```bash
# Set environment variables BEFORE running volta
export VOLTA_PORT=7070
export GOOGLE_CLIENT_ID=abc123.apps.googleusercontent.com
export DATABASE_URL=jdbc:postgresql://localhost:5432/volta

java -jar volta-auth-proxy.jar
```

Inside Java, these are read with `System.getenv()`:

```java
int port = Integer.parseInt(System.getenv("VOLTA_PORT"));
String clientId = System.getenv("GOOGLE_CLIENT_ID");
String dbUrl = System.getenv("DATABASE_URL");
```

---

## How does volta-auth-proxy use it?

### Configuration via environment variables

volta reads all its configuration from environment variables, not from hardcoded values:

```
  Environment Variable              What it configures
  ────────────────────              ──────────────────
  VOLTA_PORT                        HTTP server port (default: 7070)
  GOOGLE_CLIENT_ID                  Google OIDC client ID
  GOOGLE_CLIENT_SECRET              Google OIDC client secret
  DATABASE_URL                      PostgreSQL connection string
  SESSION_TIMEOUT                   Session duration (default: 8h)
  JWT_EXPIRY                        JWT lifetime (default: 5m)
  VOLTA_BASE_URL                    Public URL of volta
```

This means the same JAR file can run in different environments (development, staging, production) by changing only the environment variables.

### Variables in route handlers

Request-scoped variables hold data for the duration of one request:

```java
app.get("/api/v1/users/me", ctx -> {
    // These variables exist only during this request
    UUID userId = ctx.attribute("userId");       // set by auth middleware
    UUID tenantId = ctx.attribute("tenantId");   // set by auth middleware

    UserInfo user = userService.findById(userId, tenantId);
    ctx.json(user);
});
```

### Variables in JWT claims

The [JWT](jwt.md) claims are essentially a set of named values (variables) that travel with the token:

```json
{
  "sub": "550e8400-...",       // variable: who is this user?
  "volta_tid": "abcd1234-...", // variable: which tenant?
  "volta_roles": ["ADMIN"],    // variable: what roles?
  "exp": 1711900000            // variable: when does this expire?
}
```

### Template variables

[jte](jte.md) [templates](template.md) use variables to fill in dynamic content:

```java
// Handler passes variables to template
ctx.render("dashboard.jte", Map.of(
    "userName", "Taro Yamada",
    "tenantName", "ACME Corp",
    "memberCount", 42
));
```

```html
<!-- Template uses the variables -->
<h1>Welcome, ${userName}</h1>
<p>Tenant: ${tenantName} (${memberCount} members)</p>
```

---

## Common mistakes and attacks

### Mistake 1: Hardcoding values instead of using variables

```java
// BAD: hardcoded -- cannot change without recompiling
String dbUrl = "jdbc:postgresql://localhost:5432/volta";

// GOOD: environment variable -- change without recompiling
String dbUrl = System.getenv("DATABASE_URL");
```

### Mistake 2: Not initializing variables

In Java, local variables must be assigned before use:

```java
String name;
System.out.println(name);  // ✗ COMPILE ERROR: variable might not be initialized
```

### Mistake 3: Reusing variable names for different purposes

```java
// Confusing -- what does 'id' mean here?
String id = ctx.pathParam("userId");
// ... 50 lines later ...
id = tenant.getId().toString();  // now it's a tenant ID?!

// Clear -- each variable has one purpose
String userId = ctx.pathParam("userId");
String tenantId = tenant.getId().toString();
```

### Attack 1: Environment variable leakage

If an attacker gains access to the [process](process.md) environment (e.g., through a debug endpoint that dumps `System.getenv()`), they see all secrets. Never expose environment variables through an API. volta does not have any endpoint that reveals configuration.

### Mistake 4: Using global mutable variables

Global mutable variables that can be changed from anywhere create race conditions in multi-threaded servers:

```java
// DANGEROUS in a multi-threaded server
static String currentUser = null;  // shared across all threads!

// SAFE -- use request-scoped variables
ctx.attribute("userId", userId);   // per-request, per-thread
```

volta avoids global mutable state. Configuration is loaded once at startup into `final` fields. Request data is stored in Javalin's per-request context.

---

## Further reading

- [environment-variable.md](environment-variable.md) -- The most important type of variable for volta configuration.
- [type-safe.md](type-safe.md) -- How Java's type system ensures variables hold the right data.
- [yaml.md](yaml.md) -- Configuration files that define named values (like variables).
- [template.md](template.md) -- Variables in jte templates.
- [uuid.md](uuid.md) -- A common variable type in volta (identifiers).
- [java.md](java.md) -- The language whose variable system volta uses.
