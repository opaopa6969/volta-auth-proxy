# HMAC

[日本語版はこちら](hmac.ja.md)

---

## What is it?

HMAC (Hash-based Message Authentication Code) is a specific way to create a [cryptographic signature](cryptographic-signature.md) using a [hash function](hash-function.md) combined with a secret key. It proves both that a message is authentic (came from someone who knows the secret) and that it has not been tampered with.

Think of it like a secret handshake at a club. Both you and the bouncer know the handshake. When you approach, you perform the handshake. If the bouncer recognizes it, you are in. If someone who does not know the handshake tries a random gesture, they are rejected. The key point: both parties share the same secret (the handshake pattern). This is what makes HMAC "symmetric" -- unlike [RS256](rs256.md) where only one side has the private key.

HMAC is simpler and faster than asymmetric signatures, making it ideal for situations where both the signer and verifier are the same system (or share a secret). volta uses HMAC-SHA256 for [cookie signing](signed-cookie.md) and webhook verification.

---

## Why does it matter?

HMAC solves a specific problem: "How do I know this data has not been tampered with, when both sides share a secret?"

Without HMAC:
- An attacker could modify a [cookie](cookie.md) value and the server would not detect it.
- Webhook payloads could be forged, triggering unauthorized actions.
- Configuration data could be tampered with in transit.

A plain [hash](hash-function.md) (SHA-256 alone) does not solve this because an attacker can compute the hash of any value -- there is no secret. HMAC adds a secret key to the hash computation, making it impossible to forge without knowing the key.

---

## How does it work?

### HMAC construction

```
  HMAC-SHA256(key, message):

  ┌──────────────────────────────────────────────────┐
  │  Step 1: Prepare key                             │
  │  If key > block size: key = SHA256(key)          │
  │  Pad key to block size (64 bytes for SHA-256)    │
  │                                                  │
  │  Step 2: Inner hash                              │
  │  inner_pad = key XOR 0x36363636...               │
  │  inner_hash = SHA256(inner_pad + message)        │
  │                                                  │
  │  Step 3: Outer hash                              │
  │  outer_pad = key XOR 0x5C5C5C5C...               │
  │  hmac = SHA256(outer_pad + inner_hash)           │
  │                                                  │
  │  Result: 32-byte (256-bit) authentication code   │
  └──────────────────────────────────────────────────┘
```

### Why not just hash(key + message)?

```
  Naive approach: SHA256(key + message)
  ┌──────────────────────────────────────────────┐
  │  Problem: Length extension attack             │
  │  SHA-256 is based on Merkle-Damgard.         │
  │  Knowing SHA256(key + message), an attacker  │
  │  can compute SHA256(key + message + extra)   │
  │  WITHOUT knowing the key!                    │
  │                                              │
  │  HMAC prevents this with the double-hash     │
  │  (inner + outer) construction.               │
  └──────────────────────────────────────────────┘
```

### HMAC vs. RSA signatures

```
  HMAC (symmetric):
  ┌──────────────────────────────────────────────┐
  │  Same key signs AND verifies                 │
  │  Both parties must share the secret          │
  │  Fast: ~100x faster than RSA                 │
  │  Used for: cookie signing, webhooks          │
  │                                              │
  │  volta: SecurityUtils.hmacSha256Hex()        │
  └──────────────────────────────────────────────┘

  RSA signature (asymmetric):
  ┌──────────────────────────────────────────────┐
  │  Private key signs, public key verifies      │
  │  Only signer needs the private key           │
  │  Slower, but public key can be shared freely │
  │  Used for: JWT signing (RS256)               │
  │                                              │
  │  volta: JwtService.issueToken()              │
  └──────────────────────────────────────────────┘
```

### HMAC verification

```
  Server creates cookie:
  value = "session-uuid"
  sig = HMAC-SHA256(secret, value) = "a3f2c8..."
  cookie = value + "." + sig

  Later, server receives cookie:
  ┌──────────────────────────────────────────┐
  │  Split cookie → value, provided_sig      │
  │  Recalculate: expected_sig =             │
  │    HMAC-SHA256(secret, value)            │
  │                                          │
  │  constantTimeEquals(expected, provided)? │
  │  YES → Cookie is authentic               │
  │  NO  → Cookie was tampered → reject      │
  └──────────────────────────────────────────┘
```

---

## How does volta-auth-proxy use it?

### Cookie signing

`SecurityUtils.hmacSha256Hex()` is used to sign the `__volta_session` cookie:

```java
public static String hmacSha256Hex(String secret, String payload) {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(
        secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder(sig.length * 2);
    for (byte b : sig) {
        sb.append(String.format("%02x", b));
    }
    return sb.toString();
}
```

### Constant-time comparison

HMAC verification uses constant-time comparison to prevent timing side-channel attacks:

```java
public static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) return false;
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8),
        b.getBytes(StandardCharsets.UTF_8));
}
```

If `String.equals()` were used, an attacker could measure response times to guess the HMAC one byte at a time.

### Webhook verification

When volta sends webhooks to downstream services, the payload can be signed with HMAC-SHA256. The receiving service verifies the signature using the shared webhook secret:

```
  volta sends:
  POST /webhook
  X-Volta-Signature: sha256=a3f2c8e1...
  Body: {"event": "user.created", "data": {...}}

  Receiver verifies:
  expected = HMAC-SHA256(webhook_secret, body)
  constantTimeEquals(expected, header_sig)? → Accept / Reject
```

---

## Common mistakes and attacks

### Mistake 1: Using a plain hash instead of HMAC

`SHA256(value)` can be computed by anyone. `HMAC-SHA256(secret, value)` requires the secret. Always use HMAC when you need authentication.

### Mistake 2: Weak HMAC keys

An HMAC key of "secret" or "password" can be brute-forced. Use a strong, random key at least 32 bytes long.

### Mistake 3: Using String.equals() for comparison

`String.equals()` returns early on the first mismatched byte, leaking timing information. Always use constant-time comparison for HMAC verification.

### Mistake 4: HMAC without checking message freshness

HMAC proves authenticity but not freshness. An attacker can replay an old HMAC-signed message. Combine HMAC with timestamps or [nonces](nonce.md) to prevent replay.

### Attack: Timing side-channel

An attacker sends requests with different HMAC values and measures response times. If comparison leaks timing, they can reconstruct the HMAC byte by byte. volta's `constantTimeEquals()` prevents this.

### Attack: HMAC key as RS256 public key (algorithm confusion)

If a system accepts both HMAC (HS256) and RSA (RS256) for JWT verification, an attacker can use the public RSA key (which is public) as the HMAC secret. volta prevents this by only accepting RS256 for JWTs and using HMAC only for cookies.

---

## Further reading

- [hash-function.md](hash-function.md) -- The underlying hash function (SHA-256)
- [signed-cookie.md](signed-cookie.md) -- How HMAC is used for cookie integrity
- [cryptographic-signature.md](cryptographic-signature.md) -- The broader concept of signatures
- [rs256.md](rs256.md) -- The asymmetric alternative for JWT signing
- [nonce.md](nonce.md) -- Preventing replay attacks on HMAC-signed messages
