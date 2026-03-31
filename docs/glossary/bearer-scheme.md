# Bearer Scheme

[日本語版はこちら](bearer-scheme.ja.md)

---

## What is it?

The Bearer scheme is a way to send an authentication token in an HTTP request. You put the token in the `Authorization` header like this:

```
Authorization: Bearer eyJhbGciOiJSUzI1NiJ9...
```

The word "Bearer" means "anyone who bears (carries) this token is granted access." It's like a concert ticket -- whoever holds it gets in, regardless of who bought it. The server doesn't check who you are; it checks that the token is valid.

---

## Why does it matter?

Bearer tokens are the standard way APIs authenticate requests. They're simple, stateless, and work across any HTTP client (mobile apps, CLI tools, other servers, JavaScript frontends).

The downside is the "anyone who has it" part. If a bearer token is leaked (in a log, in a URL, in an error message), anyone who finds it can use it. That's why bearer tokens should:
- Be short-lived (volta: 5 minutes)
- Only travel over HTTPS
- Never appear in URLs or logs

---

## A simple example

```
# Making an authenticated API call
curl -H "Authorization: Bearer eyJhbGciOiJSUzI1NiJ9..." \
     https://auth.example.com/api/v1/users/me

# The server:
# 1. Extracts everything after "Bearer "
# 2. Verifies the JWT signature
# 3. Reads the claims (sub, volta_tid, volta_roles)
# 4. Returns the response (or 401 if invalid)
```

### Why "Bearer" and not just the token?

The `Authorization` header supports multiple **schemes**:
- `Bearer <token>` -- token-based (most common for APIs)
- `Basic <base64>` -- username:password encoded in base64
- `Digest <params>` -- challenge-response authentication

The scheme prefix tells the server which authentication method to use. Without it, the server wouldn't know how to interpret the header value.

---

## In volta-auth-proxy

volta uses Bearer tokens for all API authentication. The `AuthService` checks the `Authorization` header first, before falling back to session cookies:

When a request arrives at `/api/v1/*`:
1. Check for `Authorization: Bearer <token>` header
2. If present, verify the JWT (signature, expiry, issuer, audience)
3. If not present, check for a session cookie (`volta_session`)
4. If neither, return `401 AUTHENTICATION_REQUIRED`

This dual approach lets browsers use cookie-based sessions (more secure for browsers) while APIs use Bearer tokens (more convenient for programmatic access).

volta also supports a `VOLTA_SERVICE_TOKEN` -- a static Bearer token for machine-to-machine communication in Phase 1, before the full client credentials flow is implemented.

---

## See also

- [jwt-vs-session.md](jwt-vs-session.md) -- When to use Bearer tokens vs sessions
- [jwt-payload.md](jwt-payload.md) -- What's inside the Bearer token
