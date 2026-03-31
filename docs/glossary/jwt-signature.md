# JWT Signature

[日本語版はこちら](jwt-signature.ja.md)

---

## What is it?

The signature is the third and final part of a JWT (`header.payload.signature`). It's the cryptographic proof that the token hasn't been tampered with. Think of it as a wax seal on a letter -- if someone opens the letter and changes the contents, the seal breaks.

The signature is created by taking the header and payload, joining them with a dot, and signing that string with a private key. Anyone with the corresponding public key can verify the signature is valid.

---

## Why does it matter?

Without the signature, JWTs would be useless for security. Anyone could craft a JWT that says `"roles": ["ADMIN"]` and gain admin access. The signature ensures that only the holder of the private key (volta-auth-proxy) can create valid tokens. If even one character of the header or payload is changed, the signature becomes invalid.

---

## A simple example

### How it's created (signing)

```
Input:   base64url(header) + "." + base64url(payload)
         = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjMifQ"

Sign with private key (RS256):
         RSA_SIGN(SHA256(input), private_key)

Output:  "kB4rD9..." (the signature, also base64url-encoded)

Final JWT: header.payload.signature
           "eyJhbGci...eyJzdWIi...kB4rD9..."
```

### How it's verified

```
1. Split the JWT at the dots -> header, payload, signature
2. Read "alg" from the header -> RS256
3. Read "kid" from the header -> look up the public key
4. Recompute: SHA256(header + "." + payload)
5. Use public key to verify the signature matches
6. If yes -> token is authentic. If no -> reject.
```

### What happens if tampered?

```
Original payload:  {"sub":"123","volta_roles":["MEMBER"]}
Attacker changes:  {"sub":"123","volta_roles":["OWNER"]}

The signature was computed over the ORIGINAL payload.
The modified payload produces a different SHA256 hash.
Verification fails -> token rejected.
```

---

## In volta-auth-proxy

volta uses RS256 (RSA + SHA-256) for all JWT signatures:

**Signing** (in `JwtService.issueToken()`):
```java
SignedJWT jwt = new SignedJWT(header, claims);
jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
```

**Verification** (in `JwtService.verify()`):
```java
JWSVerifier verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey());
if (!jwt.verify(verifier)) {
    throw new IllegalArgumentException("Invalid JWT signature");
}
```

volta also enforces that the algorithm is RS256 before even attempting verification. This prevents the "algorithm confusion" attack where an attacker might try to trick the server into using a weaker algorithm.

The public key is available at `/.well-known/jwks.json` so that downstream services can verify volta-issued tokens independently, without calling back to volta.

---

## See also

- [jwt-header.md](jwt-header.md) -- Where the algorithm and key ID are specified
- [jwt-payload.md](jwt-payload.md) -- The data that the signature protects
