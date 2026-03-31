# SDK (Software Development Kit)

[日本語版はこちら](sdk.ja.md)

---

## What is it in one sentence?

An SDK is a pre-built library that gives you ready-made functions to interact with a service, so you do not have to write all the low-level code yourself -- like a cooking kit that comes with pre-measured ingredients and instructions instead of making you buy everything separately.

---

## The cooking kit analogy

Say you want to make ramen. You have two options:

**Option A: From scratch**
- Go to the store and buy flour, kansui, eggs, pork bones, soy sauce, mirin, nori, green onions...
- Make the noodles by hand
- Simmer the broth for 12 hours
- Figure out the right proportions by trial and error

**Option B: Ramen kit**
- Open the box
- Follow the instructions: "Boil water. Add noodle packet. Add soup base. Top with included toppings."
- Done in 15 minutes

The ramen kit is an SDK. Someone already figured out the hard parts and packaged them for you. You still make ramen (interact with the service), but you skip the tedious, error-prone work.

---

## Why you need an SDK

Without an SDK, talking to volta-auth-proxy from your app requires writing a lot of repetitive code:

```
  Without SDK (you write everything):
  ───────────────────────────────────
  1. Read the X-Volta-User-Id header from the request
  2. Read the X-Volta-Tenant-Id header
  3. Read the X-Volta-Roles header
  4. Parse the roles string into a list
  5. Build an HTTP client to call volta's Internal API
  6. Add the Authorization header with the service token
  7. Handle network errors, timeouts, retries
  8. Parse JSON responses
  9. Handle error responses (400, 401, 403, 404, 500)
  10. Map JSON to your language's objects

  That's a LOT of code that has nothing to do with
  your app's actual purpose.
```

```
  With SDK (pre-built for you):
  ─────────────────────────────
  VoltaUser user = VoltaAuth.getUser(request);
  String tenantId = user.getTenantId();
  boolean isAdmin = user.hasRole("ADMIN");
  List<Member> members = volta.listMembers(tenantId);

  Four lines. Done. Back to building your app.
```

---

## volta's two SDKs

volta provides SDKs for two languages:

### volta-sdk (Java)

For Java/Kotlin apps (Spring Boot, Javalin, etc.):

```java
// Get the current user from volta headers
VoltaUser user = VoltaAuth.getUser(request);

// Check the user's identity
String email = user.getEmail();        // "taro@acme.com"
String tenantId = user.getTenantId();  // "acme-uuid"
String userId = user.getUserId();      // "taro-uuid"

// Check permissions
if (user.hasRole("ADMIN")) {
    // Show admin features
}

// Call volta's Internal API
VoltaClient volta = new VoltaClient("http://volta:7070", serviceToken);
List<Member> members = volta.listMembers(tenantId);
```

### volta-sdk-js (JavaScript/TypeScript)

For Node.js apps (Express, Next.js, etc.):

```javascript
// Get the current user from volta headers
const user = VoltaAuth.getUser(req);

// Check the user's identity
const email = user.email;        // "taro@acme.com"
const tenantId = user.tenantId;  // "acme-uuid"
const userId = user.userId;      // "taro-uuid"

// Check permissions
if (user.hasRole("ADMIN")) {
    // Show admin features
}

// Call volta's Internal API
const volta = new VoltaClient("http://volta:7070", serviceToken);
const members = await volta.listMembers(tenantId);
```

---

## What the SDK saves you from

Here is a real comparison of what you would write without the SDK versus with it:

**Checking if a user is an admin (without SDK):**
```java
String rolesHeader = request.getHeader("X-Volta-Roles");
if (rolesHeader == null) {
    throw new UnauthorizedException("No roles header");
}
List<String> roles = Arrays.asList(rolesHeader.split(","));
boolean isAdmin = roles.contains("ADMIN") || roles.contains("OWNER");
if (!isAdmin) {
    throw new ForbiddenException("Admin access required");
}
```

**Same thing with SDK:**
```java
VoltaUser user = VoltaAuth.getUser(request);
if (!user.hasRole("ADMIN")) {
    throw new ForbiddenException("Admin access required");
}
```

The SDK also handles edge cases you might forget:
- What if the header is missing?
- What if the header has unexpected whitespace?
- What about the role hierarchy (OWNER should also pass an ADMIN check)?
- What about null values?

The SDK handles all of these. You just call the method.

---

## A simple example

Say you are building a wiki app with a "delete page" button that only ADMINs and OWNERs should see:

```java
// In your wiki app's delete endpoint
app.delete("/api/pages/:id", ctx -> {
    // Step 1: Get the user (SDK reads volta headers)
    VoltaUser user = VoltaAuth.getUser(ctx.req());

    // Step 2: Check permission (SDK handles role hierarchy)
    if (!user.hasRole("ADMIN")) {
        ctx.status(403).result("Only admins can delete pages");
        return;
    }

    // Step 3: Get the tenant (SDK extracts it from headers)
    String tenantId = user.getTenantId();

    // Step 4: Delete the page (your app's logic)
    pageService.delete(ctx.pathParam("id"), tenantId);
    ctx.status(200).result("Page deleted");
});
```

Without the SDK, Step 1 alone would be 10+ lines of header parsing and validation. With it, it is one line.

---

## Further reading

- [api.md](api.md) -- The volta Internal API that the SDK wraps.
- [downstream-app.md](downstream-app.md) -- The apps that use the SDK.
- [role.md](role.md) -- The role hierarchy that the SDK's `hasRole()` method understands.
