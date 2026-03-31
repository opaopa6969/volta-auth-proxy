# Redirect URI

[Japanese / 日本語](redirect-uri.ja.md)

---

## What is it?

A redirect URI (also called a callback URL) is the address where the identity provider (e.g., Google) sends the user back after authentication. When you click "Sign in with Google," Google needs to know where to return you with the authorization code. That destination is the redirect URI. It must be registered in advance with the identity provider and must match **exactly** -- scheme, host, port, and path.

---

## Why does it matter?

If the redirect URI is not validated strictly, an attacker can trick the identity provider into sending the authorization code to a URL the attacker controls. This is called an **open redirect attack**. The attacker receives the code, exchanges it for tokens, and gains access to the victim's account.

Google and other providers enforce this by requiring you to register your exact redirect URIs in the developer console. They will reject any authorization request whose `redirect_uri` parameter does not match a registered value. This is not optional paranoia -- it is a critical security boundary.

---

## Simple example

Registered redirect URI in Google Console:
```
http://localhost:7070/callback
```

Authorization request:
```
https://accounts.google.com/o/oauth2/v2/auth
  ?redirect_uri=http://localhost:7070/callback   <-- must match exactly
  &client_id=YOUR_ID
  &response_type=code
  &scope=openid email profile
```

If an attacker changes the redirect_uri to:
```
?redirect_uri=https://evil.com/steal
```

Google rejects the request because `https://evil.com/steal` is not registered. The attack fails.

---

## In volta-auth-proxy

volta's redirect URI is configured via the `GOOGLE_REDIRECT_URI` environment variable:

```
GOOGLE_REDIRECT_URI=http://localhost:7070/callback
```

This value is used in two places:

1. **Building the authorization URL** (`OidcService.createAuthorizationUrl()`): Included as the `redirect_uri` parameter in the Google authorization request.

2. **Exchanging the code** (`OidcService.exchangeCode()`): Included again in the token exchange POST. Google requires the redirect_uri to match in both the initial request and the code exchange -- a mismatch causes the exchange to fail.

volta also has a separate mechanism for post-login redirects: the `returnTo` parameter. This is where the user goes after the entire OIDC flow completes, and it is validated against the `ALLOWED_REDIRECT_DOMAINS` whitelist (see [open-redirect.md](open-redirect.md)). The OIDC redirect URI (`/callback`) and the application redirect (`returnTo`) are two different things -- the first is between volta and Google, the second is between volta and the downstream app.

See also: [open-redirect.md](open-redirect.md), [authorization-code-flow.md](authorization-code-flow.md)
