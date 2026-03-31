# SameSite (Cookie Attribute)

## What is it?

SameSite is a cookie attribute that controls whether the browser sends the cookie when a request comes from a different website. It helps prevent cross-site attacks by restricting when cookies travel across site boundaries.

There are three possible values:

**`SameSite=Strict`** -- The cookie is NEVER sent with cross-site requests. If you are on `evil.com` and click a link to `your-bank.com`, the browser will not include your bank's cookies. You would appear logged out even though you have an active session.

**`SameSite=Lax`** -- The cookie IS sent with top-level navigations (clicking a link, typing a URL) from other sites, but NOT with background requests (images, iframes, AJAX calls). This is the sweet spot for most applications.

**`SameSite=None`** -- The cookie is sent with ALL cross-site requests. This effectively disables SameSite protection. Requires the `Secure` flag (HTTPS only).

```
  Scenario: User is on evil.com, has a session on your-app.com

  evil.com has a link: <a href="https://your-app.com/dashboard">

  User clicks the link:
    Strict: Cookie NOT sent. User sees login page.
    Lax:    Cookie IS sent. User sees dashboard. (top-level navigation)
    None:   Cookie IS sent. User sees dashboard.

  evil.com has an image: <img src="https://your-app.com/api/delete-account">

  Browser loads the image:
    Strict: Cookie NOT sent. API rejects the request.
    Lax:    Cookie NOT sent. API rejects the request.
    None:   Cookie IS sent. Account gets deleted!
```

## Why does it matter?

SameSite is a defense against CSRF (Cross-Site Request Forgery) attacks. In a CSRF attack, a malicious website tricks the user's browser into making requests to a legitimate site where the user is logged in -- and the browser automatically includes the user's cookies.

Here is a classic CSRF attack:

```
  evil.com page contains:
  <form action="https://your-bank.com/transfer" method="POST">
    <input name="to" value="attacker-account">
    <input name="amount" value="10000">
  </form>
  <script>document.forms[0].submit()</script>

  Without SameSite:
  Browser sends the form POST to your-bank.com
  WITH the user's session cookie
  Bank thinks the user initiated the transfer
  Money is gone

  With SameSite=Lax or Strict:
  Browser does NOT send the cookie with the cross-site POST
  Bank sees an unauthenticated request
  Transfer is rejected
```

Before SameSite existed, developers had to implement CSRF tokens (hidden form fields with random values) as the primary defense. SameSite provides browser-level protection that works automatically, adding a strong layer of defense even if the CSRF token implementation has bugs.

## How does it work?

The browser determines whether a request is "same-site" or "cross-site" by comparing the registrable domain of the page that initiated the request with the registrable domain of the request target.

- `app.example.com` and `api.example.com` are **same-site** (both under `example.com`)
- `example.com` and `evil.com` are **cross-site** (different registrable domains)

For each outgoing request, the browser checks:

```
  Is the request same-site or cross-site?
       |
       |--- Same-site: Send the cookie (all SameSite values)
       |
       |--- Cross-site:
               |
               |--- SameSite=None: Send the cookie
               |
               |--- SameSite=Lax:
               |       |
               |       |--- Top-level navigation (link click, form GET)?
               |       |       Yes: Send the cookie
               |       |       No:  Do NOT send the cookie
               |
               |--- SameSite=Strict: Do NOT send the cookie
```

### Why Lax and not Strict?

Strict sounds more secure, so why not always use it? Because Strict breaks common user experiences:

- If a user clicks a link to your app from their email, Strict means they appear logged out (the cookie is not sent). They have to click something on your site first, then they are logged in on the second request.
- If a user clicks a link from Slack, Teams, or any external tool, same problem.

Lax solves this by allowing the cookie on top-level navigations (the user explicitly decided to go to your site) while still blocking background requests (which the user did not initiate).

The tricky part: OIDC redirect flows. When Google redirects the user back to your callback URL, that is a top-level navigation from `accounts.google.com` to your app. With `SameSite=Lax`, the session cookie is sent with this redirect. With `SameSite=Strict`, it would not be sent, breaking the login flow.

## How does volta-auth-proxy use it?

volta-auth-proxy uses `SameSite=Lax` on its session cookie:

```
Set-Cookie: __volta_session=<UUID>; Path=/; Max-Age=28800; HttpOnly; SameSite=Lax
```

**Why Lax?** volta uses OIDC with Google for login. The login flow looks like this:

```
  1. User at volta login page (your-domain.com)
  2. Redirect to Google (accounts.google.com)
  3. User authenticates at Google
  4. Google redirects back to volta (/callback on your-domain.com)
  5. volta creates session, sets cookie
  6. volta redirects to the target app

  Step 4 is a cross-site top-level navigation
  (from accounts.google.com to your-domain.com).

  With SameSite=Lax: Cookie from step 5 works normally
  because the SET happens on your-domain.com.

  For subsequent requests where the user clicks a link
  from an external site to your app:
  With SameSite=Lax: Cookie IS sent (top-level navigation) -- good
  With SameSite=Strict: Cookie NOT sent -- user appears logged out
```

**CSRF protection.** volta also implements traditional CSRF token validation for form submissions. Every server-side session has a CSRF token stored in the database. HTML forms include this token as a hidden `_csrf` field, and volta validates it on every POST, DELETE, and PATCH request. This double protection (SameSite + CSRF tokens) provides defense in depth.

**The flash cookie.** volta's flash message cookie (`__volta_flash`) also uses `SameSite=Lax`:
```
Set-Cookie: __volta_flash=...; Path=/; Max-Age=20; SameSite=Lax
```

## Common mistakes

**1. Using `SameSite=None` without understanding the consequences.**
`None` sends your cookies with every cross-site request, which effectively disables CSRF protection at the cookie level. Only use `None` when you genuinely need cross-site cookie sending (like for third-party embedded widgets), and always pair it with `Secure`.

**2. Using `SameSite=Strict` with OIDC flows.**
If your app uses OIDC login (redirecting to Google/Okta/etc. and back), `Strict` can break the flow because the redirect back to your app is a cross-site navigation. `Lax` is the right choice.

**3. Relying solely on SameSite for CSRF protection.**
SameSite is a strong defense, but it has edge cases:
- Older browsers may not support it
- `Lax` allows GET requests from cross-site, so if your app performs state-changing operations on GET endpoints, those are still vulnerable
- Subdomain attacks on shared domains can bypass SameSite

Always combine SameSite with traditional CSRF tokens for defense in depth, as volta does.

**4. Not setting SameSite at all.**
Modern browsers default to `Lax` if no SameSite attribute is set. But relying on browser defaults is fragile. Always set it explicitly.

**5. Forgetting that `SameSite=None` requires `Secure`.**
Browsers reject `SameSite=None` cookies that don't have the `Secure` flag. The cookie will simply be ignored. If you need `None`, you must also use HTTPS.

**6. Confusing "same-site" with "same-origin".**
Same-origin is stricter: `app.example.com:443` and `api.example.com:443` are different origins but same site. SameSite uses the less strict "same-site" definition (based on registrable domain), while CORS uses the stricter "same-origin" definition.
