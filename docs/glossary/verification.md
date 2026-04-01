# Verification

[日本語版はこちら](verification.ja.md)

---

## What is it?

Verification is the process of confirming that something is authentic and has not been modified. In volta's context, it primarily means checking that a JWT's [digital signature](digital-signature.md) is valid -- proving the token was issued by volta and that its [claims](claim.md) have not been tampered with.

Think of it like checking a banknote. You hold the bill up to the light to see the watermark, run your finger over the raised print, and check the security strip. You are not creating money -- you are verifying that the money is genuine. Anyone can verify a banknote, but only the mint can create one. Similarly, anyone with the [public key](public-key-cryptography.md) can verify a JWT, but only the holder of the [signing key](signing-key.md) can create one.

Verification is distinct from decoding. Decoding a JWT (Base64) reveals its contents -- anyone can do this. Verification proves the contents are trustworthy. A decoded-but-unverified JWT is just untrustworthy JSON.

---

## Why does it matter?

Without verification, any system that receives a JWT must blindly trust it. An attacker could:

- Craft a fake JWT with `"volta_roles": ["admin"]` and gain admin access.
- Modify the `exp` claim to create a token that never expires.
- Change the `sub` claim to impersonate another user.
- Switch the `volta_tid` to access another tenant's data.

Verification is the gatekeeper that makes all of volta's security guarantees real. The [cryptographic signature](cryptographic-signature.md) is only useful if someone actually checks it.

---

## How does it work?

### JWT verification steps

```
  Incoming JWT: header.payload.signature
         │
         ▼
  Step 1: Parse the JWT
         │  Split into header, payload, signature
         │
         ▼
  Step 2: Check the algorithm
         │  header.alg MUST be RS256
         │  Reject "none", "HS256", or anything else
         │
         ▼
  Step 3: Verify the signature
         │  Recalculate: RS256(header + "." + payload, public_key)
         │  Compare with provided signature
         │  MATCH → token was signed by volta
         │  MISMATCH → token is forged or tampered → REJECT
         │
         ▼
  Step 4: Validate claims
         │  ├── iss == configured issuer?
         │  ├── aud contains configured audience?
         │  ├── exp > current time?
         │  └── All required claims present?
         │
         ▼
  Step 5: Token is verified. Extract claims and proceed.
```

### Signature verification in detail

```
  JWT:  eyJhbGciOi...  .  eyJzdWIiOi...  .  SflKxwRJSM...
        ─────────────     ─────────────     ─────────────
        header (B64)      payload (B64)     signature (B64)

  Verification:
  ┌──────────────────────────────────────────────────┐
  │                                                  │
  │  input = base64(header) + "." + base64(payload)  │
  │                                                  │
  │  expected_sig = RS256_SIGN(input, private_key)   │
  │  (we don't have private key -- so instead...)    │
  │                                                  │
  │  RS256_VERIFY(input, signature, public_key)      │
  │  → true: signature was created with matching     │
  │          private key                             │
  │  → false: signature is invalid                   │
  │                                                  │
  │  This is the magic of asymmetric crypto:         │
  │  You can VERIFY without being able to SIGN.      │
  └──────────────────────────────────────────────────┘
```

### What gets verified vs. what gets checked

```
  VERIFIED (cryptographic proof):
  ┌──────────────────────────────────────────┐
  │  "This token was signed by the holder    │
  │   of volta's private key, and no byte    │
  │   has been modified since signing."       │
  └──────────────────────────────────────────┘

  CHECKED (business logic):
  ┌──────────────────────────────────────────┐
  │  "This token is not expired."            │
  │  "This token was issued for my audience."│
  │  "This token was issued by volta."       │
  │  "This user has the required role."      │
  └──────────────────────────────────────────┘

  Both are required. Verification without claim
  checks accepts expired or misdirected tokens.
  Claim checks without verification accepts forgeries.
```

---

## How does volta-auth-proxy use it?

### JwtService.verify()

```java
public Map<String, Object> verify(String token) {
    SignedJWT jwt = SignedJWT.parse(token);

    // Step 1: Check algorithm
    if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
        throw new IllegalArgumentException("Unsupported JWT algorithm");
    }

    // Step 2: Verify signature
    JWSVerifier verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey());
    if (!jwt.verify(verifier)) {
        throw new IllegalArgumentException("Invalid JWT signature");
    }

    // Step 3: Validate claims
    JWTClaimsSet claims = jwt.getJWTClaimsSet();
    if (!config.jwtIssuer().equals(claims.getIssuer())) {
        throw new IllegalArgumentException("Invalid issuer");
    }
    if (!claims.getAudience().contains(config.jwtAudience())) {
        throw new IllegalArgumentException("Invalid audience");
    }
    if (claims.getExpirationTime() == null
        || claims.getExpirationTime().before(new Date())) {
        throw new IllegalArgumentException("Token expired");
    }
    return claims.getClaims();
}
```

### Cookie signature verification

volta also verifies [signed cookies](signed-cookie.md) using [HMAC](hmac.md):

```java
String expected = SecurityUtils.hmacSha256Hex(secret, cookieValue);
if (!SecurityUtils.constantTimeEquals(expected, providedSignature)) {
    // Cookie has been tampered with
    throw new IllegalArgumentException("Invalid cookie signature");
}
```

### Constant-time comparison

volta uses `MessageDigest.isEqual()` for all signature comparisons to prevent timing attacks:

```java
public static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8),
        b.getBytes(StandardCharsets.UTF_8));
}
```

---

## Common mistakes and attacks

### Mistake 1: Verifying signature but not claims

A valid signature only means the token was issued by volta. It does not mean the token is still valid (`exp`), intended for your service (`aud`), or from the expected issuer (`iss`). Always check all claims.

### Mistake 2: Accepting alg: none

Some JWT libraries accept `alg: none` (no signature). This means anyone can create a "valid" token. volta hard-codes RS256 and rejects everything else.

### Mistake 3: Using the wrong key for verification

If a service uses the wrong public key (e.g., from a different environment), all tokens appear invalid. Ensure the JWKS endpoint matches the issuer.

### Attack: Algorithm confusion (RS256 → HS256)

An attacker changes the JWT header to `alg: HS256` and signs with the public key (which is, well, public). A vulnerable library treats the public key as an HMAC secret and accepts the forged token. volta prevents this by checking `alg == RS256` before verification.

### Attack: Key injection via JKU/X5U

Some JWT headers include a URL pointing to the signing key (`jku` or `x5u`). An attacker can point this to their own key server. volta ignores these headers and only uses its own configured key.

---

## Further reading

- [digital-signature.md](digital-signature.md) -- The mathematical foundation of verification
- [cryptographic-signature.md](cryptographic-signature.md) -- Signatures as mathematical proofs
- [rs256.md](rs256.md) -- The specific algorithm volta uses for verification
- [claim.md](claim.md) -- The data verified inside the JWT
- [signing-key.md](signing-key.md) -- The key pair used for signing and verification
- [hmac.md](hmac.md) -- Verification used for signed cookies
