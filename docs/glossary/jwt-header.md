# JWT Header

[日本語版はこちら](jwt-header.ja.md)

---

## What is it?

A JWT (JSON Web Token) has three parts separated by dots: `header.payload.signature`. The **header** is the first part. It's a small JSON object that tells the receiver two essential things: *how* the token was signed and *what type* of token it is.

Think of it as the label on an envelope. Before you open the letter (payload), you look at the label to know what kind of document is inside and what seal was used to close it.

---

## Why does it matter?

Without the header, the receiver wouldn't know how to verify the token. There are many signing algorithms (RS256, HS256, ES256, etc.), and each requires a different verification process. The header says "I was signed with RS256 using key `key-2025-01-01`" so the verifier knows exactly what to do.

The `kid` (Key ID) field is especially important during **key rotation**. When the signing key changes, old tokens (signed with the old key) and new tokens (signed with the new key) might coexist. The `kid` tells the verifier which key to use.

---

## A simple example

A decoded JWT header:

```json
{
  "alg": "RS256",
  "kid": "key-2025-03-31T09-00",
  "typ": "JWT"
}
```

| Field | Meaning | Example |
|-------|---------|---------|
| `alg` | Signing algorithm | `RS256` (RSA + SHA-256) |
| `kid` | Which key signed this | `key-2025-03-31T09-00` |
| `typ` | Token type | `JWT` |

The `typ: "JWT"` field might seem redundant, but it helps in systems that handle multiple token formats (e.g., JWTs and SAML tokens). It says "this is a JWT, not something else."

---

## In volta-auth-proxy

volta builds the JWT header in `JwtService.java`:

```java
new JWSHeader.Builder(JWSAlgorithm.RS256)
    .keyID(rsaKey.getKeyID())
    .type(JOSEObjectType.JWT)
    .build()
```

Key points:
- **Algorithm is always RS256.** volta rejects any token with a different algorithm during verification: `if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()))` -- this prevents the classic "alg: none" attack where an attacker sets the algorithm to "none" to bypass signature checks.
- **kid format:** volta uses `key-` followed by a timestamp, like `key-2025-03-31T09-00`. This makes it easy to see when a key was created.
- **typ is always "JWT."** Standard practice for clarity.

---

## See also

- [jwt-payload.md](jwt-payload.md) -- The second part: the claims
- [jwt-signature.md](jwt-signature.md) -- The third part: the cryptographic seal
