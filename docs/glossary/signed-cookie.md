# Signed Cookie

[日本語版はこちら](signed-cookie.ja.md)

---

## What is it?

A signed cookie is a [cookie](cookie.md) that includes an [HMAC](hmac.md) signature to detect tampering. The server generates a signature over the cookie's value using a secret key, and appends it to the cookie. On every subsequent request, the server recalculates the signature and compares it -- if the value was modified, the signatures will not match, and the server rejects the cookie.

Think of it like a sealed envelope with a tamper-evident sticker. You put a letter inside, seal it, and place a special sticker across the seal. If someone opens the envelope and re-seals it, the sticker will be broken or look different. The recipient can tell the envelope was opened. The signed cookie's HMAC is that tamper-evident sticker.

Important: signing is not encryption. A signed cookie's value is readable by anyone who inspects it (just like you can see the envelope exists). The signature only proves the value has not been changed -- it does not hide it. For volta's `__volta_session` cookie, this is fine because the value is just a UUID (session ID), not sensitive data.

---

## Why does it matter?

Without a signature, an attacker who can access the cookie (via XSS, network interception, or browser devtools) could modify its value. For example:

- Change the session ID to another user's session ID (session hijacking).
- Craft a fake session ID that might collide with a valid one.
- Inject malicious data if the cookie carries structured data.

A signature makes these attacks detectable. Even if the attacker can read the cookie value, they cannot forge a valid signature without the server's secret key. Modified cookies are rejected immediately.

---

## How does it work?

### Signing process

```
  Server creates cookie:
  ┌──────────────────────────────────────────────────┐
  │                                                  │
  │  Value: "550e8400-e29b-41d4-a716-446655440000"   │
  │  Secret: "server-secret-key"                     │
  │                                                  │
  │  Signature = HMAC-SHA256(secret, value)          │
  │            = "a3f2c8e1..."                        │
  │                                                  │
  │  Cookie = value + "." + signature                │
  │  "550e8400-e29b-41d4...a3f2c8e1..."              │
  └──────────────────────────────────────────────────┘

  Set-Cookie: __volta_session=550e8400...a3f2c8e1;
              HttpOnly; Secure; SameSite=Lax
```

### Verification process

```
  Browser sends cookie back:
  ┌──────────────────────────────────────────────────┐
  │  Cookie: __volta_session=550e8400...a3f2c8e1     │
  │                                                  │
  │  Server splits: value = "550e8400..."            │
  │                 sig   = "a3f2c8e1..."            │
  │                                                  │
  │  Recalculate: HMAC-SHA256(secret, value)         │
  │             = "a3f2c8e1..."                      │
  │                                                  │
  │  Compare: received sig == calculated sig?        │
  │           "a3f2c8e1" == "a3f2c8e1" → MATCH ✓    │
  │                                                  │
  │  Cookie is authentic. Proceed.                   │
  └──────────────────────────────────────────────────┘
```

### Tamper detection

```
  Attacker modifies cookie value:
  ┌──────────────────────────────────────────────────┐
  │  Original: "550e8400...a3f2c8e1"                 │
  │  Modified: "ATTACKER-SESSION-ID...a3f2c8e1"      │
  │                                                  │
  │  Server recalculates:                            │
  │  HMAC-SHA256(secret, "ATTACKER-SESSION-ID")      │
  │  = "7b9d4f2a..."                                 │
  │                                                  │
  │  Compare: "a3f2c8e1" == "7b9d4f2a"? → MISMATCH  │
  │                                                  │
  │  Cookie REJECTED. Attacker detected. → 401       │
  └──────────────────────────────────────────────────┘
```

### Why HMAC and not a plain hash?

```
  Plain hash (SHA-256):
  ┌──────────────────────────────────────────────┐
  │  sig = SHA256(value)                         │
  │                                              │
  │  Problem: Attacker knows the algorithm.      │
  │  They can compute SHA256("evil-value")       │
  │  and create a valid value+hash pair.         │
  │  No secret needed!                           │
  └──────────────────────────────────────────────┘

  HMAC (keyed hash):
  ┌──────────────────────────────────────────────┐
  │  sig = HMAC-SHA256(SECRET, value)            │
  │                                              │
  │  Attacker needs the SECRET to compute        │
  │  a valid signature.                          │
  │  Without the secret, they cannot forge it.   │
  └──────────────────────────────────────────────┘
```

---

## How does volta-auth-proxy use it?

volta's `__volta_session` cookie is a signed cookie. The signing is handled by `SecurityUtils.hmacSha256Hex()`:

```java
public static String hmacSha256Hex(String secret, String payload) {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(
        secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    // Convert to hex string
    StringBuilder sb = new StringBuilder(sig.length * 2);
    for (byte b : sig) {
        sb.append(String.format("%02x", b));
    }
    return sb.toString();
}
```

### Cookie structure

```
  __volta_session cookie:
  ┌──────────────────────────────────────────────────┐
  │  Value: session UUID                             │
  │  Signed with: HMAC-SHA256                        │
  │  Secret: derived from server configuration       │
  │                                                  │
  │  Attributes:                                     │
  │  ├── HttpOnly  (no JavaScript access)            │
  │  ├── Secure    (HTTPS only)                      │
  │  ├── SameSite=Lax (CSRF protection)              │
  │  ├── Path=/    (all routes)                      │
  │  └── Max-Age=28800 (8h sliding window)           │
  └──────────────────────────────────────────────────┘
```

### Constant-time comparison

volta uses `SecurityUtils.constantTimeEquals()` for signature comparison to prevent timing attacks:

```java
public static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) return false;
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8),
        b.getBytes(StandardCharsets.UTF_8));
}
```

A timing attack measures how long the comparison takes. If `String.equals()` returns early on the first mismatched character, an attacker can guess the signature one character at a time. `constantTimeEquals` always takes the same time regardless of where the mismatch is.

---

## Common mistakes and attacks

### Mistake 1: Using a plain hash instead of HMAC

SHA-256 without a key means anyone can compute the hash. Always use a keyed hash ([HMAC](hmac.md)).

### Mistake 2: Weak signing secret

If the signing secret is guessable ("secret", "password"), an attacker can forge valid signatures. Use a strong, random secret.

### Mistake 3: Not using constant-time comparison

Using `==` or `.equals()` for signature comparison leaks timing information. Always use constant-time comparison.

### Mistake 4: Signing but not setting HttpOnly

A signed cookie that is readable by JavaScript can still be stolen by XSS. The signature prevents modification but not theft. Always combine signing with HttpOnly.

### Attack: Cookie replay

An attacker steals the entire signed cookie (value + signature) and replays it. Signing does not prevent this -- it only prevents modification. Defenses: HttpOnly (prevents JS theft), Secure (prevents network interception), short expiry, [session fixation](session-fixation.md) prevention.

---

## Further reading

- [cookie.md](cookie.md) -- Cookies in general
- [hmac.md](hmac.md) -- The algorithm used for cookie signing
- [session.md](session.md) -- What the signed cookie represents
- [session-fixation.md](session-fixation.md) -- Attacks that signed cookies alone do not prevent
- [csrf.md](csrf.md) -- How SameSite on signed cookies prevents CSRF
