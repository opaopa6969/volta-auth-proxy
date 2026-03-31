# Key Rotation

[Japanese / 日本語](key-rotation.ja.md)

---

## What is it?

Key rotation is the practice of periodically replacing cryptographic keys with new ones. The old key is retired, and a new key takes its place for all future operations. It is like changing the locks on your house periodically -- even if someone made a copy of the old key without you knowing, it stops working once the lock is changed.

---

## Why does it matter?

Cryptographic keys can be compromised silently. An attacker might steal a key from a log file, a backup, or a memory dump, and you may never know. By rotating keys regularly, you limit the damage:

- **Blast radius**: If a key is compromised, only tokens signed during that key's lifetime are affected.
- **Compliance**: Many security standards (PCI DSS, SOC 2) require periodic key rotation.
- **Cryptanalysis defense**: The longer a key is in use, the more data is signed with it, giving attackers more material to work with.

The challenge is rotating without downtime. If you swap keys instantly, tokens signed with the old key become unverifiable. You need a transition period.

---

## Simple example

```
Time 0:  Key A is active. All JWTs are signed with Key A.
Time 1:  Rotate! Key B is created. Key A is marked "retired."
         - New JWTs are signed with Key B.
         - Old JWTs signed with Key A are still valid (until they expire).
         - JWKS endpoint publishes both Key A and Key B.
Time 2:  All Key A tokens have expired (max 5 minutes in volta).
         Key A can be safely removed from JWKS.
```

The `kid` (Key ID) field in the JWT header tells verifiers which key to use, so both old and new tokens can be verified during the transition.

---

## In volta-auth-proxy

volta implements key rotation in `JwtService.rotateKey()`:

```java
public synchronized String rotateKey() {
    RSAKey current = this.rsaKey;
    // Generate new 2048-bit RSA key pair
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    String kid = "key-" + Instant.now()...;

    // Store new key (encrypted) and retire old key in one transaction
    store.rotateSigningKey(
        current.getKeyID(),   // old kid to retire
        kid,                  // new kid
        keyCipher.encrypt(...), // new public key (encrypted)
        keyCipher.encrypt(...)  // new private key (encrypted)
    );
    this.rsaKey = next;
    return kid;
}
```

How volta's rotation works:

1. **New key generation**: A fresh 2048-bit RSA key pair is generated.
2. **Atomic database swap**: The old key is marked retired and the new key is stored, both encrypted with AES-256-GCM, in a single database operation.
3. **kid-based identification**: Each key has a unique `kid` (e.g., `key-2026-03-31T10-00`). The kid is embedded in every JWT header so verifiers know which key to use.
4. **Short token lifetime**: JWTs expire in 5 minutes (`JWT_TTL_SECONDS=300`), so old-key tokens disappear quickly after rotation.
5. **JWKS endpoint**: The `/.well-known/jwks.json` endpoint publishes the current active key. Downstream services that cache JWKS should refresh periodically.

Rotation can be triggered via the admin API, allowing operators to rotate keys immediately if a compromise is suspected.

See also: [encryption-at-rest.md](encryption-at-rest.md), [public-key-cryptography.md](public-key-cryptography.md)
