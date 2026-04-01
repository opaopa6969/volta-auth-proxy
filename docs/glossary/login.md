# Login

[日本語版はこちら](login.ja.md)

---

## In one sentence?

Login is the act of proving who you are to a system so it knows it can trust you and give you access to your stuff.

---

## Showing your ID at the door

Logging in is like arriving at a hotel:

1. **You walk up to the front desk** -- You open the website in your [browser](browser.md)
2. **You show your ID** -- You provide [credentials](credentials.md) (password, Google account, etc.)
3. **The receptionist checks the system** -- The [server](server.md) verifies your identity
4. **You get a room key** -- The server gives you a [session](session.md) [cookie](cookie.md)
5. **The key opens your room** -- The cookie grants access to your account on every subsequent request

Without step 2, anyone could walk into any room. That's why login exists.

---

## Why do we need this?

Without login:

- Anyone could see anyone else's data
- There'd be no way to know who performed an action (audit trail gone)
- [RBAC](authentication-vs-authorization.md) roles (OWNER, ADMIN, MEMBER, VIEWER) would be meaningless
- Sensitive operations like inviting users or changing settings would be open to the public
- Multi-tenant isolation would collapse -- Tenant A could see Tenant B's data

Login is the front gate of every security model.

---

## Login in volta-auth-proxy

volta does NOT handle passwords itself. It delegates [authentication](authentication-vs-authorization.md) to Google via [OIDC](oidc.md). Here's why:

- **No password storage risk** -- volta never sees or stores your password
- **Google handles MFA, suspicious login detection, and account recovery**
- **One fewer attack surface** -- No brute-force attacks on volta's login

The login flow in volta:

```
  Browser                    volta-auth-proxy              Google
  ──────                     ──────────────────            ──────
  1. Visit /auth/login ──────>
                             2. Generate state + nonce
                             3. Redirect to Google ──────>
                                                          4. User signs in
                                                          5. Google redirects back
                             <────── with authorization code
                             6. Exchange code for tokens
                             7. Verify id_token (OIDC)
                             8. Create/update user record
                             9. Create session
                             10. Set __volta_session cookie
  <────── Redirect to app
  11. Logged in!
```

Key security measures during login:

- **State parameter** -- Prevents [CSRF](csrf.md) attacks on the login flow
- **Nonce** -- Prevents token replay attacks
- **[PKCE](pkce.md)** -- Prevents authorization code interception
- **[Redirect URI](redirect-uri.md) validation** -- Prevents [open redirect](open-redirect.md) attacks

---

## Concrete example

A user logging into a volta-protected app:

1. User visits `https://app.acme.example.com/dashboard`
2. [Reverse proxy](reverse-proxy.md) checks with volta via [ForwardAuth](forwardauth.md) -- no valid [session](session.md)
3. volta responds with HTTP 302 [redirect](redirect.md) to `/auth/login?redirect_to=/dashboard`
4. volta generates a random `state` value, stores it in a temporary [cookie](cookie.md)
5. volta [redirects](redirect.md) the [browser](browser.md) to `https://accounts.google.com/o/oauth2/v2/auth?client_id=...&state=...&nonce=...`
6. User sees Google's "Sign in" page, enters their email and password
7. Google verifies the user and [redirects](redirect.md) back to `https://auth.acme.example.com/auth/callback?code=abc123&state=...`
8. volta verifies the `state` matches, exchanges `code` for tokens with Google
9. volta reads the `id_token` to get user's email and name
10. volta creates a session, sets the `__volta_session` cookie
11. volta redirects user back to `/dashboard` -- now authenticated

---

## Learn more

- [Logout](logout.md) -- The reverse of login: ending your session
- [Session](session.md) -- What gets created when you log in
- [OIDC](oidc.md) -- The protocol volta uses for login with Google
- [Credentials](credentials.md) -- The proof of identity you provide
- [PKCE](pkce.md) -- Extra security for the login flow
- [Redirect](redirect.md) -- How the browser moves between volta and Google
