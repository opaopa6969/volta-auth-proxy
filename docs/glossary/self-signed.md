# Self-Signed

[日本語版はこちら](self-signed.ja.md)

---

## What is it?

A self-signed certificate or key is one that is signed by its own creator, rather than by a trusted third-party authority (like a Certificate Authority). The creator says "I vouch for myself." There is no independent party confirming the identity.

Imagine you are at a party and someone hands you a business card that says "CEO of MegaCorp." Did MegaCorp actually print that card? Or did the person print it at home? With a self-signed certificate, it is like printing your own business card -- the information might be accurate, but there is no external verification. A certificate from a Certificate Authority (CA) is like a card printed by MegaCorp's official print shop with a holographic seal.

Self-signed does not mean insecure. It means trust must be established through a different channel. If you generated the key yourself and you control both sides of the communication, self-signed is perfectly fine. volta generates its own RSA key pair on first boot -- this is self-signed and secure because volta is both the signer and the verifier.

---

## Why does it matter?

The distinction between self-signed and CA-signed matters for trust:

- **CA-signed**: Any client that trusts the CA automatically trusts the certificate. Used for public-facing HTTPS.
- **Self-signed**: Only clients that have been explicitly configured to trust this specific certificate will accept it. Used for internal services, development, and systems like volta where the same system signs and verifies.

If you use a self-signed certificate where a CA-signed one is expected, browsers show scary warnings and API clients reject the connection. But for internal JWT signing, self-signed is the standard approach -- there is no reason to involve a CA because the signing key never leaves the system.

---

## How does it work?

### CA-signed vs. self-signed

```text
CA-signed certificate:

   "I am api.example.com"

   Signed by: Let's Encrypt (CA)
   CA signed by: ISRG Root X1
   Root trusted by: Every browser on Earth

   Chain of trust:
   Browser → Root CA → Intermediate CA → Cert

Self-signed certificate/key:

   "I am volta-auth-proxy"

   Signed by: volta-auth-proxy (itself)
   Trusted by: volta-auth-proxy (itself)

   No chain of trust needed:
   volta signs JWTs → volta verifies JWTs
   (same system, same key pair)
```

### When self-signed is appropriate

| Scenario | Self-signed OK? | Why |
|----------|----------------|-----|
| Public HTTPS website | No | Browsers need CA chain of trust |
| Internal JWT signing | Yes | Same system signs and verifies |
| Development/testing | Yes | No real users, just developers |
| Service-to-service mTLS | Maybe | OK if you manage trust manually |
| volta's RSA key pair | Yes | volta controls both sides |

### Trust models

```text
External trust (CA-signed):

  Root CA     → Int CA     →   Cert       →  Client

Pre-installed   Signed by    Signed by      Trusts Root
in browser      Root CA      Int CA         → trusts Cert

Self trust (self-signed):

   volta-auth-proxy

   Private key   sign  → JWT
   Public key    verify → JWT

   No external trust chain needed.
   Public key shared via JWKS endpoint.
```

---

## How does volta-auth-proxy use it?

volta's RSA key pair is entirely self-signed. On first boot, `JwtService.loadOrCreateKey()` checks the `signing_keys` table. If no key exists, it generates a fresh 2048-bit RSA key pair:

```java
private RSAKey createKey() {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    String kid = "key-" + Instant.now()...;
    // Store encrypted in database
    store.saveSigningKey(kid,
        keyCipher.encrypt(base64(publicKey)),
        keyCipher.encrypt(base64(privateKey)));
    return rsaKey;
}
```

This is self-signed because:

1. **volta generates the key** -- no CA is involved.
2. **volta signs JWTs with the private key** -- using `RSASSASigner`.
3. **volta (and downstream services) verify JWTs with the public key** -- published at `/.well-known/jwks.json`.
4. **Trust is established by configuration** -- downstream apps are configured to trust volta's JWKS endpoint, not a CA.

### Why not use a CA for JWT signing?

- JWTs are not TLS certificates. They do not need browser trust.
- Adding a CA would add complexity ([config hell](config-hell.md)) with no security benefit.
- The [signing key](signing-key.md) never leaves volta. There is no "man in the middle" to protect against at the signing level.
- volta's [auto-key-generation](auto-key-generation.md) means zero manual certificate management.

---

## Common mistakes and attacks

### Mistake 1: Using self-signed for public HTTPS

Browsers will show "Your connection is not private" warnings. Users will leave. Use Let's Encrypt or another CA for public-facing TLS.

### Mistake 2: Disabling certificate verification in production

Developers often set `verify_ssl=False` or `InsecureSkipVerify` during development with self-signed certs, then forget to re-enable it. This defeats the purpose of TLS entirely.

### Mistake 3: Confusing self-signed with insecure

Self-signed keys for JWT signing are industry standard. The security comes from keeping the private key secret, not from a CA blessing. volta's approach is identical to what Auth0, Firebase, and other identity providers use.

### Mistake 4: Not rotating self-signed keys

Because there is no CA expiry forcing rotation, self-signed keys can linger forever. Always implement [key rotation](key-rotation.md). volta supports rotation via `POST /api/v1/admin/keys/rotate`.

---

## Further reading

- [auto-key-generation.md](auto-key-generation.md) -- How volta creates keys on first boot
- [signing-key.md](signing-key.md) -- The private key volta generates
- [public-key-cryptography.md](public-key-cryptography.md) -- How key pairs work
- [key-rotation.md](key-rotation.md) -- Replacing self-signed keys periodically
- [jwks.md](jwks.md) -- How volta publishes its self-signed public key
