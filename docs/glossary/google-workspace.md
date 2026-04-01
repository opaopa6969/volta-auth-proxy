# Google Workspace

[日本語版はこちら](google-workspace.ja.md)

---

## What is it?

Google Workspace (formerly G Suite, formerly Google Apps for Business) is Google's suite of cloud-based productivity and collaboration tools: Gmail, Google Drive, Google Docs, Google Calendar, Google Meet, and more. But beyond being a productivity suite, Google Workspace is also an **identity provider** -- every Google Workspace user has a Google account that can be used for "Sign in with Google" across the internet.

Think of Google Workspace like a corporate campus badge system bundled with an office supply store. The badge (Google account) gets you into the campus (Gmail, Drive, Calendar), but it also works at stores across town (any website that supports "Sign in with Google"). The campus security team (Google Workspace admin) can revoke your badge, restrict what stores you can visit, and enforce rules like "always use a PIN with your badge" (MFA).

For volta-auth-proxy, Google Workspace matters because Google is volta's **Phase 1 OIDC provider**. When a user clicks "Sign in with Google," they are authenticating through their Google account -- which is often a Google Workspace account for business users.

---

## Why does it matter?

Google Workspace has over 3 billion users (including personal Gmail accounts that use the same authentication system). For SaaS products, "Sign in with Google" is often the most requested authentication method because:

1. **Most business users already have a Google account** (either personal or Workspace)
2. **Google handles the hard parts**: MFA, account recovery, device management, brute-force protection
3. **Users trust Google's login page** (they see it every day)
4. **One fewer password to remember**

For volta-auth-proxy specifically, Google Workspace provides:

- **Verified email addresses**: Workspace admins control the email domain. If `alice@acme.com` authenticates through Google Workspace, you can trust that acme.com verified Alice's identity.
- **Organization-scoped identity**: Unlike a personal Gmail account, a Workspace account belongs to an organization, making tenant resolution straightforward.
- **Admin controls**: Workspace admins can restrict which third-party apps their users can sign into, adding a layer of organizational security.

---

## How does it work?

### Google Workspace as an Identity Provider

```
  User (alice@acme.com, Google Workspace account)
       │
       │  "Sign in with Google"
       ▼
  volta-auth-proxy
       │
       │  OIDC redirect
       ▼
  Google's OAuth/OIDC endpoints
       │
       │  Authenticate user (password + MFA)
       │  Issue id_token with claims:
       │    email: alice@acme.com
       │    email_verified: true
       │    hd: acme.com  ← Hosted Domain (Workspace indicator)
       │    name: Alice Smith
       │    sub: 1234567890
       │
       ▼
  volta-auth-proxy
       │
       │  Validate id_token
       │  Check hd claim → resolve tenant "acme"
       │  Create/update user
       │  Issue volta session
       ▼
  User is logged in
```

### Key claims in the Google id_token

| Claim | Description | volta usage |
|-------|-------------|-------------|
| `email` | User's email address | User identification |
| `email_verified` | Whether Google has verified this email | Must be `true` (volta rejects unverified) |
| `hd` | Hosted domain (only for Workspace accounts) | Tenant resolution |
| `sub` | Google's unique user identifier | Stable user ID (email can change) |
| `name` | Display name | User profile |
| `picture` | Profile picture URL | User profile |

### The `hd` (Hosted Domain) claim

The `hd` claim is crucial for multi-tenant SaaS:

| Account type | `hd` claim | Meaning |
|-------------|-----------|---------|
| Google Workspace (`alice@acme.com`) | `"acme.com"` | User belongs to acme.com organization |
| Personal Gmail (`alice@gmail.com`) | Not present | Personal account, no organization |
| Google Workspace (consumer) | `"gmail.com"` | Rare edge case |

volta uses the `hd` claim to automatically resolve which tenant a user belongs to. If `hd` is `acme.com`, volta looks up the tenant associated with `acme.com`.

### Workspace admin controls

Google Workspace admins can:

| Control | Effect on volta |
|---------|----------------|
| Block third-party app access | Users cannot use "Sign in with Google" for volta |
| Restrict to approved apps | Only apps on the approved list can use Workspace SSO |
| Enforce MFA | All users must complete MFA before reaching volta |
| Disable user account | User cannot authenticate (volta login fails) |
| Transfer ownership | User's email changes (volta should use `sub` as stable ID) |

### Google Workspace vs personal Google accounts

| Feature | Google Workspace | Personal Gmail |
|---------|-----------------|----------------|
| `hd` claim | Present (your domain) | Absent |
| Admin control | Full (IT manages) | None (user manages) |
| Email domain | Custom (`@acme.com`) | `@gmail.com` |
| MFA enforcement | Admin can mandate | User's choice |
| Tenant resolution | Automatic via `hd` | Manual (free-email-domain logic) |
| Cost | $6-18/user/month | Free |
| SAML/SCIM | Available (Business Plus+) | No |

---

## How does volta-auth-proxy use it?

Google Workspace is volta-auth-proxy's **primary identity provider in Phase 1**. volta uses Google's [OIDC](oidc.md) endpoints to authenticate users.

### The authentication flow

1. User clicks "Login" on a volta-protected application
2. volta redirects to Google's authorization endpoint (configured via [Google Cloud Console](google-cloud-console.md))
3. Google authenticates the user (Workspace login page, MFA if required)
4. Google redirects back to volta with an authorization code
5. volta exchanges the code for an id_token (server-to-server)
6. volta validates the id_token and extracts identity claims
7. volta resolves the tenant from the `hd` claim or email domain
8. volta creates/updates the user and issues a session

### Tenant resolution via Google Workspace

```
  id_token.hd = "acme.com"
       │
       ▼
  SELECT * FROM tenants WHERE domain = 'acme.com'
       │
       ├── Found → User belongs to tenant "Acme Corp"
       └── Not found → Tenant auto-provisioning or rejection
```

### Free email domain handling

When a user signs in with `alice@gmail.com` (no `hd` claim), volta checks its [free-email-domains](free-email-domains.md) list. Free email domains cannot be used for automatic tenant resolution -- the user must be invited to a specific tenant or self-register.

### Why Google first?

volta chose Google as the Phase 1 provider because:

1. **Largest user base**: Most SaaS users have a Google account
2. **Excellent OIDC implementation**: Google's OIDC is well-documented and standards-compliant
3. **The `hd` claim**: Makes multi-tenant resolution elegant
4. **Free**: No per-user IdP costs (unlike [Okta](okta.md))

Phase 2+ adds [Okta](okta.md), [Active Directory](active-directory.md), and other providers.

---

## Common mistakes and attacks

### Mistake 1: Trusting email alone for tenant resolution

A user might have `alice@acme.com` as a personal Google account (not a Workspace account). Without checking the `hd` claim, you might assign them to the acme.com tenant incorrectly. Always check `hd` for Workspace accounts and fall back to invitation-based access for personal accounts.

### Mistake 2: Not checking email_verified

Google accounts can have unverified emails. If you skip the `email_verified` check, someone could create a Google account with your email and access your tenant. volta always requires `email_verified = true`.

### Mistake 3: Using email as the stable identifier

Workspace admins can change a user's email address (e.g., name change after marriage). If volta identifies users solely by email, the user will lose access to their account. Use Google's `sub` claim as the stable identifier, with email as a human-readable label.

### Mistake 4: Not handling Workspace admin app restrictions

If a Workspace admin blocks your app, Google returns an error during the OIDC flow. volta should display a clear error message: "Your organization's administrator has not approved this application. Contact your IT team."

### Attack: Workspace domain takeover

If acme.com lets their Google Workspace subscription lapse and someone else registers acme.com as a new Workspace, they could create `alice@acme.com` and access the acme tenant. Mitigate by using `sub` (stable, not reusable) in addition to email for user matching.

---

## Further reading

- [Google Workspace admin documentation](https://support.google.com/a/) -- Managing Workspace settings.
- [Google Identity documentation](https://developers.google.com/identity/) -- OAuth/OIDC integration.
- [google-cloud-console.md](google-cloud-console.md) -- Where you create OAuth credentials.
- [oidc.md](oidc.md) -- The protocol volta uses with Google.
- [free-email-domains.md](free-email-domains.md) -- Handling personal email signups.
- [idp.md](idp.md) -- What an Identity Provider is.
- [okta.md](okta.md) -- Enterprise IdP alternative (Phase 3).
- [active-directory.md](active-directory.md) -- Microsoft's enterprise IdP (Phase 3).
