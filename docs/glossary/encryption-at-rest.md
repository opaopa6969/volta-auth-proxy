# Encryption at Rest

[Japanese / 日本語](encryption-at-rest.ja.md)

---

## What is it?

Encryption at rest means protecting data while it is stored (on disk, in a database, on a backup tape) as opposed to data in transit (moving over a network). Even if someone gains physical access to the storage or a database dump leaks, the data remains unreadable without the decryption key. It is like locking your valuables in a safe even when the house itself is locked.

---

## Why does it matter?

Databases get breached. Backups get stolen. Disks get decommissioned without proper wiping. If sensitive data -- like cryptographic signing keys -- is stored in plain text, anyone who accesses the storage has everything they need to impersonate the system. Encryption at rest adds a second layer of defense: even after a breach, the attacker gets ciphertext, not usable keys.

For an auth gateway like volta, the most sensitive data is the RSA private key used to sign JWTs. If an attacker obtains this key, they can forge tokens for any user. Encrypting it at rest means a database breach alone is not enough.

---

## Simple example

Without encryption at rest:
```
Database table: signing_keys
+--------+---------------------------+
| kid    | private_key_pem           |
+--------+---------------------------+
| key-1  | MIIEvgIBADANBgkqhki...   |  <-- plain text, usable immediately
+--------+---------------------------+
```

With encryption at rest (AES-256-GCM):
```
Database table: signing_keys
+--------+------------------------------------------+
| kid    | private_key_pem                          |
+--------+------------------------------------------+
| key-1  | v1:BASE64_IV:BASE64_ENCRYPTED_PAYLOAD    |  <-- useless without the key
+--------+------------------------------------------+
```

The encrypted version is useless without the `JWT_KEY_ENCRYPTION_SECRET` environment variable, which lives outside the database.

---

## In volta-auth-proxy

volta encrypts RSA signing keys before storing them in PostgreSQL using `KeyCipher`, which implements AES-256-GCM:

```java
public String encrypt(String plain) {
    byte[] iv = new byte[12];            // random 96-bit IV
    RANDOM.nextBytes(iv);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
    byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
    return "v1:" + b64(iv) + ":" + b64(encrypted);
}
```

Key details:

- **Algorithm**: AES-256-GCM (authenticated encryption -- both confidentiality and integrity).
- **Key derivation**: The `JWT_KEY_ENCRYPTION_SECRET` env var is hashed with SHA-256 to produce the 256-bit AES key.
- **IV (Initialization Vector)**: A fresh random 12-byte IV is generated for every encryption operation, ensuring the same plaintext produces different ciphertext each time.
- **Format**: `v1:BASE64_IV:BASE64_CIPHERTEXT` -- the `v1:` prefix enables future format changes.
- **Backward compatibility**: If a stored value does not start with `v1:`, `decrypt()` returns it as-is (for migration from unencrypted storage).

The encryption secret (`JWT_KEY_ENCRYPTION_SECRET`) is set via an environment variable, not stored in the database. This means an attacker needs both the database dump AND the application environment to decrypt the signing keys.

See also: [hash-function.md](hash-function.md), [key-rotation.md](key-rotation.md)
