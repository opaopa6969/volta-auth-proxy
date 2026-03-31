# CSRF (Cross-Site Request Forgery)

[日本語版はこちら](csrf.ja.md)

---

## What is it?

Cross-Site Request Forgery (CSRF, sometimes pronounced "sea-surf") is an attack where a malicious website tricks your browser into performing an action on a different website where you are already logged in. The malicious website cannot read the response -- it just fires off the request. But if that request transfers money or changes your password, the damage is done.

Think of it like someone forging your signature on a document. You did not write the document, you did not agree to it, but because the signature looks like yours (because it was your browser making the request, with your cookies attached), the bank processes it.

---

## Why does it matter?

Without CSRF protection, any website you visit could:

- Transfer money from your bank account
- Change your email address on a service
- Create admin accounts on your applications
- Accept invitations on your behalf
- Change your role in a team

The scary part: the user does nothing wrong. They do not click a suspicious link. They just visit a normal-looking website (or one with an invisible iframe), and the attack happens silently in the background.

---

## How does it work?

### A real attack example: bank transfer

Imagine you are logged into your bank at `https://bank.example.com`. The bank uses a cookie to track your session (as all banks do).

```
  Step 1: You log into your bank. Your browser stores a session cookie.

    Browser cookie jar:
    ┌────────────────────────────────────────┐
    │  bank.example.com: session=abc123xyz   │
    └────────────────────────────────────────┘

  Step 2: While still logged in, you visit a different website
          (maybe a fun cat picture site: evil-cats.example.com)

  Step 3: That website has this hidden HTML:

    <form action="https://bank.example.com/transfer" method="POST"
          style="display:none">
      <input name="to" value="attacker-account-999">
      <input name="amount" value="10000">
    </form>
    <script>document.forms[0].submit();</script>

  Step 4: Your browser automatically:
    a) Creates a POST request to bank.example.com/transfer
    b) Attaches the bank's session cookie (because the request
       goes TO bank.example.com, the browser sends its cookies)
    c) Sends the form data (to=attacker, amount=10000)

  Step 5: The bank receives the request. It looks legitimate:
    - Valid session cookie ✓
    - Valid form data ✓
    - The bank cannot tell this came from evil-cats.example.com

  Step 6: $10,000 transferred to the attacker.
```

The key insight: **browsers automatically attach cookies to any request going to a domain, regardless of which website initiated the request.** This is how cookies are designed to work -- it is a feature, not a bug. CSRF exploits this feature.

### How token-based CSRF protection works

The defense is simple: include a secret token in every form that only your website knows.

```
  Step 1: When volta renders an HTML form, it includes a hidden token:

    <form action="/admin/members/change-role" method="POST">
      <input type="hidden" name="_csrf" value="Kj8mX2pQ...random...">
      <select name="role">...</select>
      <button>Change Role</button>
    </form>

    This token is unique to the user's session and is stored
    server-side in the sessions table.

  Step 2: When the form is submitted, the server checks:

    a) Is the _csrf token present? ─── No ──► 403 Forbidden
    b) Does it match the session's CSRF token? ─── No ──► 403 Forbidden
    c) Both yes? ──► Process the request

  Why the attacker cannot win:

    The attacker's evil page:
    ┌────────────────────────────────────────────┐
    │  <form action="https://volta.example.com/  │
    │        admin/members/change-role">          │
    │    <input name="role" value="OWNER">        │
    │    <input name="_csrf" value="???">          │
    │  </form>                                    │
    │                                             │
    │  The attacker does NOT know the CSRF token. │
    │  They cannot read volta's pages (same-origin│
    │  policy prevents cross-origin reads).        │
    │  The form submission will be rejected.       │
    └────────────────────────────────────────────┘
```

### How SameSite cookies help

Modern browsers support a cookie attribute called `SameSite`, which controls when cookies are sent with cross-origin requests:

| SameSite value | Behavior |
|---------------|----------|
| `Strict` | Cookie is NEVER sent on cross-origin requests. Most secure, but breaks legitimate cross-site navigation (e.g., clicking a link from email). |
| `Lax` (default in modern browsers) | Cookie is sent on top-level navigation (clicking a link) but NOT on POST requests, iframes, or AJAX from other sites. This blocks most CSRF attacks. |
| `None` | Cookie is always sent. Must be combined with `Secure` flag. This is the old behavior. |

```
  SameSite=Lax protects against:

  evil-cats.example.com                 volta.example.com
  ┌──────────────────────┐              ┌──────────────────┐
  │  <form method="POST" │              │                  │
  │   action="volta..."> │              │  Cookie NOT sent │
  │  </form>             │──── POST ───►│  (blocked!)      │
  │  <script>submit()    │              │                  │
  └──────────────────────┘              └──────────────────┘

  BUT allows:

  email with link                       volta.example.com
  ┌──────────────────────┐              ┌──────────────────┐
  │  Click here to       │              │                  │
  │  view your dashboard │── GET link ─►│  Cookie IS sent  │
  │                      │              │  (top-level nav)  │
  └──────────────────────┘              └──────────────────┘
```

---

## What volta does specifically

volta uses a multi-layered CSRF defense strategy:

### Layer 1: SameSite=Lax session cookie

The `__volta_session` cookie is set with `SameSite=Lax`, which blocks cross-origin POST requests from other sites.

### Layer 2: Token-based CSRF for HTML forms

For traditional HTML form submissions (POST, PATCH, DELETE), volta generates a CSRF token stored in the session record and requires it in every form submission:

```
  Main.java - CSRF middleware:

  1. Check: Is this a POST/PATCH/DELETE request?
     ├── No  → Skip CSRF check
     └── Yes → Continue

  2. Check: Is this a JSON/XHR request?
     ├── Yes → Skip (JSON + SameSite is sufficient)
     └── No  → Continue (this is an HTML form)

  3. Check: Is there a session cookie?
     ├── No  → 403 "CSRF token invalid"
     └── Yes → Continue

  4. Check: Does the _csrf parameter match the session's token?
     ├── No  → 403 "CSRF token invalid"
     └── Yes → Process request
```

### Layer 3: JSON API exemption

volta's JSON API endpoints (called via `fetch()` or `XMLHttpRequest`) do not need traditional CSRF tokens because:

1. **SameSite=Lax** blocks cross-origin POST requests with cookies
2. **Content-Type: application/json** -- browsers cannot send JSON via HTML forms (forms only send `application/x-www-form-urlencoded` or `multipart/form-data`). An attacker's form cannot set `Content-Type: application/json`.
3. **CORS** -- browsers enforce the same-origin policy on XHR/fetch. A cross-origin `fetch()` with credentials requires explicit CORS headers from the server.

This combination means: if a request arrives with `Content-Type: application/json` and a valid session cookie, it must have originated from the same origin (or a CORS-approved origin).

### Layer 4: The state parameter in OIDC

During the Google login flow, the `state` parameter acts as a CSRF token. Without it, an attacker could:

1. Start a login flow with their own Google account
2. Capture the callback URL (with the authorization code)
3. Trick the victim into visiting that callback URL
4. The victim ends up logged in as the attacker (login CSRF)

volta generates a cryptographically random state, stores it in the database, and verifies it when Google redirects back. See [oidc.md](oidc.md) for details.

---

## Common mistakes and attacks

### Mistake 1: Only protecting some endpoints

CSRF protection must cover ALL state-changing endpoints. If you protect `/transfer` but forget `/change-email`, the attacker changes the email, resets the password, and takes over the account.

### Mistake 2: Using GET for state-changing operations

```
  BAD:  GET /admin/members/delete?id=user-123
        (An <img> tag or link click can trigger this)

  GOOD: DELETE /api/v1/tenants/{tid}/members/{uid}
        (Requires a form or fetch with appropriate method)
```

### Mistake 3: CSRF tokens in cookies

If you put the CSRF token only in a cookie, the attack still works -- the browser sends the cookie automatically. The token must be in the form body or a custom header, which the attacker cannot set from a different origin.

### Mistake 4: Not regenerating CSRF tokens

If the CSRF token never changes, an attacker who finds one (e.g., from a cached page) can reuse it. volta ties CSRF tokens to sessions, and sessions are regenerated on login (preventing session fixation).

### Attack: Login CSRF

This is an often-overlooked variant. The attacker does not attack your existing session -- they force you into a session they control:

```
  1. Attacker starts Google OIDC login with their account
  2. Attacker gets the callback URL: /callback?code=ATTACKER_CODE&state=...
  3. Attacker sends victim to this URL
  4. Without state verification: victim is now logged in as attacker
  5. Victim uploads sensitive files, thinking they are in their own account
  6. Attacker logs in and reads the files
```

volta's state parameter prevents this entirely.

---

## Further reading

- [OWASP CSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html) -- Comprehensive defense guide.
- [SameSite Cookies Explained](https://web.dev/samesite-cookies-explained/) -- Google's guide to SameSite.
- [oidc.md](oidc.md) -- How the state parameter prevents login CSRF.
- [cookie.md](cookie.md) -- Cookie attributes including SameSite.
- [session.md](session.md) -- How volta manages sessions and CSRF tokens.
