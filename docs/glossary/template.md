# Template

[日本語版はこちら](template.ja.md)

---

## What is it?

A template is an [HTML](html.md) document with placeholders that get filled in with real data before being sent to the [browser](browser.md). Instead of writing separate HTML pages for every user, you write one template and let the [server](server.md) insert the right data each time someone requests the page.

Think of it like a form letter. A form letter has blanks: "Dear ______, your account balance is ______." The letter is written once. When it is time to mail it, the system fills in each person's name and balance. The template is the letter with blanks. The data fills the blanks. The result is a personalized page for each user.

volta-auth-proxy uses [jte](jte.md) (Java Template Engine) as its template engine. Other popular template engines include Thymeleaf, [FreeMarker](freemarker.md), Mustache, Handlebars, and EJS.

---

## Why does it matter?

Without templates, generating dynamic HTML would mean:

- Concatenating strings in [Java](java.md) code: `"<h1>" + userName + "</h1>"` -- unreadable, error-prone, [XSS](xss.md)-vulnerable
- Writing separate HTML files for every possible state -- impossible to maintain
- Mixing business logic with presentation logic -- violating separation of concerns

Templates solve all three problems:

- **Readable** -- Templates look like HTML with small insertions
- **Maintainable** -- One template serves infinite variations
- **Secure** -- Good template engines auto-escape output, preventing XSS

---

## How does it work?

### The template rendering cycle

```
  Template File (login.jte)          Data (Java object)
  ─────────────────────────          ──────────────────
  <h1>Welcome to ${tenantName}</h1>  tenantName = "ACME Corp"
  <p>Hello, ${userName}</p>          userName = "Taro Yamada"
           │                                  │
           └──────────┬───────────────────────┘
                      │
                      ▼
               Template Engine
              (fills placeholders)
                      │
                      ▼
          Rendered HTML (sent to browser)
          ──────────────────────────────
          <h1>Welcome to ACME Corp</h1>
          <p>Hello, Taro Yamada</p>
```

### Placeholders and expressions

Templates use special syntax to mark where data should be inserted:

```html
<!-- jte syntax -->
<h1>${tenantName}</h1>              <!-- simple value -->
<p>Role: ${user.roles().get(0)}</p> <!-- method call -->

<!-- Conditional rendering -->
@if(user.isAdmin())
    <a href="/admin">Admin Panel</a>
@endif

<!-- Loops -->
@for(var member : members)
    <tr>
        <td>${member.name()}</td>
        <td>${member.role()}</td>
    </tr>
@endfor
```

### Auto-escaping (XSS protection)

A good template engine automatically escapes HTML special characters:

```
  User input:     <script>alert('hacked')</script>
  Without escape: <script>alert('hacked')</script>   ← RUNS as JavaScript!
  With escape:    &lt;script&gt;alert('hacked')&lt;/script&gt;  ← displayed as text
```

jte auto-escapes by default. If a user's name is `<script>alert('xss')</script>`, the template renders it as harmless text, not executable code.

### Template engine comparison

| Engine | Language | Type-safe? | Auto-escape? | Used by |
|--------|----------|-----------|-------------|---------|
| [jte](jte.md) | Java | Yes | Yes | volta-auth-proxy |
| Thymeleaf | Java | No | Yes | Spring Boot (common) |
| [FreeMarker](freemarker.md) | Java | No | Optional | Keycloak |
| JSP | Java | No | No (default) | Legacy Java apps |
| EJS | JavaScript | No | No | Express.js apps |
| Jinja2 | Python | No | Yes | Flask/Django |

### Type-safe templates (jte's advantage)

Traditional template engines accept any object and fail at runtime if the data is wrong. [Type-safe](type-safe.md) templates like jte check at [compile](compile.md) time:

```
  Traditional (runtime error):
  ──────────────────────────
  Template:  ${user.nmae}        ← typo: "nmae" instead of "name"
  Compile:   ✓ no error          ← compiler doesn't check templates
  Runtime:   ✗ NullPointerException on line 47   ← user finds the bug

  Type-safe / jte (compile error):
  ─────────────────────────────
  Template:  ${user.nmae}        ← typo: "nmae" instead of "name"
  Compile:   ✗ error: cannot find symbol "nmae"  ← developer finds the bug
  Runtime:   never reached        ← bug caught before deployment
```

---

## How does volta-auth-proxy use it?

### jte templates for auth pages

volta uses jte templates for its server-rendered pages:

```
  volta templates (src/main/jte/):
  ├── login.jte           → Login page with Google OIDC button
  ├── tenant-select.jte   → Multi-tenant selector after login
  ├── invite-accept.jte   → Invitation acceptance page
  ├── error.jte           → Error display page
  └── layout/
      └── main.jte        → Shared layout (header, footer, CSS)
```

### Rendering a template in a handler

In a Javalin handler, rendering a template looks like:

```java
app.get("/auth/login", ctx -> {
    var model = new LoginPage(
        tenantName,     // "ACME Corp"
        googleClientId, // for the sign-in button
        csrfToken       // anti-CSRF protection
    );
    ctx.render("login.jte", Map.of("page", model));
});
```

The handler creates a data object, passes it to the template, and jte renders the HTML. The handler never builds HTML strings manually.

### Layout templates (composition)

volta uses a layout template to avoid repeating the HTML boilerplate:

```html
<!-- layout/main.jte -->
<!DOCTYPE html>
<html lang="en">
<head>
    <title>${title}</title>
    <link rel="stylesheet" href="/public/style.css">
</head>
<body>
    <main>
        ${content}
    </main>
</body>
</html>
```

Individual page templates extend this layout, providing only their unique content. This means changing the header or footer affects all pages at once.

### Why server-rendered templates for auth pages?

volta deliberately uses server-rendered templates instead of a [SPA](spa.md) for authentication pages:

1. **No JavaScript dependency** -- Login must work even if JavaScript is broken or blocked
2. **No XSS from client rendering** -- Server-generated HTML cannot contain injected scripts
3. **Redirect-friendly** -- OIDC flows involve HTTP redirects, which work naturally with server pages
4. **Simpler** -- Auth pages are simple forms; a SPA framework would be overkill

---

## Common mistakes and attacks

### Mistake 1: Disabling auto-escaping

Some template engines let you output "raw" HTML with special syntax (e.g., `$unsafe{value}` in jte, `{!! value !!}` in Blade). Using this with user-controlled data opens [XSS](xss.md) vulnerabilities. Only use raw output for trusted, developer-controlled HTML.

### Mistake 2: Putting business logic in templates

Templates should only handle presentation. Logic like "can this user see the admin panel?" should be decided in the handler, not in the template:

```
  WRONG (logic in template):
  @if(db.query("SELECT role FROM users WHERE id = " + userId) == "ADMIN")
      <a href="/admin">Admin</a>
  @endif

  RIGHT (logic in handler, template just renders):
  // Handler:
  model.showAdminLink = user.isAdmin();

  // Template:
  @if(page.showAdminLink)
      <a href="/admin">Admin</a>
  @endif
```

### Attack 1: Server-Side Template Injection (SSTI)

If user input is inserted into the template itself (not just into the data), an attacker can execute arbitrary code:

```
  VULNERABLE:
  engine.render("Hello " + userInput)
  // If userInput = "${Runtime.exec('rm -rf /')}" → code execution!

  SAFE:
  engine.render("hello.jte", Map.of("name", userInput))
  // userInput is data, not template code
```

volta always passes user data as template parameters, never as part of the template string itself.

### Mistake 3: Not pre-compiling templates

Template engines can compile templates at startup (fast) or on every request (slow). jte supports pre-compilation during the [build](build.md) step (`mvn compile`), so templates are compiled once, not on every page load. volta uses pre-compiled templates for production performance.

---

## Further reading

- [jte.md](jte.md) -- The specific template engine volta uses.
- [type-safe.md](type-safe.md) -- Why compile-time type checking matters for templates.
- [html.md](html.md) -- The output format of templates.
- [xss.md](xss.md) -- The attack that auto-escaping prevents.
- [freemarker.md](freemarker.md) -- An alternative template engine (used by Keycloak).
- [frontend-backend.md](frontend-backend.md) -- Templates bridge the gap between backend data and frontend display.
- [spa.md](spa.md) -- The alternative to server-rendered templates.
