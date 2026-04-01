# Native Implementation (Self-Built)

[日本語版はこちら](native-implementation.ja.md)

---

"Native" or "self-built" in software means you wrote it yourself instead of using someone else's solution. When volta-auth-proxy says it handles OIDC natively, it means the OIDC flow is implemented in volta's own code -- not delegated to Keycloak, not handled by an SDK, not proxied through a third-party service. The code lives in your repository, and you can read every line.

This essay is about when and why teams choose to build their own, and what it costs.

---

## Why some teams build their own auth

The decision to build auth in-house usually comes from one of three places:

**Frustration.** The team used Keycloak for six months and is drowning in configuration. Or they used Auth0 and just got a bill for $2,400/month. Or they tried to customize a login page and spent a week fighting FreeMarker templates. At some point, someone says: "We could build this ourselves in less time than we spend fighting this tool."

**Control.** The team needs something specific -- a particular multi-tenancy model, a specific invitation flow, a custom consent screen -- and the existing tools cannot do it without ugly workarounds. They want to own every decision.

**Understanding.** Authentication is critical infrastructure. Some teams believe that if you do not understand your auth system deeply enough to build it yourself, you do not understand it deeply enough to operate it safely. "If we cannot explain every line, we cannot trust every line."

volta was born from all three motivations.

---

## The pros of building your own

### Control

When you build your own auth, every decision is yours:

- Session timeout: you choose
- JWT claims: you choose
- Multi-tenancy model: you choose
- Login page: pixel-perfect control
- Security headers: you set them

There is no "sorry, that feature requires the Enterprise plan." There is no "this setting is not exposed in the configuration." There is just code, and code can do anything.

### Understanding

When you write the OIDC flow yourself, you understand it. Not "I read the docs" understanding, but "I implemented the state parameter and I know exactly why it prevents CSRF" understanding. This depth matters when things go wrong. A team that built their own auth can debug it at 3 AM. A team that configured someone else's auth reads Stack Overflow at 3 AM.

### Speed (of the system, not development)

Self-built auth can be optimized for your exact use case. volta starts in 200ms and uses 30MB of RAM because it only does what it needs to do. Keycloak starts in 30 seconds and uses 512MB because it does everything anyone might ever need.

### No vendor dependency

When you build your own, nobody can raise your prices, deprecate your features, or acquire your vendor and change the roadmap. Your auth system exists as long as you want it to exist.

---

## The cons of building your own

### Responsibility

This is the big one. When you build your own auth, you own every bug, every vulnerability, and every incident. There is no security team at Auth0 watching for CVEs in your JWT library. There is no Keycloak community releasing patches for the OIDC flow. There is just your team.

```
  Security vulnerability discovered in JJWT library:

  If you use Auth0:
  - Auth0's security team patches it
  - You do nothing

  If you use volta (self-built):
  - You see the CVE announcement
  - You evaluate the impact on your system
  - You update the dependency
  - You test the update
  - You redeploy
  - You verify the fix in production
```

This is real work. It requires vigilance. It requires someone on the team who reads security advisories.

### Bugs you create yourself

Auth0 has been tested by thousands of companies over years. Your self-built auth has been tested by your team over months. The probability that your implementation has a subtle bug (a timing attack, a race condition in session handling, a CSRF edge case) is higher than Auth0's probability of the same bug.

This does not mean self-built auth is insecure. It means self-built auth requires more testing, more review, and more humility. "I think this is secure" is not the same as "this has been penetration-tested by professionals."

### Maintenance

Auth is not a "build it and forget it" component. OAuth2 standards evolve. Browser security policies change. New attack vectors emerge. A self-built auth system requires ongoing maintenance -- not just feature development, but security hygiene.

---

## volta's philosophy: "choose the hell you understand"

Every option has downsides. There is no free lunch in authentication:

- **Auth0's hell:** Vendor lock-in, escalating costs, limited control, dependency on another company's roadmap.
- **Keycloak's hell:** Configuration complexity, resource heaviness, FreeMarker templates, realm management at scale.
- **Self-built hell:** Security responsibility, bug ownership, maintenance burden.

volta's argument is simple: **choose the hell you understand.**

If you build your own auth (using volta as a foundation), you understand every line of code. When something breaks, you can fix it. When a vulnerability is discovered, you can patch it. When a customer needs a feature, you can build it. The "hell" of self-built auth is real, but it is hell you can navigate because you understand the terrain.

The "hell" of Keycloak's 500-line realm.json is also real, but it is hell you cannot navigate because you do not understand what `quickLoginCheckMilliSeconds` does. The "hell" of Auth0's vendor lock-in is real, but it is hell you cannot escape because your code, your users, and your workflows are all intertwined with their platform.

At least with self-built auth, the problems are YOUR problems. You are not waiting for someone else to fix them. You are not paying someone else to fix them. You are not hoping someone else will fix them.

---

## volta as a foundation, not a blank page

It is important to note that volta is not "build auth from scratch." volta has already made the hard decisions:

- OIDC Authorization Code flow with PKCE: implemented and tested
- JWT issuance with RS256 and key rotation: implemented and tested
- Session management with sliding window expiry: implemented and tested
- Multi-tenant data model with role-based access: implemented and tested
- ForwardAuth proxy pattern: implemented and tested
- CSRF protection, rate limiting, audit logging: implemented and tested

When you use volta, you are not writing auth from zero. You are starting from a working, tested foundation and customizing it. The "native implementation" advantage is that you CAN customize it -- not that you MUST build everything from scratch.

This is the difference between:
- "Build auth from scratch" (months of work, high risk)
- "Use volta as a foundation and own the code" (days of setup, lower risk, full control)

---

## Further reading

- [self-hosting.md](self-hosting.md) -- The operational side of owning your auth.
- [security-responsibility.md](security-responsibility.md) -- What security ownership actually means.
- [tradeoff.md](tradeoff.md) -- The broader trade-off framework.
- [keycloak.md](keycloak.md) -- The alternative you might be escaping from.
