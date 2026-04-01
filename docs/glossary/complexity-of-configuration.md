# The Complexity of Configuration

[日本語版はこちら](complexity-of-configuration.ja.md)

---

Configuration complexity is one of the most underestimated costs in software engineering. It hides in plain sight. Nobody puts "configuration complexity" on a project risk register. Nobody estimates how many hours the team will spend reading documentation for settings they do not understand. And yet, configuration complexity has killed more projects than bad algorithms ever will.

---

## The illusion of "just configure it"

Every enterprise software vendor says the same thing: "Our product is highly configurable. You can customize it to fit any use case." This sounds wonderful. It is a trap.

Here is what "highly configurable" actually means:

1. Someone must learn what all the settings do
2. Someone must decide which values are correct for your use case
3. Someone must test that the chosen values actually work together
4. Someone must document why those values were chosen
5. Someone must maintain those values as the software evolves
6. Someone must troubleshoot when a setting change breaks something
7. Someone must onboard new team members who stare at the config file in confusion

That "someone" is you.

---

## When configuration becomes programming without a compiler

Consider Keycloak's `realm.json`. Here is a real excerpt from a production configuration:

```json
{
  "realm": "my-saas",
  "sslRequired": "external",
  "bruteForceProtected": true,
  "maxFailureWaitSeconds": 900,
  "minimumQuickLoginWaitSeconds": 60,
  "waitIncrementSeconds": 60,
  "quickLoginCheckMilliSeconds": 1000,
  "maxDeltaTimeSeconds": 43200,
  "failureFactor": 30,
  "otpPolicyType": "totp",
  "otpPolicyAlgorithm": "HmacSHA1",
  "otpPolicyInitialCounter": 0,
  "otpPolicyDigits": 6,
  "otpPolicyLookAheadWindow": 1,
  "otpPolicyPeriod": 30,
  "clients": [{
    "clientId": "my-app",
    "standardFlowEnabled": true,
    "implicitFlowEnabled": false,
    "directAccessGrantsEnabled": false,
    "serviceAccountsEnabled": false,
    "publicClient": true,
    "fullScopeAllowed": true,
    "defaultClientScopes": ["openid", "profile", "email"],
    "optionalClientScopes": ["offline_access"]
  }]
}
```

Look at this file. Really look at it.

- What is `quickLoginCheckMilliSeconds`? What happens if you set it to 500 instead of 1000?
- What is `maxDeltaTimeSeconds`? Why is it 43200? Is that seconds? Hours? What is a "delta time" in this context?
- What is `fullScopeAllowed`? Is `true` safe? What does `false` do? What is a "full scope"?
- What is `otpPolicyLookAheadWindow`? Why is it 1? What happens at 0? At 5?

These are not rhetorical questions. These are questions that real developers ask when they encounter this file for the first time. And the answers are not obvious -- they require reading Keycloak documentation, which itself requires understanding Keycloak's internal concepts, which requires weeks of study.

**This is programming.** You are writing instructions that control system behavior. But unlike actual code:

- There is no compiler to catch mistakes
- There is no type system to prevent invalid values
- There is no test framework to verify correctness
- There is no IDE to auto-complete or explain options
- There is no diff tool that meaningfully shows "what changed and why"

Configuration-as-programming is the worst kind of programming: all the complexity, none of the safety nets.

---

## The hidden time cost

I have watched teams spend more time configuring Keycloak than it would have taken to build the auth features they needed from scratch. Here is a typical timeline:

```
  Week 1:  "Let's use Keycloak! It does everything!"
  Week 2:  Reading Keycloak docs. "What's a realm? What's a client?"
  Week 3:  First realm.json works. Login page is ugly.
  Week 4:  Fighting FreeMarker templates to customize login.
  Week 5:  "Wait, how does multi-tenancy work? One realm per tenant?"
  Week 6:  Realizing realm-per-tenant doesn't scale. Researching alternatives.
  Week 7:  Custom SPI development to work around limitations.
  Week 8:  Still debugging the SPI. Stack Overflow has no answers.
  Week 9:  "Maybe we should have just built this ourselves."
  Week 10: Too invested to switch. Sunk cost fallacy takes hold.
```

Ten weeks. Two and a half months. And the team has not written a single line of their actual product's business logic.

---

## volta's choice: code over configuration

volta-auth-proxy takes the opposite approach. Instead of exposing hundreds of settings, volta makes opinionated choices and embeds them in code.

**Keycloak's approach:** "You decide everything."

```json
"otpPolicyType": "totp",
"otpPolicyAlgorithm": "HmacSHA1",
"otpPolicyInitialCounter": 0,
"otpPolicyDigits": 6,
"otpPolicyLookAheadWindow": 1,
"otpPolicyPeriod": 30
```

**volta's approach:** "We decided. TOTP, SHA1, 6 digits, 30 seconds. These are the right choices. Move on."

volta's entire configuration:

```bash
# .env
DATABASE_URL=jdbc:postgresql://localhost:5432/volta_auth
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
SESSION_SECRET=your-random-secret
BASE_URL=https://auth.example.com
```

```yaml
# volta-config.yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
```

Every setting is obvious. Every value is something you already know (your database URL, your Google credentials, your domain). There is nothing to research, nothing to misunderstand, nothing to get wrong.

---

## "But what if I need to change something?"

This is the question people always ask. "What if the default session timeout is wrong for me? What if I need a different JWT algorithm?"

The answer: **change the code.**

volta is open source. If you need RS384 instead of RS256, you change one line in `JwtService.java`. If you need a 4-hour session timeout instead of 8 hours, you change one constant. This is not harder than changing a configuration value -- it is actually easier, because:

1. The code has type checking (the compiler catches mistakes)
2. The code has tests (you can verify the change works)
3. The code has git history (you can see why it was changed)
4. The code is self-documenting (the variable name explains what it does)

The difference is philosophical. Keycloak says: "You might need to change anything, so everything is configurable." volta says: "You probably won't need to change most things, and when you do, code is a better place to change it than a configuration file."

---

## The 80/20 rule of configuration

In my experience, 80% of configuration options in enterprise software are never changed from their defaults. They exist because some product manager once said "a customer might want to change this" -- and so a setting was born. That setting now must be documented, tested, maintained, and explained to every new user.

volta eliminates those settings entirely. The 20% of things that differ between deployments (database URL, OAuth credentials, app list) are in `.env` and `volta-config.yaml`. The 80% of things that are the same everywhere (JWT algorithm, session timeout, max sessions, PKCE enforcement) are in code.

---

## The cost of "flexibility"

Flexibility is not free. Every configuration option is:

- A decision someone must make
- Documentation someone must write
- A test case someone must cover
- A support ticket when someone gets it wrong
- A security vulnerability when someone sets it to an insecure value

volta's opinionated defaults are not limitations. They are **decisions that have already been made correctly.** RS256 is the right JWT algorithm for this use case. 8-hour sliding window is the right session timeout. PKCE is required, not optional. These are security decisions, not preferences.

When you use volta, you are not accepting limitations. You are inheriting good decisions.

---

## Further reading

- [config-hell.md](config-hell.md) -- The detailed technical analysis of configuration complexity.
- [keycloak.md](keycloak.md) -- The Swiss Army knife that inspired volta's minimalism.
- [tradeoff.md](tradeoff.md) -- The broader trade-off framework.
- [dashboard.md](dashboard.md) -- GUI vs code configuration.
