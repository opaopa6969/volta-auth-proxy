# Consent Screen

[Japanese / 日本語](consent-screen.ja.md)

---

## What is it?

A consent screen is the page shown by an identity provider (like Google) asking the user to approve what data the application wants to access. It says something like "volta wants to view your email address and basic profile info -- Allow or Deny?" The user must explicitly grant permission before the OIDC flow continues. It is the user's chance to say "no" before any data is shared.

---

## Why does it matter?

Consent screens exist because of a fundamental principle: users should control who sees their data. Without consent screens, any application that knows your Google client ID could silently access your email and profile. The consent screen creates a human checkpoint -- no matter how cleverly an attacker crafts a phishing link, the user still sees what is being requested and can refuse.

Consent screens also create accountability. Users know exactly which applications have access to their data, and they can revoke access later through the provider's settings.

---

## Simple example

When volta redirects a user to Google, Google shows:

```
+------------------------------------------+
|  Choose an account                       |
|                                          |
|  alice@gmail.com                         |
|  bob@company.com                         |
|                                          |
+------------------------------------------+
         |  (user picks an account)
         v
+------------------------------------------+
|  volta wants to:                         |
|                                          |
|  - See your email address                |
|  - See your personal info (name, photo)  |
|                                          |
|  [Allow]            [Deny]               |
+------------------------------------------+
```

volta requests `prompt=select_account`, which always shows the account picker, even if the user has only one account. This prevents silent login to the wrong account in multi-account scenarios.

---

## In volta-auth-proxy

volta interacts with consent at two levels:

**Google's consent screen** (OIDC level):

volta requests minimal scopes (`openid email profile`), which keeps Google's consent screen simple. The `prompt=select_account` parameter forces the account picker to appear:

```java
params.put("prompt", "select_account");
```

This is a deliberate UX choice. Without it, a user already signed into Google might be silently logged in without seeing any prompt, which could cause them to accidentally log in as the wrong account.

**volta's own invitation consent** (application level):

volta has a separate concept of consent for workspace invitations. When a user is invited to a tenant, they see an invitation acceptance page. This is not a Google consent screen -- it is volta's own UI asking: "You have been invited to join workspace X. Do you accept?" This is managed through volta's invitation flow, with the invitation code stored in the OIDC flow and the acceptance page rendered by volta's jte templates.

The two consent layers serve different purposes: Google's consent protects the user's Google data. volta's invitation consent protects the workspace from unauthorized joins.

See also: [scopes.md](scopes.md), [authorization-code-flow.md](authorization-code-flow.md)
