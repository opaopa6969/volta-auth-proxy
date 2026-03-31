# Scopes

[Japanese / 日本語](scopes.ja.md)

---

## What is it?

Scopes are labels that define what information or access an application is requesting from the identity provider. When you see a consent screen saying "This app wants to see your email address," that is because the app requested the `email` scope. Scopes are sent as part of the authorization request, and the identity provider uses them to decide what data to include in the response.

---

## Why does it matter?

Scopes enforce the principle of least privilege. An application should only request the data it actually needs. If an app only needs to know who you are and your email, it should not request access to your Google Drive files. Over-requesting scopes erodes user trust (the consent screen becomes scary) and increases risk (if the app is compromised, more data is exposed).

For identity providers, scopes are also a way to gate sensitive data behind explicit user consent.

---

## Simple example

The three most common OIDC scopes:

| Scope | What it provides |
|-------|-----------------|
| `openid` | Required for OIDC. Returns a `sub` (subject identifier) in the id_token. Without this, it is plain OAuth 2.0, not OIDC. |
| `email` | The user's email address and whether it is verified (`email`, `email_verified` claims). |
| `profile` | The user's name, picture, and other profile information (`name`, `picture`, `locale` claims). |

An authorization request with these scopes:

```
scope=openid email profile
```

This tells Google: "I need to know who this person is (openid), their email (email), and their display name (profile)."

---

## In volta-auth-proxy

volta requests exactly three scopes:

```java
params.put("scope", "openid email profile");
```

Here is why each is needed:

- **openid**: Makes this an OIDC flow (not just OAuth). Ensures Google returns an `id_token` with a `sub` claim that uniquely identifies the user.
- **email**: volta needs the user's email to match them against tenant memberships and invitations. The `email_verified` flag is also checked -- volta rejects unverified emails.
- **profile**: volta uses the `name` claim as the user's display name, stored in the users table and included in the JWT as `volta_display`.

volta does not request broader scopes like `https://www.googleapis.com/auth/drive` or `https://www.googleapis.com/auth/calendar` because it does not need access to any Google APIs on the user's behalf. It only needs identity information.

This minimal scope set means the Google consent screen is short and non-threatening: "volta wants to see your email address and basic profile info." Users are more likely to trust and approve this compared to an app requesting extensive permissions.

See also: [consent-screen.md](consent-screen.md), [authorization-code-flow.md](authorization-code-flow.md)
