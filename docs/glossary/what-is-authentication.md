# What Is Authentication, Actually?

[日本語版はこちら](what-is-authentication.ja.md)

---

## The misconception

"I know what auth is. It's login."

No. Login is one small part of authentication. It is the part you can see. But authentication is an iceberg, and login is the tip poking above the water. Below the surface is a sprawling system of identity verification, credential storage, session management, token issuance, trust establishment, and revocation. Most engineers see about 20% of it.

And here is the thing that makes it worse: because everyone has logged into a website, everyone assumes they understand authentication. It is the Dunning-Kruger effect applied to distributed systems security.

---

## What authentication actually is

Authentication is the act of proving that an entity is who they claim to be. That is it. Not "letting them in." Not "giving them permissions." Just: proving identity.

But "proving identity" turns out to be an absurdly complex problem once you think about it for more than five seconds.

### The layers most people do not see

**Layer 1: Credential exchange.** The user provides something (password, OAuth token, biometric) that proves they are who they claim to be. This is the part people think of as "auth."

**Layer 2: Identity provider trust.** When volta uses Google OIDC, it is not verifying the user's identity directly. It is trusting Google to verify the identity and then verifying that the message from Google is authentic. This is a chain of trust. volta verifies the OIDC ID token signature, checks the `iss` (issuer) claim, validates the `nonce` (to prevent replay attacks), and confirms the `aud` (audience) matches. If any of these fail, the identity is rejected -- even if the user "logged in successfully" from Google's perspective.

**Layer 3: Session establishment.** OK, the user proved their identity once. Now what? Do they prove it again on every request? That would be unusable. So we create a session -- a server-side record that says "this browser, with this cookie, belongs to this verified user." The session is not the authentication. It is a memory of the authentication that happened earlier.

**Layer 4: Token issuance.** volta issues a JWT after session verification. The JWT is a portable proof of authentication that downstream apps can verify without calling back to volta. But the JWT is not the authentication either. It is a signed statement that says "volta verified this user's identity at this time."

**Layer 5: Ongoing verification.** Sessions expire. Tokens expire. Keys rotate. Users get deactivated. Tenants get suspended. Authentication is not a one-time event. It is a continuous state that needs continuous maintenance. volta's 8-hour sliding window, 5-minute JWT expiry, and session-per-device limits are all part of this layer.

**Layer 6: Revocation.** The ability to un-authenticate someone. "This user's session is no longer valid." "All sessions for this user are terminated." This is where the myth of stateless authentication breaks down -- you cannot revoke something you do not track.

---

## The things authentication is NOT

**Authentication is not authorization.** Authentication says "you are Taro Yamada." Authorization says "Taro Yamada can edit projects but cannot delete users." volta handles both, but they are separate systems. ForwardAuth checks authentication (valid session?) and authorization (correct role for this app?) as separate steps.

**Authentication is not session management.** A session is a convenience that avoids re-authentication on every request. You can have authentication without sessions (verify credentials every time) and you can have sessions without proper authentication (session fixation attacks, where an attacker creates a session and tricks the user into using it). volta regenerates session IDs on every login specifically to prevent this.

**Authentication is not credential storage.** volta does not store passwords. It delegates credential verification to Google via OIDC. This is a deliberate design decision: let the identity provider handle the hardest part (password hashing, brute force protection, compromised credential detection) and focus on everything else.

**Authentication is not encryption.** "We encrypt everything" is not an authentication strategy. Encryption protects data in transit and at rest. Authentication verifies identity. They are complementary but separate concerns. volta uses encryption (AES-256-GCM for key storage, RS256 for JWT signing), but those are tools in service of authentication, not authentication itself.

---

## What volta actually does

Here is the full authentication flow, all six layers:

1. **Credential exchange:** User clicks "Sign in with Google." volta redirects to Google's OIDC endpoint with PKCE challenge, state parameter, and nonce.
2. **Identity provider trust:** Google authenticates the user and redirects back with an authorization code. volta exchanges the code for tokens, verifies the ID token signature against Google's JWKS, validates iss/aud/nonce/exp claims.
3. **Session establishment:** volta creates a server-side session, generates a signed session cookie, and stores the session in the database. Session ID is fresh (not reused from any previous session).
4. **Token issuance:** volta issues a short-lived JWT (5 min) containing the user's ID, tenant ID, roles, and display name. Downstream apps verify this JWT using volta's JWKS endpoint.
5. **Ongoing verification:** ForwardAuth checks session validity on every request. JWT is refreshed silently via volta-sdk-js when it expires. Sessions slide forward with activity but hard-expire after the maximum lifetime.
6. **Revocation:** Users can revoke individual sessions or all sessions. Admins can change roles (effective on next JWT refresh). Tenant suspension blocks all member access immediately at the session layer.

That is authentication. Not "login."

---

## It is OK not to know this

If you thought authentication was just the login screen, welcome to the club. Most engineers start there. The problem is not ignorance -- it is that the industry talks about "auth" as if it is one thing, when it is actually six things wearing a trenchcoat.

Now you have seen what is under the trenchcoat. It is not as scary as it looks. Each layer has a clear purpose, and once you understand them separately, the whole system makes sense.

Now you know.
