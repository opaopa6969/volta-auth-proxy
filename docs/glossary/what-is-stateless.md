# What Is Stateless, Actually?

[日本語版はこちら](what-is-stateless.ja.md)

---

## The misconception

"JWT is stateless."

You hear this everywhere. Blog posts, conference talks, Stack Overflow answers. The claim is that JWTs do not require server-side storage, and therefore your authentication system is stateless. Simple. Clean. Scalable.

Except it is not true. Or rather, it is true in the same way that "I don't need a map, I memorized the route" is true -- it works until you need to take a detour.

---

## What stateless actually means

A stateless system is one where the server does not need to remember anything about previous interactions to process the current request. Each request contains all the information needed to handle it. The server has no memory.

HTTP itself is stateless. Each request is independent. The server does not know that the person making this GET request is the same person who made a POST request five seconds ago.

In theory, JWT-based authentication is stateless: the token contains all the claims (user ID, roles, tenant), and the server just needs the public key to verify the signature. No database lookup. No session table. Pure, beautiful statelessness.

In practice, this falls apart almost immediately.

---

## Where the stateless myth breaks down

### Problem 1: Revocation

A user's account is compromised. You need to invalidate their token immediately. But the token lives on the client. You did not store it on the server. You have no way to say "this specific token is no longer valid" without... maintaining a list of revoked tokens on the server.

Congratulations. You just added state to your stateless system.

The usual workaround is "make tokens short-lived." If the token expires in 5 minutes, a compromised token is only valid for 5 minutes. That is what volta does. But during those 5 minutes, the token is irrevocable. That is the tradeoff. Anyone who tells you JWTs are stateless AND instantly revocable is either confused or selling you middleware.

### Problem 2: JWKS caching

To verify a JWT signature, you need the public key. The public key comes from the JWKS (JSON Web Key Set) endpoint. Do you fetch it on every request? That would be absurd -- it would negate the entire performance benefit of JWTs. So you cache it.

But cached JWKS is state. It is state that can become stale (after key rotation). It is state that needs invalidation logic. It is state that can cause failures if it is wrong.

### Problem 3: Rate limiting

You want to rate-limit API requests per user. Where do you store the request count? In memory? That is state. In Redis? That is distributed state. In the JWT? You cannot -- the client controls the token.

### Problem 4: The session you pretend does not exist

Many "stateless JWT" systems actually have a session. They just call it something else. A refresh token stored in a database. A token blacklist. A "last active" timestamp. Each of these is session state wearing a different hat.

---

## volta's honest approach

volta does not pretend to be stateless. It uses a hybrid model and is transparent about it:

**Sessions are stateful. Deliberately.**

When a user logs in, volta creates a server-side session in PostgreSQL. The session tracks:
- Who the user is
- Which tenant they are in
- When the session was created
- When it was last active
- Which device/browser it belongs to

This session is real state. volta stores it, manages it, and can revoke it instantly. When an admin suspends a tenant or a user revokes all sessions, it happens immediately at the session layer. No waiting for token expiry.

**JWTs are stateless-ish. Deliberately.**

volta issues short-lived JWTs (5-minute expiry) that downstream apps verify without calling volta. These JWTs are stateless in the useful sense: downstream apps do not need a database connection to volta to verify identity. But they are not purely stateless because:

- They were issued based on a session (which is state)
- They can become stale within their 5-minute lifetime (if a role changes or a session is revoked)
- The JWKS keys used to verify them are cached (which is state)

volta is honest about this 5-minute window. It is documented. It is a known tradeoff. A role change or session revocation takes up to 5 minutes to propagate to downstream apps. volta-sdk-js handles this by silently refreshing JWTs, and at refresh time, the session is re-verified.

**Why this hybrid works**

The session layer gives volta:
- Instant revocation
- Concurrent session limits (max 5 per user)
- Sliding window expiry (8 hours of inactivity = logout)
- Device-level visibility and control

The JWT layer gives downstream apps:
- No coupling to volta's database
- No per-request callback to volta
- Fast, local verification
- Portable identity information (user ID, tenant, roles)

Each layer does what it is good at. Neither pretends to do what it cannot.

---

## The architecture of honesty

The real problem with the "stateless JWT" narrative is not technical. It is cultural. It teaches engineers to pursue an ideal (zero state) that is impossible in practice, and then to hide the state they inevitably create behind abstractions and naming tricks.

volta takes a different approach: name the state. Put it in a table. Give it a lifecycle. Make it visible. A session table you can query, inspect, and revoke is better than state scattered across Redis keys, cookie values, and refresh token tables that nobody admits are sessions.

---

## It is OK not to know this

If you thought JWTs made your system stateless, you were repeating what the industry told you. The industry was wrong, or at least, dramatically oversimplifying. Pure statelessness in authentication is like a frictionless surface in physics: useful for understanding the concept, but not something you will encounter in the real world.

The engineers who build reliable systems are not the ones chasing statelessness. They are the ones who know exactly where their state lives, why it is there, and what happens when it goes wrong.

Now you know.
