# Public-Key Cryptography

[Japanese / 日本語](public-key-cryptography.ja.md)

---

## What is it?

Public-key cryptography (also called asymmetric cryptography) is a system that uses two mathematically linked keys: a **public key** that anyone can see, and a **private key** that only the owner keeps secret. Data encrypted with the public key can only be decrypted with the private key, and vice versa. This solves a fundamental problem: how do two parties communicate securely without first meeting to exchange a secret?

---

## Why does it matter?

Before public-key cryptography, if two people wanted to send encrypted messages, they needed to share a secret key in advance. That is the "key distribution problem" -- how do you securely share the key? Public-key cryptography eliminates this. You publish your public key openly. Anyone can use it to encrypt a message for you, but only you can decrypt it with your private key.

In authentication, public-key cryptography enables **digital signatures**. A server signs data with its private key, and anyone can verify the signature using the public key. This is how JWTs work: volta signs tokens with its private RSA key, and downstream services verify them with the public key from the JWKS endpoint.

---

## Simple example

Think of a mailbox analogy:

- **Public key** = the mail slot on your front door. Anyone can drop a letter in.
- **Private key** = the key to open the mailbox. Only you have it.

For **signing** (proving authorship), it works in reverse:

- You write a letter and stamp it with your private key (signature).
- Anyone can check the stamp against your public key to confirm it really came from you.

```
Signing:    Private Key  +  Data  ->  Signature
Verifying:  Public Key   +  Data  +  Signature  ->  Valid / Invalid
```

---

## In volta-auth-proxy

volta uses RSA public-key cryptography (2048-bit keys) for JWT signing:

- **Private key**: Used by `JwtService` to sign JWTs with the RS256 algorithm. Stored encrypted in the database (see [encryption-at-rest.md](encryption-at-rest.md)). Never leaves the server.
- **Public key**: Published at the `/.well-known/jwks.json` endpoint as a JWK (JSON Web Key). Any downstream service can fetch this to verify volta's JWTs without needing any shared secret.

```java
// Signing (only volta can do this)
jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));

// Verification (anyone with the public key can do this)
JWSVerifier verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey());
jwt.verify(verifier);
```

This means downstream apps never need volta's private key. They just fetch the public key from JWKS and verify independently. If the private key is compromised, volta can rotate to a new key pair (see [key-rotation.md](key-rotation.md)) without coordinating with every downstream service.

See also: [digital-signature.md](digital-signature.md), [hash-function.md](hash-function.md)
