# Discovery Document (.well-known/openid-configuration)

[Japanese / 日本語](discovery-document.ja.md)

---

## What is it?

An OIDC discovery document is a JSON file published at a well-known URL (always `/.well-known/openid-configuration`) that describes everything a client needs to know about an identity provider. It lists the authorization endpoint, token endpoint, JWKS URI, supported scopes, supported algorithms, and more. Instead of hardcoding these URLs, a client can fetch the discovery document and configure itself automatically.

---

## Why does it matter?

Without discovery, every application that integrates with an identity provider would need to hardcode endpoint URLs. If the provider changes a URL, every application breaks. The discovery document provides a single source of truth that clients can read programmatically. It also makes it possible to build generic OIDC libraries that work with any provider -- just give them the issuer URL and they figure out the rest.

For providers like Google, the discovery document also serves as documentation. You can look at it to see exactly what Google supports.

---

## Simple example

Google's discovery document at `https://accounts.google.com/.well-known/openid-configuration`:

```json
{
  "issuer": "https://accounts.google.com",
  "authorization_endpoint": "https://accounts.google.com/o/oauth2/v2/auth",
  "token_endpoint": "https://oauth2.googleapis.com/token",
  "jwks_uri": "https://www.googleapis.com/oauth2/v3/certs",
  "scopes_supported": ["openid", "email", "profile"],
  "response_types_supported": ["code", "token", "id_token"],
  "id_token_signing_alg_values_supported": ["RS256"],
  ...
}
```

A client reads this once and knows: "To start authentication, redirect to the `authorization_endpoint`. To exchange a code, POST to the `token_endpoint`. To verify an id_token signature, fetch keys from `jwks_uri`."

---

## In volta-auth-proxy

volta takes a pragmatic approach to discovery:

**As a consumer** (connecting to Google): volta currently hardcodes Google's OIDC endpoints rather than fetching the discovery document at runtime:

```java
private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
private static final URI GOOGLE_JWKS = URI.create("https://www.googleapis.com/oauth2/v3/certs");
```

This is a deliberate choice for Phase 1 -- these Google URLs have been stable for years, and hardcoding avoids an extra HTTP call on startup. If volta adds support for other providers (Phase 3), it would switch to reading discovery documents dynamically.

**As a provider** (for downstream apps): volta publishes its own JWKS endpoint at `/.well-known/jwks.json`, which downstream services use to verify volta-issued JWTs. This follows the same principle -- downstream apps do not need to hardcode volta's public keys; they just fetch them from the well-known URL.

See also: [scopes.md](scopes.md), [authorization-code-flow.md](authorization-code-flow.md)
