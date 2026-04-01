# Build Only What You Need

[日本語版はこちら](build-only-what-you-need.ja.md)

---

There is a seductive idea in software engineering that goes like this: "If we just think hard enough, plan carefully enough, and design comprehensively enough, we can build the perfect system on the first try." This idea has a long and distinguished history of producing failed projects.

---

## The grand plan trap

Every engineer has been in this meeting:

> "Before we build anything, let's make sure we've thought of everything. We need to support OIDC, SAML, LDAP, MFA, SCIM, webhooks, billing integration, i18n, mobile SDKs, and a policy engine. Let's design the architecture to handle all of this from day one."

This feels responsible. Thorough. Professional. It is also how projects die.

Here is what happens next:

1. The architecture becomes complex because it must accommodate 10 features simultaneously
2. Each feature adds constraints on every other feature ("but what about SAML? We need to account for that in the session model")
3. Development slows because every decision has cascading effects across the grand design
4. Six months in, nothing works end-to-end because everything depends on everything else
5. The team argues about hypothetical edge cases in features that no user has asked for
6. Motivation drops. "When will this ship?"
7. Either the project gets canceled or it ships as a buggy mess with none of the 10 features working properly

I have seen this pattern destroy projects worth millions of dollars. The problem is not bad engineers -- the problem is trying to solve too many problems at once.

---

## YAGNI: You Ain't Gonna Need It

YAGNI is an acronym from Extreme Programming that states: do not add functionality until you actually need it. Not "until you think you might need it." Not "until a customer mentions it in passing." Not "until a competitor has it." Until you actually, right now, today, need it.

YAGNI is not about being lazy. It is about being honest. Honest about what you know (very little about the future) and what you do not know (almost everything about the future).

Consider this: how many features have you built that were never used? If you are honest, the answer is "many." Every unused feature was time that could have been spent on features that matter.

---

## volta's approach: Phase 1 is minimal, Interfaces for the future

volta-auth-proxy embodies the "build only what you need" philosophy through its phased roadmap and its use of interfaces.

### What Phase 1 includes (and nothing more)

Phase 1 is exactly what a multi-tenant SaaS needs on day one:

- Google OIDC login (because every SaaS starts with Google login)
- Sessions (because users need to stay logged in)
- Multi-tenancy (because this is a multi-tenant SaaS)
- Roles (because not everyone should be an admin)
- Invitations (because teams need to add members)
- ForwardAuth (because apps need to know who the user is)
- Internal API (because apps need to manage users and tenants)

That is it. No SAML. No LDAP. No SCIM. No billing. No mobile SDK.

### What Phase 1 does NOT include (and why)

| Not included | Why not |
|-------------|---------|
| SAML SSO | You do not have enterprise customers yet |
| LDAP/AD | You are not selling to Fortune 500 companies yet |
| MFA/TOTP | Google handles MFA for Google login |
| SCIM | Your customers do not have 10,000 employees yet |
| Billing | You need auth before you need billing |
| Mobile SDK | You have not built a mobile app yet |
| Policy engine | Your RBAC is OWNER > ADMIN > MEMBER > VIEWER |

None of these things are needed on day one. All of them are planned for later phases. And the architecture supports adding them because volta designed for it:

```java
// Phase 1: The interface exists. Only Postgres implementation ships.
interface SessionStore {
    void createSession(...);
    Optional<SessionRecord> findSession(...);
    void touchSession(...);
    void revokeSession(...);
    // ...
}

// Phase 1: Only this implementation exists
class PostgresSessionStore implements SessionStore { ... }

// Phase 2: Redis implementation drops in with zero auth logic changes
class RedisSessionStore implements SessionStore { ... }
```

The interface was designed in Phase 1. The Redis implementation was built in Phase 2. The auth logic never changed. This is the key insight: **you can prepare for the future without building the future.**

---

## Ship something that works, then grow

The most important thing about Phase 1 is that it works. Not "it kind of works." Not "it works for demos." It works in production. Users can log in, create tenants, invite members, manage roles, and use applications. The system is secure (PKCE, signed sessions, RS256 JWTs, CSRF protection). The system is tested.

This matters because:

1. **Real feedback comes from real usage.** You discover what Phase 2 needs by running Phase 1 in production. Maybe you discover Redis sessions are not needed until you hit 10,000 concurrent users. Maybe you discover SAML is needed sooner than expected because a key customer requires it.

2. **Momentum builds confidence.** A team that has shipped Phase 1 believes they can ship Phase 2. A team that has been designing for six months believes nothing will ever ship.

3. **Simplicity is debuggable.** When Phase 1 has a bug, there are only a few places to look. When the grand design has a bug, it could be anywhere in ten interconnected systems.

4. **Users do not care about your roadmap.** They care about what works today. Phase 1 works today.

---

## The waterfall trap revisited

The waterfall model says: gather all requirements, design everything, build everything, test everything, deploy. It works for building bridges (where requirements do not change mid-construction). It fails for software (where requirements change constantly).

Phase-based development is not waterfall in disguise. The difference is:

```
  Waterfall:
  [Gather ALL requirements] → [Design EVERYTHING] → [Build EVERYTHING] → [Ship]
  ────────────────────────────────────────────────────────────────────────────►
  12+ months before anything works

  Phase-based:
  [Phase 1: core requirements] → [Build] → [Ship] → [Learn]
  ──────────────────────────────────────────────────────────►
  Weeks. Something works.

      [Phase 2: scaling needs] → [Build] → [Ship] → [Learn]
      ──────────────────────────────────────────────────────►
      Months later. More works.

          [Phase 3: enterprise needs] → [Build] → [Ship]
          ──────────────────────────────────────────────►
          When customers need it. Not before.
```

Each phase completes the feedback loop. You ship, you learn, you plan the next phase based on what you learned. This is slower than the grand plan *in theory*. In practice, it is much faster, because the grand plan never actually ships on time.

---

## Practical advice

If you are designing a system and feel the urge to "think of everything":

1. **Write down everything you think you need.** Get it out of your head.
2. **Circle the things a user needs in week one.** Be ruthless.
3. **Everything else goes on the "later" list.** Not "never." Just "later."
4. **Design interfaces** for the things on the "later" list. This prepares the architecture without writing implementation code.
5. **Ship the circled items.** Fast. Working. Tested.
6. **Revisit the "later" list after shipping.** You will be surprised how many items are no longer relevant.

This is how volta was built. And it works.

---

## Further reading

- [phase-based-development.md](phase-based-development.md) -- volta's 4-phase roadmap in detail.
- [interface-extension-point.md](interface-extension-point.md) -- How volta prepares for the future without building it.
- [tradeoff.md](tradeoff.md) -- The trade-offs of building less.
- [minimum-viable-architecture.md](minimum-viable-architecture.md) -- The smallest architecture that works.
