# PKCE (Proof Key for Code Exchange)

[日本語版はこちら](pkce.ja.md)

---

## What is it?

PKCE (pronounced "pixy") is a security extension to the OAuth 2.0 authorization code flow. It prevents a specific attack where someone intercepts the authorization code before you can use it. It works by creating a secret on the client side before the flow begins, and proving you know that secret when you exchange the code for tokens.

Think of it like mailing yourself a sealed envelope. Before you start the login process, you create a secret word and lock it in the envelope. When the login finishes and you receive the authorization code, you open the envelope and show the secret word to prove you are the same person who started the process. Anyone who intercepts the code in transit cannot open the envelope, so the code is useless to them.

---

## Why does it matter?

### The problem PKCE solves

In the standard OAuth 2.0 authorization code flow, here is what happens:

```
  1. Your app redirects the user to Google
  2. Google authenticates the user
  3. Google redirects back to your app with a CODE in the URL

     http://localhost:7070/callback?code=AUTHORIZATION_CODE&state=...

  4. Your app exchanges the CODE for tokens (server-to-server)
```

The vulnerability is in step 3. The authorization code travels through the user's browser in the URL. On mobile devices and single-page applications (SPAs), this code can be intercepted by:

- **Malicious apps on the same device** that register for the same redirect URI scheme
- **Browser extensions** that can read URL parameters
- **Browser history** that logs the full URL
- **Network intermediaries** (though HTTPS protects against most of these)

Without PKCE, if an attacker grabs the code, they can exchange it for tokens and impersonate the user. The code is like a bearer check -- whoever presents it gets the tokens.

### What breaks without PKCE?

For **mobile apps**, OAuth without PKCE is considered insecure by the IETF (the standards body). Multiple apps on a device can register to handle the same custom URL scheme (e.g., `myapp://callback`). The OS might deliver the redirect to the wrong app.

For **SPAs** (single-page applications), there is no client_secret. The app runs entirely in the browser, so any "secret" is visible in the source code. Without PKCE, the only thing protecting the code exchange is the client_secret -- which does not exist for public clients.

For **server-side apps** (like volta), PKCE is not strictly required because the client_secret provides protection. But volta uses it anyway as defense-in-depth -- if the client_secret were somehow compromised, PKCE still protects the flow.

---

## How does it work?

### Step by step

PKCE adds two new parameters to the flow: a **code_verifier** and a **code_challenge**.

```
  Step 1: BEFORE starting the login
  ══════════════════════════════════

  Your app generates a random string called the "code_verifier"
  (43-128 characters, URL-safe random bytes)

    code_verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

  Your app then creates a "code_challenge" by hashing the verifier:

    code_challenge = BASE64URL(SHA256(code_verifier))
                   = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

  The challenge is a one-way hash: you can go from verifier -> challenge,
  but you cannot go from challenge -> verifier.


  Step 2: START the login (authorization request)
  ══════════════════════════════════════════════════

  Your app redirects to Google with the code_challenge:

    GET https://accounts.google.com/o/oauth2/v2/auth
      ?response_type=code
      &client_id=YOUR_CLIENT_ID
      &redirect_uri=http://localhost:7070/callback
      &scope=openid email profile
      &state=RANDOM_STATE
      &code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM
      &code_challenge_method=S256

  Google stores the code_challenge alongside the authorization session.


  Step 3: USER authenticates with Google
  ══════════════════════════════════════

  (Same as normal -- user picks account, approves)


  Step 4: Google REDIRECTS back with a code
  ══════════════════════════════════════════

    http://localhost:7070/callback?code=AUTH_CODE&state=RANDOM_STATE

    >>> DANGER ZONE: The code is in the URL. It could be intercepted. <<<


  Step 5: EXCHANGE the code for tokens
  ═══════════════════════════════════════

  Your app sends the code AND the original code_verifier:

    POST https://oauth2.googleapis.com/token
      code=AUTH_CODE
      &client_id=YOUR_CLIENT_ID
      &client_secret=YOUR_CLIENT_SECRET
      &redirect_uri=http://localhost:7070/callback
      &grant_type=authorization_code
      &code_verifier=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk

  Google:
    1. Looks up the code_challenge it stored earlier
    2. Computes SHA256(code_verifier) to get a new challenge
    3. Compares: does the new challenge match the stored one?
    4. If YES -> issue tokens
       If NO  -> reject (code was intercepted by someone who
                          does not have the verifier)
```

### Why the attacker cannot win

```
  What the attacker has:          What the attacker needs:
  ┌─────────────────────────┐     ┌─────────────────────────┐
  │ - The authorization code │     │ - The authorization code │
  │   (intercepted from URL) │     │   (they have this) ✓     │
  │                          │     │ - The code_verifier      │
  │                          │     │   (they do NOT have this)│
  └─────────────────────────┘     └─────────────────────────┘

  The code_verifier never leaves your app.
  It was generated in your app's memory.
  It is sent directly to Google's token endpoint (server-to-server).
  The attacker only saw the code_challenge (the hash),
  and SHA-256 is a one-way function -- they cannot reverse it.
```

### S256 vs plain

PKCE supports two methods for creating the code_challenge:

| Method | How it works | Security |
|--------|-------------|----------|
| **S256** | `challenge = BASE64URL(SHA256(verifier))` | Secure. The challenge cannot be reversed to find the verifier. |
| **plain** | `challenge = verifier` | Insecure. The challenge IS the verifier. If someone sees the authorization request (step 2), they know the verifier. |

**Always use S256.** The `plain` method exists for backward compatibility with systems that cannot compute SHA-256 (extremely rare). volta uses S256 exclusively.

---

## How does volta-auth-proxy use it?

volta implements PKCE in `OidcService.java` and `SecurityUtils.java`.

### Code verifier generation

```java
// SecurityUtils.java
String verifier = SecurityUtils.randomUrlSafe(32);
// Generates 32 cryptographically random bytes, Base64URL-encoded
// Result: 43 characters of URL-safe random text
```

### Code challenge creation

```java
// SecurityUtils.java
public static String pkceChallenge(String verifier) {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
}
```

### Storage and retrieval

volta stores the code_verifier in the `oidc_flows` database table alongside the state and nonce:

```java
// OidcService.java - createAuthorizationUrl()
store.saveOidcFlow(new OidcFlowRecord(
    state,       // CSRF protection
    nonce,       // Replay protection
    verifier,    // PKCE protection
    returnTo,    // Where to redirect after login
    inviteCode,  // Invitation code (if joining via invite)
    expiresAt    // 10-minute expiry
));
```

When Google redirects back, volta retrieves the verifier and sends it to Google's token endpoint:

```java
// OidcService.java - exchangeCode()
String body = "code=" + enc(code)
    + "&client_id=" + enc(config.googleClientId())
    + "&client_secret=" + enc(config.googleClientSecret())
    + "&redirect_uri=" + enc(config.googleRedirectUri())
    + "&grant_type=authorization_code"
    + "&code_verifier=" + enc(codeVerifier);  // <-- PKCE verifier
```

### Why volta uses PKCE even though it has a client_secret

volta is a server-side application. It has a `client_secret` that provides its own protection during the code exchange. So why use PKCE too?

1. **Defense in depth:** If the client_secret is ever leaked (misconfigured environment variable, log exposure), PKCE still protects the flow.
2. **Best practice:** Google recommends PKCE for all OAuth clients, not just public ones.
3. **Future-proofing:** If volta ever supports mobile clients or SPAs directly, PKCE is already in place.
4. **It costs nothing:** PKCE adds one SHA-256 computation and one extra parameter. Zero performance impact.

---

## Common mistakes and attacks

### Mistake 1: Using `plain` instead of `S256`

With `plain`, the code_challenge equals the code_verifier. If the attacker can see the authorization request (step 2), they have everything they need to complete the code exchange. Always use S256.

### Mistake 2: Reusing the code_verifier

The code_verifier must be unique for every authorization request. If you reuse it, an attacker who learned the verifier from a previous flow can use it on the next one.

### Mistake 3: Storing the code_verifier in a predictable location

On mobile, do not store the code_verifier in shared preferences or other locations accessible to other apps. Keep it in memory for the duration of the flow.

### Attack: Authorization code interception (what PKCE prevents)

On Android, a malicious app can register a custom URL scheme matching your redirect URI. When Google redirects back:

```
  Google redirects: myapp://callback?code=AUTH_CODE

  Android asks: "Which app should handle myapp://?"

  If a malicious app also registered myapp://, the OS might
  deliver the redirect to the malicious app instead of yours.

  Without PKCE: The malicious app exchanges the code -> game over.
  With PKCE:    The malicious app has the code but not the verifier
                -> token exchange fails -> your data is safe.
```

---

## Further reading

- [RFC 7636 - Proof Key for Code Exchange](https://tools.ietf.org/html/rfc7636) -- The official PKCE specification.
- [OAuth 2.0 for Native Apps (RFC 8252)](https://tools.ietf.org/html/rfc8252) -- Why PKCE is essential for mobile.
- [OAuth 2.0 for Browser-Based Apps](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-browser-based-apps) -- PKCE for SPAs.
- [oidc.md](oidc.md) -- The full OIDC flow that PKCE protects.
- [csrf.md](csrf.md) -- The state parameter, which works alongside PKCE.
