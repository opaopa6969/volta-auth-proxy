# Auto Key Generation

[日本語版はこちら](auto-key-generation.ja.md)

---

## What is it?

Auto key generation is the practice of having a system automatically create its own cryptographic keys when it starts for the first time, rather than requiring an administrator to manually generate and configure them. The system detects "I have no keys yet," generates them on the spot, and stores them securely.

Imagine buying a new safe that comes without a combination. Instead of requiring you to call the manufacturer for a code, the safe generates its own unique combination the first time you power it on, prints it on a receipt for you, and locks itself. No setup wizard, no manual configuration -- it just works out of the box.

This is a key part of volta's "zero-config" philosophy: the system should be usable immediately after deployment, without the operator needing to understand cryptography or run key generation commands.

---

## Why does it matter?

Manual key management is error-prone and blocks deployment:

- **Forgotten step**: Operators forget to generate keys, and the system fails at runtime with cryptic errors.
- **Weak keys**: Operators generate keys with insufficient randomness or wrong parameters (e.g., 1024-bit RSA instead of 2048).
- **Copy-paste reuse**: Operators copy keys from tutorials or other environments, creating shared secrets across systems.
- **Configuration burden**: Every new environment (dev, staging, production) needs manual key setup.

Auto key generation eliminates all of these. The system always has correct, unique, strong keys from the moment it boots. This is especially important for [self-hosted](self-hosting.md) software where the operator may not be a security expert.

---

## How does it work?

### The first-boot flow

```
  volta-auth-proxy starts
         │
         ▼
  Query signing_keys table
         │
         ├── Keys exist? ──→ YES ──→ Load and decrypt keys
         │                           (using KeyCipher + AES-256-GCM)
         │                           Ready to sign JWTs.
         │
         └── Keys exist? ──→ NO ──→ Generate new RSA-2048 key pair
                                     │
                                     ▼
                                    Encrypt with AES-256-GCM
                                     │
                                     ▼
                                    Store in signing_keys table
                                     │
                                     ▼
                                    Ready to sign JWTs.
```

### What gets generated

```
  ┌──────────────────────────────────────────────────┐
  │  Auto-generated on first boot:                   │
  │                                                  │
  │  Algorithm:  RSA                                 │
  │  Key size:   2048 bits                           │
  │  Source:     java.security.KeyPairGenerator       │
  │  Randomness: java.security.SecureRandom          │
  │  Key ID:     "key-2026-04-01T12-00"              │
  │              (timestamp-based, unique)            │
  │                                                  │
  │  Storage:    signing_keys table                  │
  │  Encryption: AES-256-GCM (via KeyCipher)         │
  │  Format:     "v1:" + base64(IV) + ":" +          │
  │              base64(encrypted_key)               │
  └──────────────────────────────────────────────────┘
```

### Idempotency

Auto key generation must be idempotent -- running it multiple times should not create duplicate keys:

```
  Boot 1:  No keys in DB  →  Generate + store  →  Use key A
  Boot 2:  Key A in DB    →  Load key A        →  Use key A
  Boot 3:  Key A in DB    →  Load key A        →  Use key A
  ...
  Rotate:  Key A retired  →  Generate key B    →  Use key B
  Boot N:  Key B in DB    →  Load key B        →  Use key B
```

---

## How does volta-auth-proxy use it?

The auto key generation logic lives in `JwtService`:

```java
public JwtService(AppConfig config, SqlStore store) {
    this.config = config;
    this.store = store;
    this.keyCipher = new KeyCipher(config.jwtKeyEncryptionSecret());
    this.rsaKey = loadOrCreateKey();  // ← Auto key generation
}

private RSAKey loadOrCreateKey() {
    return store.loadActiveSigningKey()   // Try to load existing
            .map(this::restoreKey)         // Decrypt if found
            .orElseGet(this::createKey);   // Generate if not found
}
```

### Key creation details

```java
private RSAKey createKey() {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);  // Strong key size
    KeyPair keyPair = generator.generateKeyPair();
    String kid = "key-" + Instant.now()
        .truncatedTo(ChronoUnit.MINUTES)
        .toString().replace(":", "-");

    // Store encrypted -- never plaintext in DB
    store.saveSigningKey(kid,
        keyCipher.encrypt(base64(publicKey)),
        keyCipher.encrypt(base64(privateKey)));
    return rsaKey;
}
```

### What the operator needs to provide

Only one thing: `JWT_KEY_ENCRYPTION_SECRET` -- a passphrase used to derive the AES-256 key that encrypts the auto-generated RSA keys at rest. Everything else is automatic:

```
  Operator provides:                    volta generates automatically:
  ┌─────────────────────────────┐      ┌──────────────────────────────┐
  │ JWT_KEY_ENCRYPTION_SECRET   │      │ RSA-2048 key pair            │
  │ (environment variable)      │      │ Key ID (kid)                 │
  │                             │      │ AES-256 derived key          │
  │ That's it.                  │      │ Encrypted key storage        │
  └─────────────────────────────┘      │ JWKS endpoint                │
                                       │ JWT signing capability       │
                                       └──────────────────────────────┘
```

### Subsequent boots

On subsequent boots, `loadOrCreateKey()` finds the existing key in the database, decrypts it with `KeyCipher`, and resumes normal operation. No new key is generated. The system is stateless across restarts -- all state lives in the database.

---

## Common mistakes and attacks

### Mistake 1: Generating keys without encryption at rest

Auto-generated keys stored in plaintext in the database are exposed if the database is compromised. Always encrypt at rest. volta uses AES-256-GCM via [KeyCipher](encryption-at-rest.md).

### Mistake 2: Using a weak encryption secret

If `JWT_KEY_ENCRYPTION_SECRET` is "password123", the AES encryption is effectively useless. Use a strong, random passphrase.

### Mistake 3: Race conditions on first boot

If multiple instances start simultaneously, they might all see "no keys" and each generate their own. volta uses `synchronized` on `loadOrCreateKey()` and database constraints to prevent this.

### Mistake 4: Not supporting key rotation

Auto-generating a key on first boot is great, but you also need a way to replace it later. volta supports [key rotation](key-rotation.md) via the admin API while maintaining [graceful transition](graceful-transition.md).

### Mistake 5: Logging the generated key

Auto-generated keys must never appear in log output. volta's `KeyCipher.encrypt()` ensures only encrypted (unreadable) values are stored or transmitted.

---

## Further reading

- [signing-key.md](signing-key.md) -- The key that gets auto-generated
- [encryption-at-rest.md](encryption-at-rest.md) -- How auto-generated keys are protected in the database
- [self-signed.md](self-signed.md) -- Why auto-generated keys don't need a CA
- [key-rotation.md](key-rotation.md) -- Replacing the auto-generated key later
- [key-cryptographic.md](key-cryptographic.md) -- Cryptographic keys in general
