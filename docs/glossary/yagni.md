# YAGNI (You Aren't Gonna Need It)

[日本語版はこちら](yagni.ja.md)

---

## What is it?

YAGNI stands for "You Aren't Gonna Need It." It is a software development principle that says: do not build a feature until you actually need it. Not when you think you might need it someday. Not when it would be "nice to have." Only when there is a real, present requirement.

Imagine you are packing for a weekend camping trip. YAGNI says: bring a tent, sleeping bag, and food. Do not bring a kayak, a portable generator, and a satellite dish "just in case." You can always go back for those things if your camping trip evolves into something bigger. But if you load the car with everything you might ever need, you will never leave the driveway.

The opposite of YAGNI is speculative generality -- building abstractions and features for imagined future requirements. This is one of the most common ways software projects become bloated, late, and impossible to maintain.

---

## Why does it matter?

Every feature you build has costs that go far beyond the initial development time:

- **Maintenance cost**: Every line of code is a liability. It must be tested, documented, debugged, and kept compatible with future changes.
- **Complexity cost**: Unused features make the codebase harder to understand. New developers must learn code that serves no purpose.
- **Opportunity cost**: Time spent building speculative features is time not spent on features users actually need right now.
- **Decision paralysis**: Over-engineering creates more decisions. More configuration. More things that can break.

Studies consistently show that a large percentage of planned features are never used. Building them is pure waste.

---

## How does it work?

### The YAGNI decision process

```
  New feature idea arrives
        │
        ▼
  Is there a concrete, current requirement?
        │
   ┌────┴────┐
   No        Yes
   │          │
   ▼          ▼
  STOP.     Is it the simplest solution
  Write     that meets the requirement?
  it down       │
  for later  ┌──┴──┐
             No    Yes
             │      │
             ▼      ▼
         Simplify  BUILD IT
         first
```

### What YAGNI is NOT

YAGNI does not mean:

- **Skip good architecture.** Clean code and separation of concerns are always needed.
- **Ignore security.** Authentication, authorization, and input validation are current requirements, not speculative ones.
- **Avoid all planning.** You should think ahead -- just do not build ahead.
- **Write throwaway code.** YAGNI code should still be well-structured. It is just smaller.

### YAGNI vs. extensibility

The nuance is: write code that is easy to extend later, without actually extending it now.

```
  BAD (speculative):
    PluginManager + PluginLoader + PluginRegistry
    + AbstractPlugin + PluginConfig + PluginLifecycle
    ... for a system that currently has 0 plugins

  GOOD (YAGNI):
    A single class that does the one thing needed.
    If plugins become a real requirement, refactor then.
    The refactor will be informed by actual needs,
    not imagined ones.
```

---

## How does volta-auth-proxy use it?

volta-auth-proxy is built around YAGNI as a core design philosophy, called **phase-minimal design**. The idea: build only what the current phase requires, and build it well.

### Concrete examples in volta

| Feature | YAGNI approach | Enterprise approach |
|---------|---------------|-------------------|
| **Auth provider** | Google OIDC only (Phase 1) | Abstract provider interface + 5 providers |
| **Database** | SQLite via single file | PostgreSQL cluster + read replicas |
| **Session store** | Same SQLite database | Redis cluster + failover |
| **Config format** | YAML file, read at startup | Dynamic config service + hot reload |
| **Multi-tenancy** | Single-process, in-memory tenant map | Separate databases per tenant |
| **API versioning** | `/api/v1/` only | Version negotiation + deprecation framework |

### The phase-minimal philosophy

volta explicitly defines phases. Each phase has a clear scope:

- **Phase 1**: Single Google OIDC provider, SQLite, single-node deployment. Enough for indie hackers and internal tools.
- **Phase 2**: Additional OIDC providers, PostgreSQL option, basic HA.
- **Phase 3**: Enterprise features (SOC2, SLA, SSO).

Phase 2 features do not exist in the Phase 1 codebase. They are not stubbed out, not hidden behind feature flags, not waiting in abstract interfaces. They simply do not exist yet.

### Why this works for volta

volta's target users -- indie hackers and early startups -- do not need enterprise features. Building those features would:

1. Slow down development of features they actually need
2. Add configuration complexity that scares away the target audience
3. Increase the attack surface unnecessarily
4. Make the ~200ms startup time impossible

---

## Common mistakes and attacks

### Mistake 1: Confusing YAGNI with laziness

YAGNI is a disciplined approach to scope management. It requires actively saying "no" to features. Laziness is skipping work that is actually needed -- like error handling, logging, or tests.

### Mistake 2: Using YAGNI to skip security

"We don't need CSRF protection yet" is not YAGNI -- it is negligence. If your application handles user sessions, CSRF protection is a current requirement. YAGNI applies to features, not to security hygiene.

### Mistake 3: Premature abstraction

Building an `AbstractAuthProviderFactory` when you only have one auth provider is the classic anti-YAGNI move. You will guess wrong about the abstraction, and then you are stuck with a bad abstraction that is harder to change than no abstraction.

### Mistake 4: Gold-plating

Adding "just one more thing" to a feature before shipping it. The feature works, but you add caching, retry logic, batch processing, and a dashboard -- none of which anyone asked for.

---

## Further reading

- [build-only-what-you-need.md](build-only-what-you-need.md) -- volta's specific implementation of YAGNI principles.
- [Martin Fowler on YAGNI](https://martinfowler.com/bliki/Yagni.html) -- The original explanation from the XP community.
- [Extreme Programming Explained](https://www.amazon.com/Extreme-Programming-Explained-Embrace-Change/dp/0321278658) by Kent Beck -- Where YAGNI was first formalized.
- [mvp.md](mvp.md) -- How MVP thinking complements YAGNI.
