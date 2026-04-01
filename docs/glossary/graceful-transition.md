# Graceful Transition

[日本語版はこちら](graceful-transition.ja.md)

---

## What is it?

A graceful transition is a changeover period where both the old and new versions of something (usually cryptographic keys) are accepted simultaneously. Instead of an instant cutover that breaks everything using the old version, the system supports both for an overlap period, then retires the old one once it is no longer needed.

Imagine a building changing its door locks. A hard cutover means changing all locks at midnight -- everyone with old keys is locked out the next morning. A graceful transition means installing new locks that accept both old and new keys for one week, giving everyone time to pick up their new key. After the week, old keys stop working.

In authentication, graceful transitions are critical during [key rotation](key-rotation.md). If you swap signing keys instantly, every JWT signed with the old key becomes unverifiable. A graceful transition keeps both keys active during the overlap.

---

## Why does it matter?

Without graceful transitions, every operational change becomes a potential outage:

- **Key rotation**: Users with valid JWTs signed by the old key get 401 errors.
- **Certificate renewal**: HTTPS connections fail if clients have the old cert cached.
- **Session migration**: Users get logged out during a session storage migration.

The cost of a non-graceful transition scales with the number of active tokens. If 10,000 users have active JWTs when the key changes, 10,000 users hit errors simultaneously. With volta's 5-minute JWT expiry, a graceful transition only needs to last 5 minutes.

---

## How does it work?

### The overlap window

```
  Time ────────────────────────────────────────────────►

  Key A:  ████████████████████████░░░░░░░░░░░░░░░░░░░░
          active                  retired (still valid
                                  for verification)

  Key B:  ░░░░░░░░░░░░░░░░░░░░░░████████████████████████
                                  active (signs new JWTs)

  Overlap:                        ▓▓▓▓▓▓▓▓▓▓▓▓
                                  Both keys accepted.
                                  Key A: verify only
                                  Key B: sign + verify

  After overlap:
  Key A removed from JWKS. All Key A tokens have expired.
```

### kid-based key selection

```
  JWT with kid "key-A":
  ┌──────────────────────────────────┐
  │  Header: { "kid": "key-A" }     │
  │  Payload: { "sub": "alice" }    │
  │  Signature: signed with Key A   │
  └──────────────────────────────────┘
       │
       ▼  Verifier checks kid
  ┌──────────────────────────────────┐
  │  JWKS has both Key A and Key B  │
  │  kid = "key-A" → use Key A      │
  │  Verification: SUCCESS          │
  └──────────────────────────────────┘

  JWT with kid "key-B":
  ┌──────────────────────────────────┐
  │  Header: { "kid": "key-B" }     │
  │  Payload: { "sub": "bob" }      │
  │  Signature: signed with Key B   │
  └──────────────────────────────────┘
       │
       ▼  Verifier checks kid
  ┌──────────────────────────────────┐
  │  JWKS has both Key A and Key B  │
  │  kid = "key-B" → use Key B      │
  │  Verification: SUCCESS          │
  └──────────────────────────────────┘
```

### How long should the overlap last?

The overlap must be at least as long as the longest-lived token signed with the old key:

```
  volta's math:
  ┌──────────────────────────────────────────────┐
  │  JWT TTL:           5 minutes                │
  │  Worst case:        Token issued at T-0,     │
  │                     rotation at T+0.001s     │
  │  Token expires at:  T + 5 min                │
  │                                              │
  │  Minimum overlap:   5 minutes                │
  │  Recommended:       5-10 minutes             │
  │  After overlap:     Safe to remove old key   │
  └──────────────────────────────────────────────┘
```

---

## How does volta-auth-proxy use it?

### Key rotation with graceful transition

When `POST /api/v1/admin/keys/rotate` is called:

```java
public synchronized String rotateKey() {
    RSAKey current = this.rsaKey;
    // Generate new key pair
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    String kid = "key-" + Instant.now()...;

    // Atomic: retire old key + store new key
    store.rotateSigningKey(
        current.getKeyID(),     // old key → retired
        kid,                    // new key → active
        keyCipher.encrypt(...), // new public key (encrypted)
        keyCipher.encrypt(...)  // new private key (encrypted)
    );
    this.rsaKey = next;  // Start signing with new key
    return kid;
}
```

### The transition timeline

```
  T+0:00  Rotation triggered
          ├── New Key B generated
          ├── Old Key A marked "retired" in DB
          ├── New JWTs signed with Key B
          └── JWKS publishes Key B (Key A still in DB for reference)

  T+0:01  Client presents JWT signed with Key A
          ├── volta verifies with Key A (still available)
          └── Verification succeeds

  T+2:30  Client's Key A JWT expires
          ├── Client calls /api/v1/auth/token (silent refresh)
          ├── volta issues new JWT signed with Key B
          └── Client uses Key B JWT going forward

  T+5:00  ALL Key A JWTs have expired
          ├── Key A is no longer needed
          └── Safe to remove Key A from system

  No user experienced any error.
  No downtime. No 401s. No re-login required.
```

### Why volta's design makes graceful transitions easy

1. **Short JWT TTL (5 min)**: The overlap window is naturally short.
2. **kid in JWT header**: Verifiers know which key to use without guessing.
3. **Session continuity**: Sessions are not affected by key rotation. Only JWTs need to transition.
4. **[Silent refresh](silent-refresh.md)**: volta-sdk-js automatically gets a new JWT when the current one expires, seamlessly switching to the new key.

---

## Common mistakes and attacks

### Mistake 1: Instant key cutover

Removing the old key immediately after rotation causes verification failures for all in-flight tokens. Always keep the old key available for at least one JWT TTL period.

### Mistake 2: Not publishing both keys in JWKS

If the JWKS endpoint only publishes the new key, downstream services that use JWKS for verification cannot verify old-key tokens. Both keys should be in JWKS during the overlap.

### Mistake 3: Overlap too short

If the overlap is shorter than the JWT TTL, some valid tokens become unverifiable. volta's 5-minute JWT TTL means the overlap must be at least 5 minutes.

### Mistake 4: Overlap too long

Keeping retired keys around indefinitely increases the attack surface. If a retired key was compromised, tokens signed with it are still accepted. Remove retired keys promptly after the overlap.

### Mistake 5: No monitoring during transition

If something goes wrong during rotation (e.g., JWKS cache not refreshing), you need to know immediately. Monitor 401 error rates during key rotation.

---

## Further reading

- [key-rotation.md](key-rotation.md) -- The operation that triggers graceful transitions
- [signing-key.md](signing-key.md) -- The keys involved in the transition
- [jwks.md](jwks.md) -- How both keys are published during overlap
- [silent-refresh.md](silent-refresh.md) -- How clients seamlessly switch to new keys
- [propagation.md](propagation.md) -- How changes spread through the system
