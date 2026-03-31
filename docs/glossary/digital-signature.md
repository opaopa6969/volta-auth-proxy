# Digital Signature

[Japanese / 日本語](digital-signature.ja.md)

---

## What is it?

A digital signature is a mathematical proof that a piece of data was created (or approved) by a specific party and has not been tampered with since. It works like a handwritten signature on a contract, but it is far harder to forge. The signer uses their private key to produce the signature, and anyone with the corresponding public key can verify it.

---

## Why does it matter?

On the internet, you cannot look someone in the eye and hand them a document. Data passes through many intermediaries. Digital signatures solve two problems at once:

1. **Authenticity** -- "This really came from who it claims to come from." An attacker cannot forge a signature without the private key.
2. **Integrity** -- "This has not been modified." If even a single bit of the signed data changes, the signature becomes invalid.

JWTs rely entirely on digital signatures. When volta issues a JWT, it signs the token with its RSA private key. Downstream services verify the signature to confirm the token is genuine and untampered.

---

## Simple example

```
1. volta creates a JWT payload:
   {"sub": "user-123", "email": "alice@example.com", "exp": 1711900000}

2. volta signs it with its private key:
   SIGNATURE = RSA_SIGN(private_key, header + "." + payload)

3. The complete JWT is:
   header.payload.SIGNATURE

4. A downstream app receives the JWT and verifies:
   RSA_VERIFY(public_key, header + "." + payload, SIGNATURE)
   -> true (valid) or false (tampered/forged)
```

If an attacker modifies the payload (e.g., changes "user-123" to "admin-1"), the signature no longer matches. The verification fails, and the token is rejected.

---

## In volta-auth-proxy

volta's `JwtService` uses RS256 (RSA + SHA-256) to sign every JWT it issues:

```java
SignedJWT jwt = new SignedJWT(
    new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID(rsaKey.getKeyID())
        .type(JOSEObjectType.JWT)
        .build(),
    claims
);
jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
```

On the verification side, volta checks every incoming JWT:

- The algorithm must be RS256 (anything else is rejected, preventing "alg: none" attacks).
- The signature must be valid against volta's public key.
- The issuer must be `"volta-auth"`.
- The audience must include `"volta-apps"`.
- The token must not be expired.

This chain of checks means that a JWT passing verification is a trustworthy statement: "volta-auth-proxy confirmed this user's identity at this time, and nobody has modified the token since."

See also: [public-key-cryptography.md](public-key-cryptography.md), [hash-function.md](hash-function.md)
