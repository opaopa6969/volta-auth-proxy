# Google Cloud Console

[日本語版はこちら](google-cloud-console.ja.md)

---

## What is it?

Google Cloud Console is Google's web-based management dashboard for Google Cloud Platform (GCP) services. It is the place where developers create projects, manage APIs, configure billing, and -- most importantly for volta-auth-proxy -- create the OAuth 2.0 credentials needed for "Sign in with Google."

Think of Google Cloud Console like a government office where you go to get official documents. You need a passport (OAuth client credentials) to travel to other countries (authenticate users). The government office (Google Cloud Console) issues the passport, sets the rules (authorized redirect URIs), and can revoke it if you break the rules.

Google Cloud Console is not an authentication service itself -- it is the admin panel where you configure Google's authentication services. The actual authentication happens through Google's OAuth 2.0 / [OIDC](oidc.md) endpoints.

---

## Why does it matter?

Every application that uses "Sign in with Google" must have credentials created in Google Cloud Console. Without these credentials, you cannot initiate the [OIDC](oidc.md) flow. This is a mandatory setup step for volta-auth-proxy.

The credentials you create here contain:

- **Client ID**: A public identifier for your application (safe to expose in browser redirects)
- **Client Secret**: A private key your server uses to exchange authorization codes for tokens (must never reach the browser)

These two values are the only Google-specific configuration volta-auth-proxy needs. They go in your `.env` file, and volta uses them to talk to Google's OIDC endpoints.

---

## How does it work?

### Creating OAuth credentials for volta

Here is the step-by-step process in Google Cloud Console:

#### Step 1: Create or select a project

```
Google Cloud Console → Select project → New Project
  Project name: volta-auth-proxy
  Organization: (your org or "No organization")
```

A GCP project is a container for resources. All OAuth credentials belong to a project.

#### Step 2: Enable the required APIs

Navigate to **APIs & Services > Library** and enable:

| API | Why |
|-----|-----|
| **Google People API** | Needed if you request profile information (name, picture) |

Note: The basic OIDC flow (email verification) works without enabling any extra APIs. The People API is only needed for additional profile data.

#### Step 3: Configure the OAuth consent screen

Navigate to **APIs & Services > OAuth consent screen**:

| Setting | Value for development | Value for production |
|---------|----------------------|---------------------|
| User Type | External | External |
| App name | volta-auth-proxy (dev) | Your SaaS Name |
| Support email | your@email.com | support@yourdomain.com |
| Authorized domains | yourdomain.com | yourdomain.com |
| Scopes | openid, email, profile | openid, email, profile |

The consent screen is what users see when they click "Sign in with Google" -- "volta-auth-proxy wants to access your email address. Allow?"

#### Step 4: Create OAuth 2.0 credentials

Navigate to **APIs & Services > Credentials > Create Credentials > OAuth client ID**:

| Setting | Value |
|---------|-------|
| Application type | Web application |
| Name | volta-auth-proxy |
| Authorized redirect URIs | `http://localhost:7070/callback` (dev) |
| | `https://auth.yourdomain.com/callback` (prod) |

After creation, you get:

```
Client ID:     xxxxxxxxxxxx.apps.googleusercontent.com
Client Secret: GOCSPX-xxxxxxxxxxxxxxxxxxxxxxxx
```

#### Step 5: Add to volta .env

```env
GOOGLE_CLIENT_ID=xxxxxxxxxxxx.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=GOCSPX-xxxxxxxxxxxxxxxxxxxxxxxx
```

### Publishing status

| Status | What it means |
|--------|--------------|
| **Testing** | Only users you explicitly add as test users can log in. Limited to 100 test users. |
| **In production** | Any Google account can log in. Requires Google's verification for sensitive scopes. |

For development, "Testing" is fine. For production, you must submit for verification if you use scopes beyond `openid`, `email`, and `profile`.

### OAuth consent screen vs Login UI

Important distinction:

```
  1. User clicks "Login" on your app
  2. volta redirects to Google
  3. Google shows ACCOUNT PICKER (Google's UI -- always)
  4. Google shows CONSENT SCREEN (Google's UI -- first time only)
  5. Google redirects back to volta with authorization code
  6. volta shows YOUR dashboard (volta's UI -- jte templates)
```

The consent screen is Google's UI that you configure in Cloud Console. You do NOT control its layout -- only the app name, logo, and privacy policy URL. volta's jte templates control everything before and after the Google interaction.

---

## How does volta-auth-proxy use it?

Google Cloud Console is where you create the OAuth credentials that volta-auth-proxy needs. It is a one-time setup step, not an ongoing dependency.

### What volta needs from Google Cloud Console

| Item | Where it goes | How often you touch it |
|------|---------------|----------------------|
| Client ID | `.env` file | Once (unless rotating) |
| Client Secret | `.env` file | Once (unless rotating) |
| Redirect URI | Credential settings | Once per environment (dev, staging, prod) |
| Consent screen | OAuth consent screen | Once (unless rebranding) |

### Credential rotation

Best practice is to rotate the client secret periodically:

1. Create a new credential in Google Cloud Console
2. Update volta's `.env` file with the new secret
3. Restart volta
4. Delete the old credential in Google Cloud Console

Active sessions are not affected because volta uses its own session cookies, not Google's tokens, after the initial login.

### Multiple environments

Create separate OAuth credentials for each environment:

| Environment | Redirect URI | Credential |
|-------------|-------------|------------|
| Local dev | `http://localhost:7070/callback` | volta-dev |
| Staging | `https://auth-staging.example.com/callback` | volta-staging |
| Production | `https://auth.example.com/callback` | volta-prod |

---

## Common mistakes and attacks

### Mistake 1: Using the same credentials for dev and prod

If your development credentials leak (in a git commit, in logs, shared with a contractor), an attacker can impersonate your application. Use separate credentials for each environment.

### Mistake 2: Adding overly broad redirect URIs

Google allows wildcard-like patterns in redirect URIs. Adding `https://*.example.com/callback` is dangerous -- if an attacker creates `https://evil.example.com/callback`, they can intercept authorization codes. Be as specific as possible.

### Mistake 3: Not setting the consent screen to production

If your consent screen stays in "Testing" mode, only whitelisted test users can log in. Real customers will see an error: "This app is not yet verified." Submit for production before launching.

### Mistake 4: Committing the client secret to git

The client secret must never be in source control. Use environment variables, a `.env` file (gitignored), or a secrets manager. If the secret leaks, rotate it immediately in Google Cloud Console.

### Attack: OAuth application impersonation

An attacker creates their own Google Cloud project with a similar app name and consent screen. They phish users into "signing in with Google" through their fake app, capturing the authorization code. Users should verify the consent screen shows the correct app name before granting access.

---

## Further reading

- [Google Cloud Console](https://console.cloud.google.com/) -- The console itself.
- [Google OAuth 2.0 setup guide](https://developers.google.com/identity/protocols/oauth2) -- Official documentation.
- [Google OIDC documentation](https://developers.google.com/identity/openid-connect/openid-connect) -- How Google implements OIDC.
- [oidc.md](oidc.md) -- The protocol that uses these credentials.
- [oauth2.md](oauth2.md) -- The framework OIDC is built on.
- [google-workspace.md](google-workspace.md) -- Google as an identity provider.
- [redirect-uri.md](redirect-uri.md) -- Why redirect URIs matter for security.
