# jte (Java Template Engine)

[日本語版はこちら](jte.ja.md)

---

## What is it?

jte (Java Template Engine) is a modern, type-safe template engine for Java that generates HTML by combining templates with data from your application.

Think of it like a mail merge in a word processor. You write a letter template with blanks: "Dear _____, your order #_____ is ready." Then you feed in a list of names and order numbers, and the word processor fills in the blanks for each person. jte works the same way, but for web pages: you write an HTML template with placeholders, and jte fills them in with real data (user names, tenant info, error messages, etc.) for each request.

---

## Why does it matter?

Web applications need to generate HTML that includes dynamic content: the logged-in user's name, a list of team members, error messages, and so on. There are many ways to do this, and the choice matters more than you might think.

### The alternatives

| Template engine | Language | Known for |
|----------------|---------|-----------|
| **jte** | Java | Type-safe, fast, modern syntax |
| **Thymeleaf** | Java | Spring Boot's default, "natural templates" |
| **FreeMarker** | Java | Old (2002), powerful but verbose, [Keycloak](keycloak.md) uses it |
| **JSP** | Java | Ancient (1999), Java's original template system |
| **Pug/EJS** | Node.js | Popular in Express.js apps |
| **Jinja2** | Python | Flask/Django ecosystem |

### Why volta chose jte over the alternatives

**jte vs FreeMarker (Keycloak's choice):**

FreeMarker is what Keycloak uses. It works, but it is from 2002 and shows its age:

```
  FreeMarker (Keycloak themes):
  ┌──────────────────────────────────────┐
  │ <#if user??>                         │  ← Weird null-check syntax
  │   <h1>Hello ${user.name}</h1>        │
  │ </#if>                               │
  │ <#list items as item>                │
  │   <li>${item.label}</li>             │
  │ </#list>                             │  ← No compile-time checks
  └──────────────────────────────────────┘

  jte (volta):
  ┌──────────────────────────────────────┐
  │ @if(user != null)                    │  ← Familiar Java syntax
  │   <h1>Hello ${user.name()}</h1>      │
  │ @endif                               │
  │ @for(var item : items)               │
  │   <li>${item.label()}</li>           │
  │ @endfor                              │  ← Compile-time type checking
  └──────────────────────────────────────┘
```

**jte vs Thymeleaf (Spring Boot's default):**

Thymeleaf uses "natural templates" that are valid HTML even without processing:

```html
<!-- Thymeleaf -->
<h1 th:text="${title}">Default Title</h1>
<div th:each="user : ${users}">
  <span th:text="${user.name}">John Doe</span>
</div>
```

This is elegant but has a cost: errors are only caught at runtime. If you misspell `${usr.name}` instead of `${user.name}`, you find out when a user hits that page -- not at build time.

jte catches these errors at compile time, before you deploy.

---

## How jte works

### Type-safe parameters

Every jte template declares its parameters at the top. The Java compiler checks that you pass the right types:

```html
@param String title
@param java.util.Map<String, String> inviteContext
@param String startUrl
<!doctype html>
<html lang="ja">
<head>
    <meta charset="utf-8">
    <title>${title}</title>
</head>
<body>
<main>
    <h1>Login</h1>
    @if(inviteContext != null)
        <p><strong>${inviteContext.get("tenantName")}</strong> invited you.</p>
    @endif
    <a class="button" href="${startUrl}">Sign in with Google</a>
</main>
</body>
</html>
```

The `@param` lines at the top are the key. They tell jte (and the Java compiler) exactly what data this template expects. If your Java code tries to render this template without providing `startUrl`, you get a compile error, not a runtime crash.

### Automatic HTML escaping

jte automatically escapes HTML special characters in output. This means:

```
  If user.name() returns: <script>alert('XSS')</script>

  jte renders: &lt;script&gt;alert('XSS')&lt;/script&gt;

  The browser shows the text literally, not executing the script.
```

This protects against [XSS](xss.md) attacks by default. You have to explicitly opt out of escaping (using `$unsafe{...}`), which makes the dangerous choice visible in code reviews.

---

## How volta uses jte

volta-auth-proxy uses jte for all its HTML pages:

```
src/main/jte/
├── layout/
│   └── base.jte              ← Shared layout (header, footer, CSS)
├── auth/
│   ├── login.jte             ← Login page ("Sign in with Google")
│   ├── callback.jte          ← OAuth callback processing
│   ├── tenant-select.jte     ← Tenant selector (when user has multiple)
│   ├── invite-consent.jte    ← "Accept this invitation?" page
│   └── sessions.jte          ← Active sessions management
├── admin/
│   ├── members.jte           ← Tenant member management
│   ├── invitations.jte       ← Invitation management
│   ├── webhooks.jte          ← Webhook configuration
│   ├── tenants.jte           ← Tenant administration
│   ├── users.jte             ← User administration
│   ├── audit.jte             ← Audit log viewer
│   └── idp.jte               ← Identity provider configuration
└── error/
    └── error.jte             ← Error pages
```

### Why this matters for volta's philosophy

One of volta's core principles is that you have **full control** over your login UI. With Auth0, you customize within their limits. With Keycloak, you wrestle with FreeMarker themes. With volta:

1. Open the `.jte` file
2. Edit the HTML however you want
3. Add your own CSS, JavaScript, branding
4. Restart volta (or use hot-reload in development)
5. Done

There is no theme system, no layout inheritance rules to learn, no "override this file in the theme directory" dance. It is just HTML templates with data placeholders. If you know HTML, you can customize volta's UI.

---

## Simple example

Here is a minimal jte template:

```html
@param String userName
@param String tenantName
@param java.util.List<String> roles

<!doctype html>
<html>
<body>
    <h1>Welcome, ${userName}</h1>
    <p>You are in the <strong>${tenantName}</strong> organization.</p>

    <h2>Your roles:</h2>
    <ul>
    @for(String role : roles)
        <li>${role}</li>
    @endfor
    </ul>
</body>
</html>
```

And the Java code that renders it:

```java
ctx.render("dashboard.jte", Map.of(
    "userName", "Taro Yamada",
    "tenantName", "ACME Corp",
    "roles", List.of("ADMIN", "MEMBER")
));
```

The output is clean, type-checked HTML. If you forget to pass `tenantName`, the compiler tells you before you deploy.

---

## Further reading

- [jte Official Documentation](https://jte.gg/) -- Full jte docs with examples.
- [keycloak.md](keycloak.md) -- Why FreeMarker templates are painful (what volta avoids).
- [xss.md](xss.md) -- The attack that jte's auto-escaping prevents.
- [config-hell.md](config-hell.md) -- volta's philosophy of simplicity extends to templates.
