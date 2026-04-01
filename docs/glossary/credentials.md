# Credentials

[日本語版はこちら](credentials.ja.md)

---

## In one sentence?

Credentials are the proof you present to a system to prove you are who you claim to be -- like a password, an API key, or your Google account.

---

## Your ID and keys

Think of credentials like the different things you use to prove your identity in daily life:

| Real life | Digital credential | Used by |
|---|---|---|
| Driver's license | Username + password | Humans logging into websites |
| Hotel room key | [Session](session.md) [cookie](cookie.md) | [Browsers](browser.md) staying logged in |
| Employee badge | [API](api.md) key or [JWT](jwt.md) | Applications calling APIs |
| Fingerprint | OAuth token from Google | volta via [OIDC](oidc.md) |
| A letter of recommendation | Client ID + Client Secret | volta identifying itself to Google |

Key distinction:

- **Something you know** -- Password, PIN, secret question
- **Something you have** -- Phone (for 2FA codes), security key
- **Something you are** -- Fingerprint, face recognition

The more types you combine, the stronger the authentication. This is called Multi-Factor Authentication (MFA).

---

## Why do we need this?

Without credentials:

- Anyone could claim to be anyone -- no proof required
- [Login](login.md) would be meaningless ("I'm the admin" -- "Okay!")
- API endpoints would be open to the world
- No distinction between users, no [RBAC](authentication-vs-authorization.md), no audit trail
- Multi-tenant systems couldn't isolate data -- anyone claims to be in any tenant

Credentials are the foundation of trust in any system. If credentials are compromised, everything built on top collapses.

---

## Credentials in volta-auth-proxy

volta handles several types of credentials:

| Credential | Who holds it | What it proves | Stored where |
|---|---|---|---|
| Google account (email + password) | User | User's identity | Google (not volta) |
| Google Client ID + Secret | volta | volta is a legitimate OAuth client | [Environment variables](environment-variable.md) |
| `__volta_session` cookie | [Browser](browser.md) | User has a valid session | Cookie (browser) + sessions table (PostgreSQL) |
| [JWT](jwt.md) (RS256) | App | User is authenticated, has these roles | Passed via [header](header.md), verified with public key |
| RSA private key | volta | volta issued this JWT | File system or env var |
| Database connection string | volta | volta can access PostgreSQL | Environment variable |

**volta never stores user passwords.** By delegating [authentication](authentication-vs-authorization.md) to Google via [OIDC](oidc.md), volta avoids the enormous responsibility and risk of password storage. No password database means no password database breach.

**Credential security in volta:**

- Google Client Secret is stored in [environment variables](environment-variable.md), never in code
- Session cookies are [HttpOnly](httponly.md) + Secure + [SameSite](samesite.md)
- JWTs expire in 5 minutes -- short window if stolen
- RSA private key is never exposed via API
- Database credentials are internal to the [Docker network](network-isolation.md)

---

## Concrete example

The credential chain during a volta login:

1. User provides **Google credentials** (email + password + optional MFA) to Google
2. Google verifies them and gives volta an **authorization code**
3. volta exchanges the code using its **Client ID + Client Secret** to get tokens
4. volta creates a **session** and sets a **session cookie** in the [browser](browser.md)
5. volta signs a **JWT** with its **RSA private key** and passes it to the app via [headers](header.md)
6. The app verifies the JWT using volta's **RSA public key** (from `/.well-known/jwks.json`)

Each credential in this chain proves a different thing:

- Step 1: "I am this Google user"
- Step 3: "I am volta, a trusted OAuth client"
- Step 4: "This browser has an active session"
- Step 5: "This JWT was issued by volta and hasn't been tampered with"

If any credential is compromised:

- **Google password stolen** -- Attacker can log in as the user (mitigated by Google's MFA)
- **Client Secret leaked** -- Attacker can impersonate volta to Google (rotate immediately)
- **Session cookie stolen** -- Attacker has the user's session (volta can revoke it server-side)
- **RSA private key leaked** -- Attacker can forge JWTs (rotate key immediately)

---

## Learn more

- [Login](login.md) -- Where credentials are first presented
- [Session](session.md) -- The credential created after successful login
- [Cookie](cookie.md) -- How session credentials are stored in the browser
- [JWT](jwt.md) -- The credential volta passes to apps
- [Environment Variable](environment-variable.md) -- Where server-side credentials are stored
- [OIDC](oidc.md) -- The protocol that handles credential exchange with Google
