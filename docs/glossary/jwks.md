# JWKS / JWK (JSON Web Key Set)

## What is it?

A JWK (JSON Web Key) is a standard way to represent a cryptographic key -- like a public key -- using JSON format. A JWKS (JSON Web Key Set) is simply a collection of these keys, served at a well-known URL so that other applications can fetch them automatically.

Think of it this way: when someone gives you a signed letter, you need to know their signature to verify it's real. JWKS is the "signature catalog" that applications can look up online to check whether a JWT (JSON Web Token) is legitimate.

The standard endpoint for publishing these keys is:

```
https://your-auth-server/.well-known/jwks.json
```

A typical JWKS response looks like this:

```json
{
  "keys": [
    {
      "kty": "RSA",
      "kid": "key-2026-03-15T10-00",
      "alg": "RS256",
      "use": "sig",
      "n": "0vx7agoebGcQ...(public key data)...",
      "e": "AQAB"
    }
  ]
}
```

Each field means:

- **kty** - Key Type. "RSA" means this is an RSA key pair.
- **kid** - Key ID. A label that tells you which specific key was used to sign a token.
- **alg** - Algorithm. "RS256" means RSA signing with SHA-256 hashing.
- **use** - Usage. "sig" means this key is for signatures (not encryption).
- **n** and **e** - The actual mathematical components of the RSA public key.

Notice that only the **public** key is exposed. The private key (used for signing) never leaves the auth server.

## Why does it matter?

Without JWKS, every application that wants to verify a JWT would need to receive the public key through some manual process -- copying files around, sharing secrets in chat, hard-coding keys in config files. That approach breaks the moment you need to change (rotate) your keys.

JWKS solves three major problems:

1. **Automatic key discovery.** Applications fetch the keys from a URL. No manual sharing required.
2. **Key rotation without downtime.** You can add a new key to the JWKS before retiring the old one. During the transition, both keys are in the set, so tokens signed with either key will still verify.
3. **Decoupling.** The auth server and the application servers don't need to share any secrets. The public key is public -- anyone can read it, but only the auth server can sign with the matching private key.

Here is how verification works, step by step:

```
  App receives JWT
        |
        v
  Read the "kid" from the JWT header
        |
        v
  Fetch JWKS from /.well-known/jwks.json
        |
        v
  Find the key with matching "kid"
        |
        v
  Use that public key to verify the signature
        |
        v
  Valid?  --> Trust the claims inside the JWT
  Invalid? --> Reject the request
```

## How does it work?

When an auth server issues a JWT, it signs it using its private key and includes the `kid` (Key ID) in the JWT header. Later, when an application receives that JWT, it:

1. Parses the JWT header to find the `kid`.
2. Fetches the JWKS from the auth server's well-known URL.
3. Searches the `keys` array for a JWK whose `kid` matches.
4. Uses that public key to mathematically verify the signature.

Applications typically cache the JWKS response so they don't need to fetch it for every single request. A common pattern is to cache the keys and only re-fetch when a JWT arrives with an unknown `kid` (which might mean a key rotation just happened).

### Key Rotation

Key rotation is the practice of periodically replacing your signing keys. It limits the damage if a private key is ever compromised. Here is the timeline of a typical rotation:

```
Time ----->

  Phase 1: Only Key A exists
  JWKS: [Key A]
  All tokens signed with Key A

  Phase 2: Key B is created. Both keys in JWKS.
  JWKS: [Key A, Key B]
  New tokens signed with Key B
  Old tokens signed with Key A still verify

  Phase 3: Key A is retired
  JWKS: [Key B]
  Old Key A tokens have expired by now
  All new tokens signed with Key B
```

The overlap period (Phase 2) is important. It ensures that tokens signed with the old key can still be verified while they haven't expired yet.

## How does volta-auth-proxy use it?

volta-auth-proxy acts as its own JWT issuer. It generates RSA 2048-bit key pairs and serves a JWKS endpoint at:

```
GET /.well-known/jwks.json
```

Here is what happens under the hood:

1. **Key creation.** On first startup, volta generates an RSA key pair and stores both the public and private key in the database. The private key is encrypted at rest using AES before being saved.
2. **Key ID format.** volta creates key IDs based on timestamps, like `key-2026-03-15T10-00`. This makes it easy to tell when a key was created.
3. **JWKS endpoint.** When a downstream application (or anyone) requests `/.well-known/jwks.json`, volta returns only the public portion of the current key. The response includes caching headers (`Cache-Control: public, max-age=60`) so that clients don't hammer the endpoint.
4. **Key rotation.** volta supports key rotation via an admin API. When rotation is triggered, a new key pair is generated, the old key is marked as retired in the database, and the new key becomes the active signer.
5. **Token verification.** When volta itself receives a JWT (for example, in a Bearer token on an API request), it verifies the signature using the current RSA public key.

The flow looks like this:

```
  volta-auth-proxy                    Downstream App (e.g., wiki)
  +-----------------+                 +-------------------+
  |                 |  1. Fetch JWKS  |                   |
  | RSA Private Key |<----------------|  Receives JWT     |
  | RSA Public Key  |----JWKS JSON--->|  from user        |
  |                 |                 |                   |
  | Signs JWTs      |                 |  Verifies JWT     |
  | with private key|                 |  with public key  |
  +-----------------+                 +-------------------+
```

Downstream applications like `app-wiki` or `app-admin` (defined in `volta-config.yaml`) can verify volta-issued JWTs by fetching the public key from the JWKS endpoint. They never need to know the private key.

## Common mistakes

**1. Exposing the private key in the JWKS.**
The JWKS endpoint must only return public keys. If you accidentally include the private key, anyone can forge valid tokens. volta avoids this by explicitly building a public-key-only JWK when serving the endpoint.

**2. Not caching the JWKS response.**
Fetching the JWKS for every single request adds latency and can overwhelm the auth server. Applications should cache the response and only re-fetch when they encounter a `kid` they don't recognize.

**3. Skipping `kid` validation.**
If an application doesn't check the `kid` from the JWT header against the JWKS, it might use the wrong key or blindly trust a key the attacker supplies. Always match the `kid` before verifying.

**4. Forgetting key rotation.**
Using the same signing key forever means that if it's ever compromised, every token ever issued is compromised. Rotate keys periodically and keep the rotation overlap window long enough for existing tokens to expire.

**5. Accepting the `alg` header from the JWT without restriction.**
An attacker might send a JWT with `"alg": "none"` (no signature) or `"alg": "HS256"` (symmetric, using the public key as the secret). volta only accepts RS256 and rejects everything else.

**6. Hard-coding public keys instead of fetching JWKS.**
This works until you rotate keys, at which point everything breaks. Use the JWKS endpoint so rotation is seamless.
