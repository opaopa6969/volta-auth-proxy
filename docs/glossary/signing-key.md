# Signing Key

[日本語版はこちら](signing-key.ja.md)

---

## What is it?

A signing key is the private [cryptographic key](key-cryptographic.md) used to create [digital signatures](digital-signature.md) on data -- in volta's case, on [JWTs](jwt.md). When volta signs a JWT, it uses the signing key to produce a mathematical proof that the token was issued by volta and has not been modified.

Think of it like your personal wax seal stamp. In the old days, a nobleman would press a unique seal into hot wax on a letter. Anyone who knew the seal pattern could verify the letter came from that nobleman -- but only the nobleman had the stamp to create the seal. The signing key is the stamp. The [public key](public-key-cryptography.md) is the knowledge of what the seal should look like.

The signing key must remain absolutely secret. If anyone else obtains it, they can forge signatures that are indistinguishable from legitimate ones. This is why volta encrypts signing keys at rest with [AES-256-GCM](encryption-at-rest.md) and never exposes them through any API.

---

## Why does it matter?

The signing key is the root of trust in volta's entire authentication system:

- **JWT integrity**: Every JWT's signature is created with the signing key. If the key leaks, an attacker can create JWTs with any [claims](claim.md) -- making themselves admin of any tenant.
- **Non-repudiation**: The signature proves volta issued the token. Without a secure signing key, this guarantee vanishes.
- **Cascade failure**: A compromised signing key compromises every downstream service that trusts volta's JWTs. The blast radius is the entire system.

This is why volta implements multiple layers of protection: [encryption at rest](encryption-at-rest.md), [auto-generation](auto-key-generation.md) to prevent weak keys, and [rotation](key-rotation.md) to limit exposure time.

---

## How does it work?

### Signing vs. verification

```
  Signing (private key -- SECRET):
  ┌──────────────────────────────────────────────┐
  │                                              │
  │  JWT Header + Payload                        │
  │         │                                    │
  │         ▼                                    │
  │  RS256 algorithm + Private Key               │
  │         │                                    │
  │         ▼                                    │
  │  Signature (appended to JWT)                 │
  │                                              │
  │  Only volta can do this.                     │
  └──────────────────────────────────────────────┘

  Verification (public key -- SHARED):
  ┌──────────────────────────────────────────────┐
  │                                              │
  │  JWT Header + Payload + Signature            │
  │         │                                    │
  │         ▼                                    │
  │  RS256 algorithm + Public Key                │
  │         │                                    │
  │         ▼                                    │
  │  Valid? YES / NO                             │
  │                                              │
  │  Anyone with the public key can do this.     │
  └──────────────────────────────────────────────┘
```

### Key pair relationship

```
  ┌──────────────────────────────────┐
  │  RSA-2048 Key Pair               │
  │                                  │
  │  Private key (signing key):      │
  │  ┌────────────────────────────┐  │
  │  │ 2048-bit RSA private key  │  │
  │  │ Used to SIGN JWTs         │  │
  │  │ Stored encrypted in DB    │  │
  │  │ Never leaves the server   │  │
  │  └────────────────────────────┘  │
  │           │                      │
  │           │ mathematically       │
  │           │ derived              │
  │           ▼                      │
  │  Public key (verification key):  │
  │  ┌────────────────────────────┐  │
  │  │ Corresponding public key  │  │
  │  │ Used to VERIFY JWTs       │  │
  │  │ Published at JWKS endpoint│  │
  │  │ Safe to share with anyone │  │
  │  └────────────────────────────┘  │
  └──────────────────────────────────┘
```

### Key identification (kid)

Each signing key has a unique Key ID (`kid`). This is embedded in every JWT header so verifiers know which key was used:

```json
{
  "alg": "RS256",
  "typ": "JWT",
  "kid": "key-2026-04-01T12-00"
}
```

During [key rotation](key-rotation.md), multiple keys may coexist. The `kid` ensures the correct key is used for verification.

---

## How does volta-auth-proxy use it?

### Storage

Signing keys are stored in the `signing_keys` table, encrypted with AES-256-GCM:

```
  signing_keys table:
  ┌────────────────────────────────────────────────┐
  │ kid         │ "key-2026-04-01T12-00"           │
  │ public_pem  │ "v1:IV_base64:encrypted_base64"  │
  │ private_pem │ "v1:IV_base64:encrypted_base64"  │
  │ status      │ "active"                         │
  │ created_at  │ 2026-04-01T12:00:00Z             │
  └────────────────────────────────────────────────┘
```

### Signing a JWT

`JwtService.issueToken()` uses the signing key via nimbus-jose-jwt:

```java
SignedJWT jwt = new SignedJWT(
    new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID(rsaKey.getKeyID())       // kid in header
        .type(JOSEObjectType.JWT)
        .build(),
    claims
);
jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));  // Sign with private key
```

### Verification

`JwtService.verify()` uses the corresponding public key:

```java
JWSVerifier verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey());
if (!jwt.verify(verifier)) {
    throw new IllegalArgumentException("Invalid JWT signature");
}
```

### JWKS publication

The public key (not the signing key) is published at `/.well-known/jwks.json`:

```java
public String jwksJson() {
    RSAKey publicKey = new RSAKey.Builder(rsaKey.toRSAPublicKey())
            .keyID(rsaKey.getKeyID())
            .algorithm(JWSAlgorithm.RS256)
            .build();
    // Only the public key -- private key is NOT included
    return new JWKSet(publicKey).toJSONObject();
}
```

### Rotation

When `rotateKey()` is called, the current signing key is retired and a new one is created. See [key-rotation](key-rotation.md) and [graceful-transition](graceful-transition.md).

---

## Common mistakes and attacks

### Mistake 1: Exposing the signing key via API

Never include the private key in JWKS or any API response. Only the public key should be shared. volta's `jwksJson()` explicitly builds a public-key-only `RSAKey`.

### Mistake 2: Using the same signing key across environments

Dev, staging, and production should each have their own signing keys. volta's [auto-key-generation](auto-key-generation.md) ensures this naturally -- each environment generates its own key on first boot.

### Mistake 3: Storing signing keys in plaintext

If the database is breached, plaintext signing keys let attackers forge any JWT. volta encrypts all signing keys with AES-256-GCM via `KeyCipher`.

### Attack: Key compromise

If an attacker obtains the signing key, they can forge JWTs with arbitrary claims (any user, any tenant, any role). Mitigations: [encryption at rest](encryption-at-rest.md), short token lifetimes (5 min), immediate [key rotation](key-rotation.md) on suspicion.

### Attack: Algorithm confusion

An attacker changes the JWT header to `alg: HS256` and uses the public key as the HMAC secret. volta prevents this by hard-coding RS256 in `JwtService.verify()`:

```java
if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
    throw new IllegalArgumentException("Unsupported JWT algorithm");
}
```

---

## Further reading

- [key-cryptographic.md](key-cryptographic.md) -- Cryptographic keys in general
- [digital-signature.md](digital-signature.md) -- How signatures work mathematically
- [rs256.md](rs256.md) -- The specific algorithm used with the signing key
- [encryption-at-rest.md](encryption-at-rest.md) -- How signing keys are protected in storage
- [auto-key-generation.md](auto-key-generation.md) -- How signing keys are created automatically
- [key-rotation.md](key-rotation.md) -- Replacing signing keys over time
