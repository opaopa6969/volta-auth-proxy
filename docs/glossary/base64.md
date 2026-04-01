# Base64

[日本語版はこちら](base64.ja.md)

---

## What is it?

Base64 is an encoding scheme that converts binary data (raw bytes) into a text string using only 64 safe characters: A-Z, a-z, 0-9, `+`, and `/` (with `=` for padding). It does NOT encrypt anything -- it simply represents binary data in a text-safe format that can be safely transmitted in URLs, HTTP [headers](header.md), JSON, and other text-based protocols.

Think of it like Morse code. Morse code converts letters into dots and dashes so they can be transmitted over a telegraph wire. The message is not secret -- anyone who knows Morse code can read it. Similarly, base64 converts binary data into text characters so it can travel through systems that only understand text. It is a transport encoding, not a security measure.

In volta-auth-proxy, base64 (specifically the URL-safe variant, base64url) is used to encode [JWT](jwt.md) parts, [invitation codes](invitation-code.md), and other binary values that need to travel in URLs or HTTP headers.

---

## Why does it matter?

Many protocols and data formats only support text:

```
  Problem: Binary data in text-only contexts

  Raw binary: [0x7A, 0x3F, 0xB2, 0x00, 0xFF, 0x91]
                                    ↑
                                  NULL byte!
                          (breaks C strings, HTTP headers,
                           JSON values, URLs...)

  Base64:     "ej+yAP+R"
              (safe ASCII text, works everywhere)
```

Without base64:

- JWT tokens could not be put in HTTP headers (binary data would break the header format)
- Invitation codes could not be put in URLs (special characters would be misinterpreted)
- Cryptographic hashes could not be stored in JSON (null bytes would truncate strings)

---

## How does it work?

### The encoding process

Base64 converts every 3 bytes (24 bits) into 4 characters (6 bits each):

```
  Input: 3 bytes = 24 bits
  ┌────────────────────────────────────────┐
  │ Byte 1: 01001101  (M = 77)            │
  │ Byte 2: 01100001  (a = 97)            │
  │ Byte 3: 01101110  (n = 110)           │
  └────────────────────────────────────────┘

  Regroup into 6-bit chunks:
  ┌────────┬────────┬────────┬────────┐
  │ 010011 │ 010110 │ 000101 │ 101110 │
  │  = 19  │  = 22  │  = 5   │  = 46  │
  └────────┴────────┴────────┴────────┘

  Look up in base64 alphabet:
  ┌─────┬─────┬─────┬─────┐
  │  T  │  W  │  F  │  u  │
  └─────┴─────┴─────┴─────┘

  "Man" → "TWFu"
```

### The base64 alphabet

```
  Index: Character
  0-25:  A-Z
  26-51: a-z
  52-61: 0-9
  62:    +
  63:    /
  Padding: =
```

### Padding

When the input length is not a multiple of 3, padding (`=`) is added:

```
  "M"   (1 byte)  → "TQ=="    (2 padding)
  "Ma"  (2 bytes) → "TWE="    (1 padding)
  "Man" (3 bytes) → "TWFu"    (0 padding)
```

### Base64 vs base64url

Standard base64 uses `+` and `/` which have special meaning in URLs. Base64url replaces them:

```
  Standard base64:  +  /  =
  Base64url:        -  _  (no padding)

  Example:
  Standard: "ej+yAP+R/w=="
  URL-safe: "ej-yAP-R_w"
```

volta uses base64url (RFC 4648 Section 5) for everything that appears in URLs or HTTP contexts.

### Size increase

Base64 encoding increases size by ~33%:

```
  Original:  32 bytes
  Base64:    ceil(32 / 3) * 4 = 44 characters
  Base64url: 43 characters (no padding)
```

---

## How does volta-auth-proxy use it?

### JWT structure

A [JWT](jwt.md) consists of three base64url-encoded parts separated by dots:

```
  JWT = base64url(header) + "." + base64url(payload) + "." + base64url(signature)

  Example:
  eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyLXV1aWQiLCJ2b2x0YV90aWQiOiJhY21lIn0.signature...
  ├─── header ────────┤ ├──────────── payload ────────────────────────────────┤ ├── sig ──┤

  Decoded header:  {"alg":"RS256"}
  Decoded payload: {"sub":"user-uuid","volta_tid":"acme"}
```

### Invitation codes

[Invitation codes](invitation-code.md) are 32 random bytes encoded as base64url:

```java
byte[] random = new byte[32];
SecureRandom.getInstanceStrong().nextBytes(random);
String code = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
// → "ej-ykYH3mQ_7vKxN2bFqLpR8s0T4uWdZ9aIcBvExHJM"
```

Base64url is used because the code appears in invitation URLs:

```
https://app.acme.com/invite?code=ej-ykYH3mQ_7vKxN2bFqLpR8s0T4uWdZ9aIcBvExHJM
```

### Cookie values

[Session](session.md) tokens stored in [cookies](cookie.md) are base64url-encoded to ensure they are safe in HTTP `Set-Cookie` headers.

### HMAC signatures

[Webhook](webhook.md) HMAC signatures are often hex-encoded rather than base64, but the webhook [payload](payload.md) itself may contain base64-encoded binary fields.

---

## Common mistakes and attacks

### Mistake 1: Thinking base64 is encryption

Base64 is encoding, NOT encryption. Anyone can decode it instantly:

```
  "Secret" in base64: "U2VjcmV0"
  Decoding: echo "U2VjcmV0" | base64 -d → "Secret"

  NEVER use base64 to "hide" sensitive data.
  Use actual encryption (AES, RSA) for secrecy.
```

### Mistake 2: Using standard base64 in URLs

Standard base64 uses `+`, `/`, and `=` which have special meanings in URLs. Always use base64url for URL contexts. volta enforces this.

### Mistake 3: Double encoding

Encoding data twice creates bloated, confusing values. Always encode once at the boundary (when entering a text context) and decode once at the other boundary.

### Mistake 4: Not handling padding correctly

Some systems add `=` padding, others do not. volta uses `withoutPadding()` for base64url. When decoding, accept both padded and unpadded input.

### Attack: JWT [payload](payload.md) tampering

An attacker decodes a JWT's base64url payload, modifies it (e.g., changes role to "ADMIN"), re-encodes it, and hopes the signature check is skipped. Defense: volta always verifies the RS256 signature. The [whitelist](whitelist.md) ensures only RS256 is accepted.

### Attack: Encoding confusion

An attacker sends data with mixed encoding (e.g., base64 inside URL encoding inside base64) to bypass input validation. Defense: decode exactly once, validate the decoded result.

---

## Further reading

- [RFC 4648](https://tools.ietf.org/html/rfc4648) -- Base16, Base32, Base64 encoding specifications.
- [jwt.md](jwt.md) -- JWT structure uses base64url encoding.
- [invitation-code.md](invitation-code.md) -- Invitation codes encoded as base64url.
- [payload.md](payload.md) -- JWT payload is base64url-encoded JSON.
- [cookie.md](cookie.md) -- Cookie values use text-safe encoding.
