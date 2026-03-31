# Replay Attack

[Japanese / 日本語](replay-attack.ja.md)

---

## What is it?

A replay attack happens when an attacker captures a valid message (like a token, a request, or an authentication response) and sends it again later to impersonate the original sender. The attacker does not need to understand or decrypt the message -- they just record it and play it back, like recording someone saying "open the door" and playing it to a voice-activated lock.

---

## Why does it matter?

If a system accepts any valid token without checking whether it has been used before or whether it is still timely, an attacker who intercepts a single authentication exchange can replay it indefinitely. They do not need to steal the user's password or compromise the server -- one captured network request is enough.

Replay attacks are especially dangerous with authentication tokens because a replayed token grants the same access as the original. The two primary defenses are **nonces** (single-use random values) and **expiration times** (tokens that stop working after a short window).

---

## Simple example

Without replay protection:
```
1. Alice authenticates and receives id_token (valid for 1 hour)
2. Attacker intercepts the id_token over the network
3. 30 minutes later, attacker presents the same id_token
4. Server accepts it -- attacker is now "Alice"
```

With nonce + short expiry:
```
1. Alice authenticates. Server stores nonce="abc123"
2. Google returns id_token containing nonce="abc123"
3. Server checks: nonce matches stored value? Yes. Consume it (mark as used).
4. Attacker captures and replays the id_token
5. Server checks: nonce="abc123" already consumed. Reject.
```

---

## In volta-auth-proxy

volta defends against replay attacks at multiple levels:

**Nonce in OIDC flow**:

When starting the login flow, volta generates a random nonce and stores it in the database:

```java
String nonce = SecurityUtils.randomUrlSafe(32);
store.saveOidcFlow(new OidcFlowRecord(state, nonce, verifier, ...));
```

When Google returns the `id_token`, volta verifies the nonce matches:

```java
String nonce = (String) claims.getClaim("nonce");
if (nonce == null || !nonce.equals(expectedNonce)) {
    throw new IllegalArgumentException("Invalid nonce");
}
```

The OIDC flow is consumed (deleted from the database) immediately upon use, so the same `state`/`nonce` pair cannot be replayed.

**Short JWT expiry**:

volta's JWTs expire in 5 minutes (`JWT_TTL_SECONDS=300`). Even if a JWT is intercepted, the window for replaying it is extremely narrow.

**Single-use state parameter**:

The `state` parameter is consumed from the database on first use (`store.consumeOidcFlow(state)`). A second callback with the same state fails.

**Flow expiration**:

OIDC flows expire after 10 minutes. Even if a state/nonce pair is somehow not consumed, it becomes invalid after a short window.

See also: [brute-force.md](brute-force.md), [token-theft.md](token-theft.md)
