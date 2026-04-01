# Redirect

[日本語版はこちら](redirect.ja.md)

---

## In one sentence?

A redirect is when a [server](server.md) tells your [browser](browser.md) "what you're looking for isn't here -- go to this other URL instead," and the browser automatically follows.

---

## The forwarding address on your mailbox

You move to a new house but your friends still send mail to the old address. So you put a note on the old mailbox: "I've moved to 456 Oak Street." The postal worker sees the note, picks up the letter, and delivers it to 456 Oak Street -- without your friend having to do anything.

| Mail | Web |
|---|---|
| Note on the mailbox | HTTP 302 response with `Location` header |
| Postal worker reads the note | [Browser](browser.md) reads the `Location` header |
| Letter goes to the new address | Browser sends a new request to the new URL |
| Your friend never knows you moved | User sees the final page, may not notice the redirect |

Common [HTTP status codes](http-status-codes.md) for redirects:

| Code | Meaning | When to use |
|---|---|---|
| 301 | Moved Permanently | Old URL is gone forever |
| 302 | Found (Temporary) | Come here for now, but the old URL may return |
| 303 | See Other | After a POST, redirect to GET this URL |
| 307 | Temporary Redirect | Like 302 but keeps the HTTP method |

---

## Why do we need this?

Without redirects:

- [Login](login.md) flows couldn't work -- there's no way to send users to Google and bring them back
- URL changes would break every bookmark and link
- After submitting a form, refreshing the page would submit it again (no POST-Redirect-GET pattern)
- [OAuth2](oauth2.md)/[OIDC](oidc.md) would be impossible -- the entire protocol depends on redirects

Redirects are the glue that holds multi-step web flows together.

---

## Redirect in volta-auth-proxy

volta uses redirects extensively in the [authentication](authentication-vs-authorization.md) flow:

```
  Step 1: User visits protected page
  Browser ──GET /dashboard──> Reverse Proxy ──ForwardAuth──> volta
                                                              │
  Step 2: volta says "not authenticated"                      │
  volta responds: 302 Location: /auth/login?redirect_to=/dashboard
                                                              │
  Step 3: volta redirects to Google                           │
  Browser ──GET /auth/login──> volta                          │
  volta responds: 302 Location: https://accounts.google.com/...
                                                              │
  Step 4: Google redirects back                               │
  Browser ──GET /auth/callback?code=abc──> volta              │
                                                              │
  Step 5: volta redirects to original page                    │
  volta responds: 302 Location: /dashboard                    │
  Browser ──GET /dashboard──> (now authenticated!)
```

**Security: Redirect URI validation**

Redirects can be dangerous. If an attacker tricks volta into redirecting to `https://evil.com`, the authorization code could be stolen. volta prevents this with:

- **Allowlist** -- Only pre-configured [redirect URIs](redirect-uri.md) are accepted
- **Exact match** -- No partial or pattern matching that could be bypassed
- **[Open redirect](open-redirect.md) prevention** -- The `redirect_to` parameter is validated against allowed [domains](domain.md)

---

## Concrete example

The full redirect chain during a volta login:

1. User types `https://app.acme.example.com/settings` in [browser](browser.md)
2. [Reverse proxy](reverse-proxy.md) asks volta: "Is this user authenticated?" -- No
3. **Redirect 1:** volta responds `302 Location: https://auth.acme.example.com/auth/login?redirect_to=https://app.acme.example.com/settings`
4. Browser follows redirect to volta's login page
5. User clicks "Sign in with Google"
6. **Redirect 2:** volta responds `302 Location: https://accounts.google.com/o/oauth2/v2/auth?client_id=...&redirect_uri=https://auth.acme.example.com/auth/callback&state=...`
7. Browser follows redirect to Google
8. User authenticates with Google
9. **Redirect 3:** Google responds `302 Location: https://auth.acme.example.com/auth/callback?code=abc123&state=xyz`
10. Browser follows redirect back to volta
11. volta verifies the code, creates a [session](session.md), sets the [cookie](cookie.md)
12. **Redirect 4:** volta responds `302 Location: https://app.acme.example.com/settings`
13. Browser follows redirect to the original page -- now authenticated

That's 4 redirects, all happening in under a second. The user just sees a brief flash before landing on their settings page.

---

## Learn more

- [Redirect URI](redirect-uri.md) -- The specific URL where OAuth providers send users back
- [Open Redirect](open-redirect.md) -- The attack that exploits unvalidated redirects
- [HTTP Status Codes](http-status-codes.md) -- The 3xx codes that trigger redirects
- [Login](login.md) -- The flow that uses the most redirects
- [OIDC](oidc.md) -- The protocol built on redirects
- [Browser](browser.md) -- The software that follows redirects
