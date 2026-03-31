# How to Decode a JWT

[日本語版はこちら](jwt-decode-howto.ja.md)

---

## What is it?

Decoding a JWT means reading its contents (header and payload). This is different from *verifying* a JWT (checking the signature). Anyone can decode a JWT because it's just base64url-encoded -- **a JWT is NOT encrypted.** It's signed, which proves it hasn't been tampered with, but the contents are readable by anyone.

Think of a JWT like a postcard with a wax seal. The wax seal (signature) proves the sender is authentic, but anyone who handles the postcard can read the message.

---

## Why does it matter?

Being able to decode JWTs is an essential debugging skill. When something goes wrong ("why is this user getting 403?"), the first thing you should do is decode their JWT and check the claims. Is the token expired? Is the tenant ID correct? Are the roles what you expect?

It also matters for security awareness: since JWTs are readable, you should never put secrets in them. If you see a JWT in a log or URL, know that its contents are visible to everyone.

---

## A simple example

### Method 1: jwt.io (web)

Go to [https://jwt.io](https://jwt.io), paste the JWT, and see the decoded header and payload instantly. The site also verifies the signature if you provide the public key.

**Warning:** Don't paste production tokens into third-party websites. jwt.io runs locally in the browser, but be cautious with sensitive tokens.

### Method 2: Command line (bash)

A JWT has three parts separated by dots. Decode the first two:

```bash
# Given a JWT
TOKEN="eyJhbGciOiJSUzI1NiIsImtpZCI6ImtleS0yMDI1IiwidHlwIjoiSldUIn0.eyJpc3MiOiJ2b2x0YS1hdXRoIiwic3ViIjoiMTIzIn0.signature_here"

# Decode header (part 1)
echo "$TOKEN" | cut -d. -f1 | base64 -d 2>/dev/null
# {"alg":"RS256","kid":"key-2025","typ":"JWT"}

# Decode payload (part 2)
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null
# {"iss":"volta-auth","sub":"123"}
```

Note: base64url uses `-` and `_` instead of `+` and `/`, and omits padding `=`. Most `base64 -d` commands handle this, but if you get errors, add padding:

```bash
echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+' | base64 -d
```

### Method 3: jq for pretty output

```bash
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq .
```

```json
{
  "iss": "volta-auth",
  "aud": ["volta-apps"],
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "volta_tid": "660e8400-e29b-41d4-a716-446655440000",
  "volta_roles": ["ADMIN"],
  "exp": 1711875900
}
```

### Method 4: Node.js one-liner

```bash
node -e "console.log(JSON.parse(Buffer.from('$TOKEN'.split('.')[1],'base64url')))"
```

---

## In volta-auth-proxy

volta issues JWTs with a 5-minute TTL. When debugging authentication issues, decoding the JWT is usually the fastest path to understanding the problem:

1. **Check `exp`:** Is the token expired? Compare the Unix timestamp to current time.
2. **Check `volta_tid`:** Is the user in the right tenant?
3. **Check `volta_roles`:** Does the user have the required role?
4. **Check `iss` and `aud`:** Are they `volta-auth` and `volta-apps`?

You can also hit volta's JWKS endpoint to get the public key for full verification:

```bash
curl http://localhost:7070/.well-known/jwks.json | jq .
```

Remember: decoding shows you what the token *claims*. Only signature verification proves those claims are *true*.

---

## See also

- [jwt-payload.md](jwt-payload.md) -- What the decoded claims mean
- [jwt-signature.md](jwt-signature.md) -- How to verify (not just decode) a JWT
