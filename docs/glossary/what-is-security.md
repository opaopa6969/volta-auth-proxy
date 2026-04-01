# What Is Security, Actually?

[日本語版はこちら](what-is-security.ja.md)

---

## The misconception

"Security is important." Everyone says it. It appears in every README, every pitch deck, every job posting. And almost nobody who says it can tell you what they actually mean.

Here is the uncomfortable truth: most people treat security like a feature. Something you add. A checkbox on a spreadsheet. "Does it have security? Yes. Check." And then they move on to the next feature.

That is not security. That is security theater.

---

## What security actually is

Security is not a feature. It is a continuous practice. It is closer to hygiene than to a product. You do not "add" hygiene to your life once and declare it done. You brush your teeth every day. You wash your hands before eating. You do it because the consequences of not doing it accumulate silently and then hit you all at once.

Security works the same way. The question is never "is this system secure?" The answer to that question is always "no" -- because security is not a binary state. The real questions are:

- **What are we protecting?** (User data, session tokens, private keys)
- **From whom?** (External attackers, compromised insiders, accidental leaks)
- **What happens if we fail?** (Data breach, regulatory fines, destroyed trust)
- **What are we doing about it today?** (Not last year. Today.)

Security is the practice of continuously answering these questions and acting on the answers.

---

## Security vs. security theater

Security theater is when you do things that look like security but do not actually protect anything meaningful. Some examples:

| Security theater | Actual security |
|---|---|
| Requiring passwords with uppercase + number + symbol | Rate-limiting login attempts to 10/min per IP |
| Showing a padlock icon on your login page | Using PKCE + state + nonce on every OIDC flow |
| "We use encryption" (without saying what, where, how) | AES-256-GCM encrypting private keys at rest, RS256 for JWT signing, rejecting HS256/none algorithms |
| Putting "SOC2 compliant" on your landing page | Actually logging every auth event and reviewing the logs |

The difference is specificity. Security theater speaks in generalities. Actual security speaks in specifics.

---

## What volta actually does (specifics, not vibes)

volta-auth-proxy does not claim to be "secure." It claims to do specific things:

**Identity verification:** Google OIDC with PKCE (S256) + state parameter (CSRF prevention) + nonce (replay attack prevention). Three separate protections on a single login flow. Not because it looks good, but because each one prevents a different class of attack.

**Session management:** Signed cookies. 8-hour sliding window. Session ID regenerated on every login (prevents session fixation). Maximum 5 concurrent sessions per user (limits blast radius of credential theft).

**Token security:** RS256 only. HS256 and `none` algorithm explicitly rejected via allowlist. JWTs expire in 5 minutes. Private keys encrypted at rest with AES-256-GCM. Key rotation API with graceful overlap period.

**Abuse prevention:** Rate limiting at 10 requests/minute per IP for login endpoints. 200 requests/minute per user for API endpoints. Cache-Control headers set to `no-store, private` on all auth endpoints to prevent back-button data leaks.

**Audit trail:** Every authentication event logged -- login, logout, role change, invitation acceptance, session revocation.

Each of these is a specific, verifiable claim. You can read the code and confirm it. That is the difference between security and security theater.

---

## The practice, not the feature

Here is what security looks like as a daily practice for a project like volta:

1. **Dependency monitoring.** volta depends on JJWT, Javalin, HikariCP, and others. Each has potential vulnerabilities. Watching for CVEs and updating promptly is security. Ignoring `dependabot` alerts is not.

2. **Protocol awareness.** OAuth2 and OIDC have known attack vectors. New ones get discovered. When a new attack against PKCE is published, someone needs to evaluate whether volta is affected. That someone is you, the operator.

3. **Incident readiness.** If a user reports a compromised account, what do you do? volta provides the tools (session revocation, audit logs, all-device logout), but having the tools is not the same as having a plan for using them.

4. **Honest assessment.** volta does not have runtime anomaly detection. It does not have automated penetration testing. It does not have a dedicated security team reviewing every commit. These are gaps. Knowing your gaps is security. Pretending you have no gaps is security theater.

---

## The thing people get wrong

The biggest security mistake is not a technical failure. It is the belief that security is someone else's problem. "The framework handles it." "The cloud provider handles it." "The auth library handles it."

Nobody handles it. Tools help. Libraries help. But security is a responsibility, not a product. When you choose a self-hosted auth solution like volta instead of Auth0, you are not choosing less security. You are choosing to own the security yourself. That is a legitimate choice -- but only if you understand what you are signing up for.

---

## It is OK not to know this

If you thought security was a feature you could add and check off, you are not alone. Most engineers think that way early in their careers, because that is how it gets talked about. "Add security to the app." "The app needs security." The language itself is misleading.

Now you know: security is not a thing you have. It is a thing you do. Every day. And the first step is being honest about what you are actually doing versus what you are pretending to do.

Now you know.
