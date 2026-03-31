# Nonce (Number Used Once)

## What is it?

A nonce is a random value that can only be used one time. The word comes from "number used once." In the context of OIDC (OpenID Connect), a nonce is a random string that your application generates, sends to the identity provider during login, and later finds embedded inside the id_token that comes back. By checking that the nonce in the token matches the one your app originally sent, you can prove that this specific token was issued for this specific login attempt.

Think of it like a serial number on a movie ticket. The theater prints a unique serial number on your ticket. When you enter, they check the serial number and tear the ticket. If someone photocopied your ticket, the serial number has already been used, so the copy is rejected.

## Why does it matter?

The nonce prevents **replay attacks** on the id_token. A replay attack is when someone captures a valid token and tries to use it again later (or in a different context) to gain unauthorized access.

Here is the attack scenario without a nonce:

```
  1. Alice logs in through OIDC
  2. Google issues an id_token for Alice
  3. An attacker intercepts this id_token (e.g., from browser history,
     a log file, or network sniffing)
  4. Later, the attacker sends this same id_token to your app
  5. Your app validates the token -- signature is good, not expired,
     issuer matches, audience matches
  6. Your app creates a session for Alice
  7. The attacker is now logged in as Alice
```

With a nonce, step 5 fails:

```
  Your app checks: Does the nonce inside the id_token match
  the nonce I stored for this login attempt?

  The attacker's request has no corresponding nonce stored on the server
  (because the attacker didn't initiate a login flow).

  Result: Rejected.
```

### How is nonce different from state?

This is a common point of confusion. Both are random values in the OIDC flow, but they protect different things at different levels:

| | State | Nonce |
|---|---|---|
| **Protects** | The redirect flow (HTTP level) | The id_token (token level) |
| **Prevents** | CSRF / login CSRF attacks | Token replay attacks |
| **Travels in** | URL query parameter | Inside the id_token (as a claim) |
| **Verified by** | Your app, before token exchange | Your app, after token exchange |
| **Stored by IdP** | Passed through unchanged | Embedded inside the signed token |

Think of it this way:
- **State** makes sure the redirect came from a login flow your app started.
- **Nonce** makes sure the id_token was issued specifically for that login flow.

You need both because they protect against different attacks that can happen at different points in the flow.

## How does it work?

```
  Your App                  Google                    Your App
  (start login)             (identity provider)       (callback)
  +----------------+       +------------------+      +----------------+
  |                |       |                  |      |                |
  | 1. Generate    |       |                  |      | 5. Parse       |
  |    nonce=XYZ   |       |                  |      |    id_token    |
  |                |       |                  |      |                |
  | 2. Save nonce  |       |                  |      | 6. Extract     |
  |    to DB       |       |                  |      |    nonce from  |
  |                |       |                  |      |    token claims|
  | 3. Redirect to | ----> | 4. User logs in. |      |                |
  |    Google with |       |    Google puts   | ---> | 7. Compare:    |
  |    nonce=XYZ   |       |    nonce=XYZ     |      |    token nonce |
  |    in auth URL |       |    inside the    |      |    == saved    |
  |                |       |    id_token      |      |    nonce?      |
  +----------------+       +------------------+      +----------------+
                                                           |
                                                      Match? -> OK
                                                      No match? -> REJECT
```

Step by step:

1. Your app generates a cryptographically random string (the nonce).
2. Your app saves the nonce server-side, associated with this login attempt.
3. Your app includes `nonce=XYZ` as a parameter in the authorization URL sent to Google.
4. Google authenticates the user and creates an id_token. Google embeds the nonce value into the id_token as a claim before signing it. Because the nonce is inside the signed token, nobody can change it without invalidating the signature.
5. Your app receives the id_token in the callback.
6. Your app parses the id_token and reads the `nonce` claim from inside it.
7. Your app compares this nonce to the one it saved in step 2. If they match, the token is fresh and was issued for this specific login attempt.

## How does volta-auth-proxy use it?

volta-auth-proxy generates and validates nonces as part of its Google OIDC integration.

**Generation.** In `OidcService.createAuthorizationUrl()`, volta generates a nonce using `SecurityUtils.randomUrlSafe(32)` -- the same cryptographically strong random generator used for the state parameter. This produces a 32-byte (256-bit) random value encoded as a URL-safe Base64 string.

**Storage.** The nonce is stored in the database as part of the `OidcFlowRecord`, alongside the state, PKCE verifier, return URL, and expiration time. The state value serves as the lookup key for the entire record.

**Inclusion in auth URL.** The nonce is added to the authorization URL as a query parameter:
```
https://accounts.google.com/o/oauth2/v2/auth?
  ...
  &nonce=<random-value>
  &state=<random-value>
  ...
```

**Validation.** When the callback arrives, volta:
1. Uses the state parameter to look up the `OidcFlowRecord` from the database.
2. Exchanges the authorization code for tokens, receiving an id_token from Google.
3. Verifies the id_token's signature using Google's JWKS.
4. Inside the custom claims verifier, checks that the `nonce` claim in the id_token matches `flow.nonce()` (the nonce from the database record).
5. If the nonce is missing from the token or does not match, volta throws an "Invalid nonce" error and the login fails.

The relevant validation code performs this check:
```
String nonce = (String) claims.getClaim("nonce");
if (nonce == null || !nonce.equals(expectedNonce)) {
    throw new IllegalArgumentException("Invalid nonce");
}
```

**One-time use.** Because the `OidcFlowRecord` is consumed (deleted from the database) when the state is validated, the nonce is also automatically consumed. You cannot replay the same id_token against volta because the nonce it contains no longer exists in the database.

## Common mistakes

**1. Not using a nonce at all.**
Some OAuth/OIDC implementations skip the nonce. This leaves the id_token vulnerable to replay attacks. The OIDC specification requires the nonce for the implicit flow and recommends it for the authorization code flow. volta always uses it.

**2. Using the same value for state and nonce.**
While technically the OIDC spec does not forbid this, using the same value means that if one is leaked, both protections are compromised. volta generates them independently.

**3. Not validating the nonce after receiving the id_token.**
Generating and sending a nonce is only half the job. If you don't check it when the token comes back, it serves no purpose.

**4. Storing the nonce only in the browser.**
If the nonce is in localStorage or a regular cookie, an attacker with XSS access could read it and craft a valid replay. volta stores the nonce in the server-side database.

**5. Using a predictable nonce.**
A nonce must be unguessable. Using a timestamp, a counter, or a hash of known data allows an attacker to predict it. volta uses 32 bytes of cryptographic randomness.

**6. Not consuming the nonce after use.**
If the nonce remains valid after the first use, the id_token can be replayed. volta's consume-on-lookup pattern ensures each nonce works exactly once.
