# Hash Function (SHA-256)

[Japanese / 日本語](hash-function.ja.md)

---

## What is it?

A hash function takes any input -- a password, a file, a single character, a whole book -- and produces a fixed-size output called a "hash" or "digest." SHA-256, one of the most widely used hash functions, always produces a 256-bit (64 hex character) output. The critical properties are: it is **one-way** (you cannot reverse it to get the original input), and it is **collision-resistant** (it is practically impossible to find two different inputs that produce the same hash).

---

## Why does it matter?

Hash functions are the Swiss Army knife of cryptography. They appear everywhere:

- **Password storage**: Store the hash, not the password. If the database leaks, attackers get hashes, not passwords.
- **Data integrity**: Hash a file before and after transfer. If the hashes match, the file was not corrupted.
- **Digital signatures**: The signer hashes the data first, then signs the (small) hash instead of the (large) data.
- **PKCE**: The code challenge is a SHA-256 hash of the code verifier, so the verifier can be checked without being exposed.
- **HMAC**: Combines a hash function with a secret key to create a message authentication code.

Think of a hash as a fingerprint. Every person has a unique fingerprint. You can identify someone by their fingerprint, but you cannot reconstruct a person from their fingerprint.

---

## Simple example

```
Input:  "hello"
SHA-256: 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824

Input:  "Hello"  (just one letter capitalized)
SHA-256: 185f8db32271fe25f561a6fc938b2e264306ec304eda518007d1764826381969
```

Notice: a tiny change in input produces a completely different hash. This is called the "avalanche effect."

Also: both outputs are exactly 64 hex characters, regardless of input length.

---

## In volta-auth-proxy

volta uses SHA-256 in several places:

**PKCE code challenge** (`SecurityUtils.pkceChallenge()`):

```java
MessageDigest digest = MessageDigest.getInstance("SHA-256");
byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
```

The OIDC flow generates a random `code_verifier`, hashes it with SHA-256 to create the `code_challenge`, and sends only the challenge to Google. Later, volta sends the original verifier to prove it started the flow.

**Key encryption key derivation** (`KeyCipher`):

```java
MessageDigest sha = MessageDigest.getInstance("SHA-256");
byte[] key = sha.digest(secret.getBytes(StandardCharsets.UTF_8));
this.keySpec = new SecretKeySpec(key, "AES");
```

The `JWT_KEY_ENCRYPTION_SECRET` environment variable is hashed with SHA-256 to derive a 256-bit AES key. This ensures the encryption key is always exactly the right size, regardless of the secret's length.

**General-purpose hashing** (`SecurityUtils.sha256Hex()`): Used for hashing tokens and other values where a one-way fingerprint is needed.

**HMAC-SHA256** (`SecurityUtils.hmacSha256Hex()`): Used for webhook signature verification, combining SHA-256 with a secret key.

See also: [public-key-cryptography.md](public-key-cryptography.md), [encryption-at-rest.md](encryption-at-rest.md)
