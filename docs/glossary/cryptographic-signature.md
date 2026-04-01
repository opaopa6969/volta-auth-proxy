# Cryptographic Signature

[日本語版はこちら](cryptographic-signature.ja.md)

---

## What is it?

A cryptographic signature is a mathematical proof that a specific piece of data was created (or approved) by the holder of a particular [private key](key-cryptographic.md), and that the data has not been modified since signing. It is the digital equivalent of a handwritten signature -- but far more secure, because it is mathematically verifiable and impossible to forge without the key.

Imagine a notary stamp on a legal document. The notary examines the document, stamps it, and signs it. Anyone who sees the stamp knows: (1) a specific notary approved this document, and (2) if anyone alters the document after stamping, the alteration is detectable. A cryptographic signature provides the same two guarantees, but with mathematics instead of wax and ink.

Cryptographic signatures underpin all of volta's trust model. Every JWT carries a signature that proves volta issued it and that no one has tampered with its [claims](claim.md).

---

## Why does it matter?

Without cryptographic signatures, there is no way to verify that data came from a trusted source. Anyone could craft a JWT with `volta_roles: ["admin"]` and claim to be an administrator. Downstream services would have no way to distinguish legitimate tokens from forgeries.

Signatures provide three critical properties:

- **Authentication**: The signature proves WHO created the data (the holder of the private key).
- **Integrity**: The signature proves the data has NOT been changed since signing.
- **Non-repudiation**: The signer cannot deny having signed (only they have the private key).

If signatures fail, the entire trust chain collapses. volta cannot prove its tokens are real. Downstream apps cannot trust any JWT. The authentication system becomes theater.

---

## How does it work?

### The signing process

```
  Input:  data (JWT header + payload)
  Key:    private key (RSA-2048)

  ┌──────────────────────────────────────────────┐
  │  Step 1: Hash the data                       │
  │  hash = SHA-256(data)                        │
  │  Produces a fixed-size "fingerprint"         │
  │                                              │
  │  Step 2: Encrypt the hash with private key   │
  │  signature = RSA_ENCRYPT(hash, private_key)  │
  │                                              │
  │  Step 3: Attach signature to data            │
  │  result = data + "." + signature             │
  └──────────────────────────────────────────────┘

  Only the private key holder can produce this
  signature for this data.
```

### The verification process

```
  Input:  data + signature
  Key:    public key (corresponding to the private key)

  ┌──────────────────────────────────────────────┐
  │  Step 1: Hash the received data              │
  │  hash1 = SHA-256(data)                       │
  │                                              │
  │  Step 2: Decrypt the signature with          │
  │          public key                          │
  │  hash2 = RSA_DECRYPT(signature, public_key)  │
  │                                              │
  │  Step 3: Compare hashes                      │
  │  hash1 == hash2?                             │
  │  YES → Signature is VALID                    │
  │  NO  → Data was TAMPERED or FORGED           │
  └──────────────────────────────────────────────┘

  Anyone with the public key can verify, but
  nobody can create a valid signature without
  the private key.
```

### Signature vs. encryption

```
  ┌──────────────────────────────────────────────────┐
  │  SIGNATURE:                                      │
  │  Purpose: Prove authorship + integrity           │
  │  Data: VISIBLE to everyone (not hidden)          │
  │  Key:  Private key SIGNS, Public key VERIFIES    │
  │  Example: JWT signature (RS256)                  │
  │                                                  │
  │  ENCRYPTION:                                     │
  │  Purpose: Hide data from unauthorized readers    │
  │  Data: HIDDEN (only key holder can read)         │
  │  Key:  Public key ENCRYPTS, Private key DECRYPTS │
  │  Example: Keys encrypted with AES-256-GCM        │
  └──────────────────────────────────────────────────┘

  volta uses SIGNATURES for JWTs (data is visible,
  but guaranteed authentic).
  volta uses ENCRYPTION for keys at rest (data is
  hidden from database readers).
```

### Types of cryptographic signatures

| Type | Algorithm | Key type | volta usage |
|------|-----------|----------|-------------|
| RSA signature | [RS256](rs256.md) | Asymmetric (public/private) | JWT signing |
| [HMAC](hmac.md) | HMAC-SHA256 | Symmetric (shared secret) | Cookie signing |
| ECDSA | ES256 | Asymmetric (elliptic curve) | Not used by volta |

---

## How does volta-auth-proxy use it?

### JWT signatures (RS256)

Every JWT issued by volta carries an RS256 signature:

```java
// JwtService.java
SignedJWT jwt = new SignedJWT(
    new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID(rsaKey.getKeyID())
        .type(JOSEObjectType.JWT)
        .build(),
    claims
);
jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
```

The signature covers both the header and payload, so changing ANY claim invalidates the signature.

### JWT signature verification

```java
// JwtService.verify()
JWSVerifier verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey());
if (!jwt.verify(verifier)) {
    throw new IllegalArgumentException("Invalid JWT signature");
}
```

### Cookie signatures (HMAC-SHA256)

volta's [signed cookies](signed-cookie.md) use HMAC-SHA256:

```java
// SecurityUtils.java
public static String hmacSha256Hex(String secret, String payload) {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
    byte[] sig = mac.doFinal(payload.getBytes(UTF_8));
    // Convert to hex string
}
```

### Constant-time verification

All signature comparisons in volta use constant-time comparison to prevent timing attacks:

```java
public static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8));
}
```

---

## Common mistakes and attacks

### Mistake 1: Not verifying signatures at all

Some developers decode JWTs (Base64) and use the claims directly without checking the signature. This is like reading an unsigned letter and trusting its contents.

### Mistake 2: Using alg: none

The JWT spec allows `alg: none` (no signature). Some libraries accept this by default. volta explicitly rejects any algorithm other than RS256.

### Mistake 3: Confusing signing with encryption

Signing proves authenticity but does NOT hide data. JWT payloads are Base64-encoded (readable by anyone). If you need to hide data, use [encryption](encryption.md).

### Attack: Algorithm confusion (RS256 to HS256)

An attacker changes the JWT header to `alg: HS256` and uses the public key (which is publicly available from JWKS) as the HMAC secret. A vulnerable library accepts this forged token. volta prevents this by hard-coding RS256.

### Attack: Signature stripping

An attacker removes the signature from a JWT and changes `alg` to `none`. A vulnerable library accepts the unsigned token. volta checks the algorithm before attempting verification.

### Attack: Key confusion

An attacker uses a different key to sign a token. Without verifying that the key comes from the expected source, the signature check passes but the token is forged. volta only uses its own key for verification, never keys from JWT headers.

---

## Further reading

- [digital-signature.md](digital-signature.md) -- The broader concept of digital signatures
- [rs256.md](rs256.md) -- The specific signature algorithm volta uses for JWTs
- [hmac.md](hmac.md) -- The symmetric signature algorithm used for cookies
- [verification.md](verification.md) -- The process of checking signatures
- [signing-key.md](signing-key.md) -- The private key used to create signatures
- [public-key-cryptography.md](public-key-cryptography.md) -- The math behind asymmetric signatures
