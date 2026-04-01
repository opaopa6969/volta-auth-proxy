# Browser

[日本語版はこちら](browser.ja.md)

---

## In one sentence?

A browser is the app on your computer or phone (like Chrome, Safari, or Firefox) that lets you visit websites by typing in an address or clicking a link.

---

## The window to the world

Imagine you want to visit a shop in a foreign country, but you don't speak the language. You hire a translator who:

- **Carries your request** to the shopkeeper ("I'd like to see the menu, please")
- **Brings back the response** and presents it nicely in your language
- **Remembers your preferences** (your name, your loyalty card) so you don't repeat yourself every visit
- **Warns you if something looks fishy** ("This shop has no license -- are you sure?")

That translator is your browser. It speaks HTTP/HTTPS to servers and translates the response into the visual pages you see.

| You do this | Browser does this behind the scenes |
|---|---|
| Type `example.com` | Sends an HTTP GET request to the server |
| Click "Log in" | Sends your [credentials](credentials.md) securely via HTTPS |
| See a webpage | Renders [HTML](html.md), CSS, and JavaScript |
| See a padlock icon | Verified the [SSL/TLS](ssl-tls.md) certificate |
| Stay logged in | Stores a [cookie](cookie.md) and sends it on every request |

---

## Why do we need this?

Without a browser, you would have to:

- Manually type raw [HTTP](http.md) requests in a terminal
- Parse HTML source code with your eyes
- Handle [cookies](cookie.md), [redirects](redirect.md), and [SSL/TLS](ssl-tls.md) handshakes yourself

Browsers make the internet usable for humans. They also enforce critical security rules like the [same-origin policy](cross-origin.md) and [CORS](cors.md), which prevent malicious websites from stealing your data.

---

## Browser in volta-auth-proxy

volta-auth-proxy interacts with the browser constantly:

1. **Login flow** -- When you [log in](login.md), the browser is [redirected](redirect.md) to Google's [OIDC](oidc.md) page, then back to volta with an authorization code.
2. **Session cookie** -- volta sets a `__volta_session` [cookie](cookie.md) with `HttpOnly`, `Secure`, and `SameSite=Lax`. The browser stores it and sends it on every request automatically.
3. **ForwardAuth** -- When your app is behind a [reverse proxy](reverse-proxy.md), the browser never talks to volta directly. The reverse proxy asks volta to verify the cookie, and volta injects [X-Volta-* headers](header.md) for the app.
4. **[CORS](cors.md) enforcement** -- The browser blocks [cross-origin](cross-origin.md) requests unless volta's CORS headers allow it. This protects against malicious sites trying to call volta's [API](api.md).
5. **[Responsive](responsive.md) login pages** -- volta's login and invitation pages adapt to phone and desktop browsers.

---

## Concrete example

What happens when you open `https://app.acme.example.com` in your browser:

1. Browser sends a GET request to the [server](server.md)
2. [Reverse proxy](reverse-proxy.md) intercepts and asks volta: "Is this user authenticated?"
3. volta checks the [cookie](cookie.md) -- no cookie found
4. volta responds with a [redirect](redirect.md) (HTTP 302) to the login page
5. Browser follows the redirect and shows the login page
6. You click "Sign in with Google"
7. Browser redirects to Google [OIDC](oidc.md)
8. You authenticate with Google
9. Google redirects back to volta with an authorization code
10. volta creates a [session](session.md), sets the cookie, and redirects to the original URL
11. Browser sends the request again -- this time with the cookie
12. volta verifies the session, injects [JWT](jwt.md) headers, and the app loads

---

## Learn more

- [HTTP](http.md) -- The language browsers speak to servers
- [Cookie](cookie.md) -- How browsers remember you between page loads
- [Redirect](redirect.md) -- How browsers follow URL changes automatically
- [SSL/TLS](ssl-tls.md) -- Why the padlock icon matters
- [Cross-Origin](cross-origin.md) -- Security rules browsers enforce between different domains
- [Responsive](responsive.md) -- How web pages adapt to different browser sizes
