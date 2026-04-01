# Payload

[日本語版はこちら](payload.ja.md)

---

## What is it?

A payload is the actual data content carried inside a message, as opposed to the metadata (headers, envelope information) that describes the message. In networking, the payload is the "cargo" -- the thing you actually care about. Everything else (headers, routing info, protocol framing) is just packaging to get the cargo where it needs to go.

Think of it like a shipping package. The label on the outside (sender address, recipient address, tracking number, "FRAGILE" stickers) is metadata. The item inside the box -- the actual thing you ordered -- is the payload. When you receive the package, you care about what is inside, not the label.

In volta-auth-proxy, payloads appear everywhere: JWT payloads carry user claims, HTTP payloads carry request/response bodies, [webhook](webhook.md) payloads carry event data, and [SCIM](scim.md) payloads carry user provisioning commands.

---

## Why does it matter?

Understanding the distinction between payload and metadata is essential for security, debugging, and integration:

```
  HTTP Request anatomy:
  ┌──────────────────────────────────────────────┐
  │  METADATA (headers, routing):                 │
  │  ┌──────────────────────────────────────────┐│
  │  │ POST /api/v1/tenants/acme/members        ││
  │  │ Host: volta.example.com                   ││
  │  │ Content-Type: application/json            ││
  │  │ Authorization: Bearer eyJhbGci...         ││
  │  │ X-Volta-Request-Id: req-uuid              ││
  │  └──────────────────────────────────────────┘│
  │                                               │
  │  PAYLOAD (the actual data):                   │
  │  ┌──────────────────────────────────────────┐│
  │  │ {                                         ││
  │  │   "email": "alice@acme.com",             ││
  │  │   "role": "MEMBER"                        ││
  │  │ }                                         ││
  │  └──────────────────────────────────────────┘│
  └──────────────────────────────────────────────┘
```

- **Security**: Payloads may contain sensitive data that needs encryption or signing
- **Validation**: Payloads must be validated before processing (type checking, size limits)
- **Logging**: Log metadata freely, but be careful about logging payloads (they may contain PII)
- **Debugging**: Most bugs are in payload handling, not header handling

---

## How does it work?

### JWT payload

A [JWT](jwt.md) has three parts: header, payload, and signature. The payload is a JSON object encoded as [base64url](base64.md):

```
  JWT: eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOi...  .signature
       ├── header ────────┤ ├── payload ──┤  ├── sig ──┤

  Decoded payload:
  {
    "iss": "volta-auth",           ← issuer
    "sub": "user-uuid",            ← subject (who)
    "aud": ["volta-apps"],         ← audience (for whom)
    "exp": 1711900000,             ← expiration
    "iat": 1711899700,             ← issued at
    "volta_v": 1,                  ← volta version
    "volta_tid": "acme-uuid",      ← tenant ID
    "volta_roles": ["ADMIN"],      ← user roles
    "volta_email": "a@acme.com"    ← email
  }
```

Each field in the JWT payload is called a "claim." volta adds custom claims prefixed with `volta_` to avoid collisions with standard claims.

### HTTP request payload

The HTTP request body is the payload:

```
  POST /scim/v2/Users
  Content-Type: application/json
  Content-Length: 187

  {                          ←─┐
    "schemas": [...],           │
    "userName": "alice@a.com",  │  PAYLOAD
    "name": {                   │
      "givenName": "Alice",     │
      "familyName": "Smith"     │
    },                          │
    "active": true              │
  }                          ←─┘
```

### Webhook payload

When volta sends a [webhook](webhook.md), the HTTP body is the payload:

```
  POST https://app.com/webhooks/volta
  Content-Type: application/json
  X-Volta-Signature: sha256=abc123    ← metadata
  X-Volta-Event: user.created         ← metadata

  {                                    ←─┐
    "event": "user.created",              │
    "timestamp": "2026-04-01T12:00:00Z",  │ PAYLOAD
    "tenant_id": "acme-uuid",             │
    "data": {                             │
      "user_id": "user-uuid",            │
      "email": "alice@acme.com",          │
      "roles": ["MEMBER"]                 │
    }                                     │
  }                                    ←─┘
```

The HMAC signature in the header is computed over this payload to ensure integrity.

### Payload size considerations

```
  ┌──────────────────────────────────────────────┐
  │  Payload size limits in volta:                │
  │                                               │
  │  JWT payload:     ~2 KB typical               │
  │    (must fit in HTTP header as Bearer token)  │
  │                                               │
  │  Webhook payload: 64 KB max                   │
  │    (event data should be concise)             │
  │                                               │
  │  SCIM payload:    256 KB max                  │
  │    (user/group objects can be large)          │
  │                                               │
  │  API request:     1 MB max                    │
  │    (general request body limit)               │
  └──────────────────────────────────────────────┘
```

---

## How does volta-auth-proxy use it?

### JWT payload claims (volta-specific)

volta adds custom claims to the JWT payload:

| Claim | Type | Example | Purpose |
|-------|------|---------|---------|
| `volta_v` | int | `1` | Payload format version |
| `volta_tid` | string | `"acme-uuid"` | [Tenant](tenant.md) ID |
| `volta_roles` | string[] | `["ADMIN"]` | [Roles](role.md) |
| `volta_email` | string | `"a@acme.com"` | User email |
| `volta_client` | boolean | `true` | [M2M](m2m.md) flag |
| `volta_client_id` | string | `"billing-svc"` | M2M client name |

### Webhook payload structure

All volta webhook payloads follow the same structure:

```json
{
  "event": "event.type",
  "timestamp": "ISO-8601",
  "delivery_id": "uuid",
  "tenant_id": "uuid",
  "data": {
    // event-specific fields
  }
}
```

This consistent structure means your webhook handler can parse any volta event with the same base logic.

### Payload validation

volta validates incoming payloads at multiple levels:

```
  1. Content-Type check
     → Must be application/json (or application/scim+json for SCIM)

  2. Size check
     → Reject payloads exceeding the limit

  3. JSON parse
     → Must be valid JSON (no trailing commas, no comments)

  4. Schema validation
     → Required fields present, correct types

  5. Business validation
     → email is valid format, role exists, tenant is active
```

### Payload in [upstream](upstream.md) forwarding

When volta forwards a request to your upstream app, it passes the original payload through unchanged. volta does NOT modify the request body -- it only adds [headers](header.md) with authentication information:

```
  Client → volta (adds X-Volta-* headers) → Upstream
           payload passes through unchanged
```

---

## Common mistakes and attacks

### Mistake 1: Logging full payloads

Payloads often contain PII (emails, names, tokens). Log metadata (method, path, status code) but redact or omit payload content in production logs.

### Mistake 2: Not validating payload size

An attacker can send a 100 MB payload to exhaust memory. Always enforce size limits before parsing.

### Mistake 3: Trusting JWT payload without signature verification

The JWT payload is [base64url](base64.md)-encoded, not encrypted. Anyone can decode and read it. An attacker can also modify it. Always verify the signature before trusting the claims.

### Mistake 4: Including secrets in payloads

Do not put passwords, API keys, or other secrets in webhook payloads or JWT claims. Use references (IDs) instead of values.

### Attack: Payload injection

An attacker crafts a JSON payload with unexpected fields (e.g., `"role": "ADMIN"`) hoping the server blindly merges all fields. Defense: use strict deserialization that only reads expected fields and ignores extras.

### Attack: Oversized payload (DoS)

An attacker sends a multi-gigabyte payload to crash the server. Defense: enforce `Content-Length` limits before reading the body. volta limits request bodies by default.

---

## Further reading

- [jwt.md](jwt.md) -- JWT payload structure and claims.
- [base64.md](base64.md) -- How JWT payloads are encoded.
- [webhook.md](webhook.md) -- Webhook payload format and HMAC signing.
- [header.md](header.md) -- The metadata that accompanies payloads.
- [scim.md](scim.md) -- SCIM user/group payloads.
