# Configuration vs Code

[日本語版はこちら](configuration-vs-code.ja.md)

---

## What is it?

The line between "configuration" and "code" is the distinction between values that change between deployments (configuration) and logic that defines how the system behaves (code). Configuration is "what" -- what database to connect to, what domain to use. Code is "how" -- how to validate a session, how to sign a JWT.

Think of it like driving a car. Configuration is the settings: your seat position, mirror angles, radio station, GPS destination. You change these without being a mechanic. Code is the engine, transmission, and brakes -- the mechanics of how the car actually works. You do NOT want your radio station setting to accidentally affect your braking system. And you definitely do not want to rebuild the engine every time you change the radio station.

---

## Where is the line?

There is a spectrum from pure configuration to pure code:

```
Pure configuration ◄─────────────────────────────► Pure code

.env secrets    YAML settings    DSL rules    Application logic
DATABASE_URL    allowed_roles    state machine  if/else in Java
API_KEY         app URLs         guard expr.    algorithms
                                               error handling

"Changes per     "Changes per    "Changes per   "Changes per
 deployment"      feature"        design"        feature"

No compiler      No compiler     No compiler    Compiler catches
checks           checks          checks (yet)   errors
```

The further left you go, the simpler but less powerful. The further right, the more powerful but more complex. The danger zone is in the middle: when configuration becomes so complex that it is effectively programming -- but without the safety nets that real programming provides.

---

## volta's stance: three layers, clear boundaries

volta draws the line clearly:

### Layer 1: .env -- secrets that differ per deployment

```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/volta_auth
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
SESSION_SECRET=your-random-secret
BASE_URL=https://auth.example.com
```

These are pure configuration. They contain no logic. They differ between your laptop, your staging server, and your production server. A wrong value causes a clear error ("cannot connect to database"), not a subtle behavioral change.

### Layer 2: YAML -- settings that define what the system protects

```yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
  - id: app-admin
    url: https://admin.example.com
    allowed_roles: [ADMIN, OWNER]
```

These are structured settings. They define which apps exist and who can access them. They are declarative ("this app allows these roles") rather than imperative ("if role equals ADMIN then allow"). Adding an app means adding 3 lines, not writing new code.

### Layer 3: Java -- logic that defines how the system behaves

```java
// Session timeout: 8 hours, sliding window
private static final Duration SESSION_TIMEOUT = Duration.ofHours(8);

// JWT algorithm: RS256 only
private static final String JWT_ALGORITHM = "RS256";

// Max concurrent sessions: 5 per user
private static final int MAX_SESSIONS = 5;
```

These are code. They are opinionated decisions embedded in the codebase. To change them, you modify source code, which means you get:

- Compiler checks (typos are caught)
- Type safety (you cannot set a timeout to "banana")
- Test coverage (the behavior is tested)
- Git history (you see who changed it and why)
- Code review (someone reviews the change)

---

## Why too much configuration becomes programming without a compiler

This is the key insight. Consider Keycloak's realm.json:

```json
{
  "bruteForceProtected": true,
  "maxFailureWaitSeconds": 900,
  "minimumQuickLoginWaitSeconds": 60,
  "waitIncrementSeconds": 60,
  "quickLoginCheckMilliSeconds": 1000,
  "maxDeltaTimeSeconds": 43200,
  "failureFactor": 30
}
```

This is not configuration. This is programming a brute-force detection algorithm via JSON. You are specifying:
- The algorithm's behavior (exponential backoff)
- Timing parameters (wait times, check intervals)
- Threshold values (failure factors, max deltas)

But unlike actual code, this JSON has:
- No compiler to catch mistakes (set `failureFactor` to -1, nobody stops you)
- No type system (set `maxFailureWaitSeconds` to "yes", good luck)
- No tests (how do you unit test a JSON file?)
- No IDE support (no autocomplete, no "go to definition")
- No meaningful error messages ("Invalid realm configuration" -- which part?)

You are programming, but without any of the tools that make programming safe.

---

## volta's answer: if it is logic, put it in code

volta refuses to create a "configuration language." The configuration is minimal:

| What | Where | Why |
|------|-------|-----|
| Database URL | `.env` | Differs per deployment, is a secret |
| OAuth credentials | `.env` | Differs per deployment, is a secret |
| Session signing key | `.env` | Differs per deployment, is a secret |
| Base URL | `.env` | Differs per deployment |
| App list + roles | `volta-config.yaml` | Differs per feature, is declarative |
| Session timeout | Java code | Is a design decision, not a setting |
| JWT algorithm | Java code | Is a security decision, not a preference |
| Max sessions | Java code | Is a design decision, not a setting |
| OIDC flow logic | Java code | Is complex logic, not configuration |

The test: "Does changing this value require understanding the system's behavior?" If yes, it belongs in code. If no (it is just a deployment detail), it belongs in configuration.

`DATABASE_URL=postgres://...` does not require understanding system behavior. Anyone can set it.

`maxDeltaTimeSeconds=43200` absolutely requires understanding brute-force detection algorithms. It belongs in code, where someone with that understanding writes it, tests it, and reviews it.

---

## In volta-auth-proxy

volta uses .env for secrets that differ per deployment, YAML for declarative app settings, and Java code for all behavioral logic -- deliberately keeping configuration minimal so that logic lives where compilers, tests, and code review can protect it.

---

## Further reading

- [config-hell.md](config-hell.md) -- What happens when configuration grows out of control.
- [complexity-of-configuration.md](complexity-of-configuration.md) -- The hidden costs of configurability.
- [yaml.md](yaml.md) -- The format volta uses for structured settings.
- [environment-variable.md](environment-variable.md) -- How .env files work.
