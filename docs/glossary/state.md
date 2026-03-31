# State Parameter (OAuth / OIDC)

## What is it?

The state parameter is a random, unguessable string that your application generates before sending the user to the identity provider (like Google) for login. When Google finishes authentication and redirects the user back to your application, it includes the same state value in the redirect URL. Your application then checks that the returned state matches the one it originally sent.

It works like a claim ticket at a coat check: you hand over your coat, get a numbered ticket, and when you come back, you must present the same ticket to get your coat. If someone else shows up with a different ticket -- or no ticket -- they get nothing.

In the OIDC (OpenID Connect) login flow, it looks like this:

```
  Your App                    Google                   Your App
  (before login)              (identity provider)      (after redirect)
  +-------------+            +---------+               +-------------+
  |             |            |         |               |             |
  | Generate    |  Redirect  |         |  Redirect     | Check:      |
  | state=abc123| ---------> | Login   | ------------> | state=abc123|
  | Save state  |            | page    |  ?code=xyz    | matches     |
  | in DB       |            |         |  &state=abc123| saved value?|
  +-------------+            +---------+               +-------------+
```

## Why does it matter?

The state parameter prevents a category of attacks called CSRF (Cross-Site Request Forgery) against the OAuth/OIDC login flow. Without it, an attacker could trick your browser into completing a login flow with the attacker's account or with a stolen authorization code.

Here is the attack scenario without a state parameter:

```
  1. Attacker starts an OIDC login with your app
  2. Attacker logs in with their Google account
  3. Google redirects back: /callback?code=ATTACKER_CODE
  4. Attacker STOPS here -- does not follow the redirect
  5. Attacker crafts a link: https://your-app.com/callback?code=ATTACKER_CODE
  6. Attacker tricks the victim into clicking that link
  7. Your app exchanges the code, gets the attacker's identity
  8. Your app creates a session for the attacker's account in the victim's browser
  9. Victim is now logged in as the attacker
  10. Anything the victim does (uploads, settings changes) goes to the attacker's account
```

This is called a "login CSRF" attack. The attacker doesn't steal the victim's account -- they force the victim into the attacker's account, then collect whatever the victim does there.

With the state parameter, step 7 fails because the victim's browser never generated that state value. Your app checks the database, finds no matching state, and rejects the request.

## How does it work?

The state parameter works through a simple challenge-response pattern:

**Step 1: Generate and store.**
Before redirecting the user to the identity provider, your app generates a random string and stores it somewhere the user's session can access later (a database, a server-side session, or a signed cookie).

**Step 2: Include in the authorization URL.**
The state value is added as a query parameter to the authorization URL:
```
https://accounts.google.com/o/oauth2/v2/auth?
  response_type=code&
  client_id=YOUR_CLIENT_ID&
  redirect_uri=https://your-app.com/callback&
  scope=openid email profile&
  state=abc123xyz789
```

**Step 3: Identity provider passes it through.**
Google (or any OIDC provider) does not interpret the state value at all. It simply includes it unchanged in the redirect back to your app:
```
https://your-app.com/callback?code=AUTHORIZATION_CODE&state=abc123xyz789
```

**Step 4: Validate.**
Your app receives the callback, extracts the state parameter, and checks it against the stored value. If they match, the flow continues. If not, the request is rejected.

**Step 5: Consume (one-time use).**
After validation, the stored state should be deleted so it cannot be reused. This prevents replay attacks where someone tries to submit the same callback URL twice.

The complete flow:

```
  Browser          Your App (Server)         Google
  |                |                         |
  |  Click Login   |                         |
  |--------------->|                         |
  |                |                         |
  |                | Generate state=RND123   |
  |                | Save to DB with         |
  |                | expiry = 10 minutes     |
  |                |                         |
  |  302 Redirect to Google + state=RND123   |
  |<---------------|                         |
  |                                          |
  |  User logs in at Google                  |
  |----------------------------------------->|
  |                                          |
  |  302 Redirect: /callback?code=XYZ       |
  |               &state=RND123              |
  |<-----------------------------------------|
  |                                          |
  |  GET /callback?code=XYZ&state=RND123    |
  |--------------->|                         |
  |                |                         |
  |                | Look up state=RND123    |
  |                | in DB. Found? Continue. |
  |                | Delete from DB (used).  |
  |                |                         |
  |                | Exchange code for tokens |
  |                |------------------------>|
  |                |<------------------------|
  |                |                         |
  |  Set session   |                         |
  |<---------------|                         |
```

## How does volta-auth-proxy use it?

volta-auth-proxy generates and validates state parameters as part of its OIDC login flow with Google.

**Generation.** When a user clicks "Login with Google," volta's `OidcService.createAuthorizationUrl()` method generates a 32-byte cryptographically random, URL-safe string using `SecurityUtils.randomUrlSafe(32)`. This uses Java's `SecureRandom`, which is a cryptographically strong random number generator.

**Storage.** The state is saved to the database as part of an `OidcFlowRecord` along with other flow data:
- The state value itself (used as the lookup key)
- A nonce (for id_token replay prevention -- see the nonce article)
- A PKCE code verifier (for authorization code protection)
- The `returnTo` URL (where to send the user after login)
- An invite code (if the user is joining via invitation)
- An expiration timestamp (10 minutes from creation)

**Validation.** When Google redirects back to `/callback`, volta's `OidcService.exchangeAndValidate()` method:
1. Extracts the `state` parameter from the callback URL.
2. Calls `store.consumeOidcFlow(state)` which both looks up and deletes the record in a single operation (consume = find + delete).
3. If no record is found, it throws an "Invalid state" error and the login fails.
4. If the record is found but expired (older than 10 minutes), it throws a "State expired" error.
5. Only if the state is valid and not expired does the flow continue to exchange the authorization code for tokens.

**Bundle approach.** volta bundles state, nonce, and PKCE verifier together in a single database record. This is a clean design because all three values belong to the same login attempt and should be consumed together.

## Common mistakes

**1. Not using a state parameter at all.**
Some OAuth tutorials skip the state parameter for simplicity. This leaves the login flow wide open to CSRF attacks. Always use it.

**2. Using a predictable value.**
The state must be cryptographically random and unguessable. Using a timestamp, a sequential counter, or a hash of the user's email defeats the purpose. volta uses 32 bytes of `SecureRandom` output, which is 256 bits of entropy.

**3. Storing state only in the browser (like localStorage).**
If an attacker can run JavaScript on your page (via XSS), they could read the expected state and craft a valid callback URL. Server-side storage (like volta's database) is safer.

**4. Not expiring the state.**
A state value that lives forever is a window that stays open forever. volta expires state records after 10 minutes. If a user takes longer than that to log in, they simply start over.

**5. Not deleting state after use.**
If the state is not consumed (deleted) after a successful validation, an attacker who captures the callback URL could replay it. volta's `consumeOidcFlow()` deletes the record atomically during lookup, ensuring one-time use.

**6. Confusing state with nonce.**
Both are random values in the OIDC flow, but they serve different purposes. The state parameter protects the redirect flow (CSRF prevention). The nonce protects the id_token (replay prevention). See the nonce article for the distinction.
