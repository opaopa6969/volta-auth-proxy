# SuperTokens

[日本語版はこちら](supertokens.ja.md)

---

## What is it?

SuperTokens is an open-source authentication solution primarily written in Node.js (with a Java-based core). It provides pre-built login flows -- email/password, passwordless, social login, MFA -- that you integrate into your application via SDKs. SuperTokens can be self-hosted or used as a managed cloud service.

Think of SuperTokens like a pre-fabricated kitchen you install in your house. You pick the layout (email login, Google login, passwordless), the kitchen company delivers the cabinets, countertop, and appliances, and you install them in your home. You own the kitchen and can modify it, but you did not design it from scratch. Compare this to volta-auth-proxy, which is more like buying raw lumber and building the kitchen yourself -- more work, but you understand every joint and screw.

SuperTokens sits in the same competitive space as [Auth0](auth0.md) (managed), [Keycloak](keycloak.md) (self-hosted, heavy), and volta-auth-proxy (self-hosted, light). Its primary differentiator is the SDK-driven integration model: instead of running a separate identity service, you embed SuperTokens into your application via library calls.

---

## Why does it matter?

SuperTokens matters in the volta-auth-proxy context as a **competitor in the self-hosted authentication space**. When developers evaluate self-hosted auth solutions, SuperTokens is one of the most frequently mentioned alternatives.

### The self-hosted auth landscape

| Solution | Language | Architecture | Weight |
|----------|----------|-------------|--------|
| [Keycloak](keycloak.md) | Java | Standalone server | Heavy (~512MB-2GB RAM) |
| [ZITADEL](zitadel.md) | Go | Standalone server | Medium (~200-500MB RAM) |
| [Ory Stack](ory-stack.md) | Go | Multiple microservices | Medium-Heavy |
| SuperTokens | Node.js + Java core | SDK + core service | Medium (~200-400MB) |
| volta-auth-proxy | Java | Standalone + ForwardAuth | Light (~30-50MB) |

SuperTokens is notable for its developer experience -- the React/Next.js SDKs provide pre-built UI components for login, and the backend SDKs handle session management automatically. This is the opposite of volta's approach, where you write the auth code yourself.

---

## How does it work?

### Architecture

```
  ┌──────────────────────────────────────────┐
  │            Your Application               │
  │                                           │
  │  ┌───────────────┐  ┌─────────────────┐  │
  │  │  Frontend SDK  │  │  Backend SDK    │  │
  │  │  (React, Vue)  │  │  (Node, Python) │  │
  │  │                │  │                 │  │
  │  │  Pre-built     │  │  Auth routes    │  │
  │  │  login forms   │  │  Session mgmt   │  │
  │  └───────────────┘  └────────┬────────┘  │
  │                              │            │
  └──────────────────────────────┼────────────┘
                                 │ HTTP
                                 ▼
                    ┌──────────────────────┐
                    │  SuperTokens Core    │
                    │  (Java process)      │
                    │                      │
                    │  User storage        │
                    │  Session management  │
                    │  Recipe logic        │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │     PostgreSQL /      │
                    │     MySQL             │
                    └──────────────────────┘
```

### Recipes

SuperTokens uses a "recipe" system -- each authentication method is a recipe:

| Recipe | Description |
|--------|-------------|
| **EmailPassword** | Traditional email + password login |
| **ThirdParty** | Social login (Google, Apple, GitHub, etc.) |
| **Passwordless** | Magic link or OTP via email/SMS |
| **ThirdPartyEmailPassword** | Combines social + email/password |
| **Session** | Session management with rotating refresh tokens |
| **EmailVerification** | Email verification flows |
| **UserRoles** | Role-based access control |
| **MultiFactorAuth** | TOTP-based MFA |

### SDK integration example (Node.js)

```javascript
const supertokens = require("supertokens-node");
const Session = require("supertokens-node/recipe/session");
const ThirdParty = require("supertokens-node/recipe/thirdparty");

supertokens.init({
    supertokens: { connectionURI: "http://localhost:3567" },
    appInfo: {
        appName: "My App",
        apiDomain: "http://localhost:3000",
        websiteDomain: "http://localhost:3000",
    },
    recipeList: [
        ThirdParty.init({
            signInAndUpFeature: {
                providers: [
                    ThirdParty.Google({ clientId: "...", clientSecret: "..." }),
                ],
            },
        }),
        Session.init(),
    ],
});
```

### SuperTokens vs volta-auth-proxy

| Aspect | SuperTokens | volta-auth-proxy |
|--------|-------------|-----------------|
| Integration model | SDK embedded in your app | Reverse proxy ForwardAuth |
| Language ecosystem | Node.js/Python/Go SDKs | Java (Javalin) |
| Frontend | Pre-built React components | jte templates (server-rendered) |
| Multi-tenancy | Supported (paid feature in cloud) | Core design principle |
| Proxy pattern | Not supported | Core architecture ([ForwardAuth](forwardauth.md)) |
| Session management | Rotating refresh tokens (SDK handles) | Server-side sessions (Postgres) |
| Auth code visibility | SDK abstractions | Full source code control |
| npm dependencies | Many (SDK + transitive) | Zero (Java, no npm) |

---

## How does volta-auth-proxy use it?

volta-auth-proxy does **not** use SuperTokens. They are competitors with fundamentally different integration philosophies.

### Why volta chose a different approach

1. **SDK vs proxy**: SuperTokens embeds auth into your application via SDKs. volta sits in front of your application as a [ForwardAuth](forwardauth.md) proxy. The proxy approach means your application does not need to know about auth at all -- it just receives identity headers.

2. **npm risk**: SuperTokens' Node.js SDKs bring hundreds of npm dependencies. volta's philosophy is zero npm -- Java with Maven, no supply-chain risk from the npm ecosystem. See the [user profile](user_profile.md) for context on this design decision.

3. **Black box concern**: SuperTokens SDKs abstract away the auth logic. When something goes wrong, you debug through SDK layers. volta is code you wrote (or can read) -- every handler, every SQL query, every validation step is visible in your IDE.

4. **Multi-tenancy approach**: SuperTokens added multi-tenancy later as a feature. volta was designed for multi-tenancy from day one -- the tenant concept permeates every table, every query, every handler.

5. **ForwardAuth pattern**: SuperTokens does not support the reverse proxy ForwardAuth pattern. If you want to protect multiple services behind a single auth layer (the typical microservices setup), volta with [Traefik](traefik.md) handles this natively. SuperTokens requires each service to integrate the SDK.

### When to choose SuperTokens over volta

- You are building a Node.js/React application and want pre-built auth UI
- You want social login + email/password + passwordless all in one
- You do not need the reverse proxy ForwardAuth pattern
- You are comfortable with npm dependencies
- You want the SuperTokens managed cloud option

### When to choose volta over SuperTokens

- You need the ForwardAuth proxy pattern (protecting multiple services)
- You are a Java shop and want auth in the same language as your application
- You want zero npm dependencies
- You want to understand and control every line of auth code
- Multi-tenancy is a core requirement, not an add-on
- You want minimal resource usage

---

## Common mistakes and attacks

### Mistake 1: Not updating the SuperTokens Core

The SuperTokens Core (Java process) and SDKs must be version-compatible. Running a newer SDK with an older Core can cause silent failures or security issues. Keep them in sync.

### Mistake 2: Exposing the Core API

The SuperTokens Core exposes an HTTP API on port 3567. If this is reachable from the internet, attackers can create users, modify sessions, and bypass auth. Bind it to localhost or an internal network only.

### Mistake 3: Relying solely on frontend SDK for protection

The frontend SDK provides UI components but cannot enforce security. All security checks must happen in the backend SDK. A determined attacker can bypass any frontend protection.

### Mistake 4: Not customizing the default UI for production

SuperTokens' pre-built UI is functional but generic. Shipping it unchanged in production looks unprofessional and may confuse users who expect your brand. Customize the UI or build your own using the SDK's headless mode.

### Attack: Session token theft via XSS

SuperTokens stores session tokens in cookies. If your application has an [XSS](xss.md) vulnerability, an attacker can steal these tokens. SuperTokens mitigates this with HttpOnly cookies, but XSS can still perform actions on behalf of the user within the same browser context.

---

## Further reading

- [SuperTokens documentation](https://supertokens.com/docs/) -- Official docs.
- [SuperTokens GitHub](https://github.com/supertokens/supertokens-core) -- Source code of the Core.
- [keycloak.md](keycloak.md) -- Heavy self-hosted alternative.
- [auth0.md](auth0.md) -- Managed SaaS alternative.
- [zitadel.md](zitadel.md) -- Go-based self-hosted alternative.
- [ory-stack.md](ory-stack.md) -- Modular Go-based alternative.
- [forwardauth.md](forwardauth.md) -- The proxy pattern volta uses instead of SDKs.
