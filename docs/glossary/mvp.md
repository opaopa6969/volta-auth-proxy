# MVP (Minimum Viable Product)

[日本語版はこちら](mvp.ja.md)

---

## What is it?

MVP stands for Minimum Viable Product. It is the simplest version of a product that solves the core problem well enough to be usable by real people. Not a prototype. Not a demo. A real product -- just with the smallest possible feature set.

Think of it like opening a restaurant. An MVP restaurant does not need a Michelin-star menu, a wine cellar, and valet parking. It needs good food, a clean table, and a way to take payment. If people keep coming back for the food, you know you have something worth expanding. If they do not, you saved yourself the cost of a wine cellar nobody wanted.

The word "viable" is the key. An MVP must actually work. A login page that does not log anyone in is not an MVP -- it is a broken product. An MVP has fewer features, but the features it has must work correctly and reliably.

---

## Why does it matter?

Building software is expensive, and the biggest risk is building the wrong thing. MVPs reduce this risk by getting a real product in front of real users as fast as possible. You learn more from one week of real usage than from six months of planning.

The alternative -- spending months building a full-featured product before anyone uses it -- is how most startups fail. They build what they think users want, launch with a big bang, and discover that users wanted something different.

MVPs also force prioritization. When you can only build five features, you must decide which five matter most. This clarity carries forward even after the MVP phase.

---

## How does it work?

### The MVP process

```
  Step 1: Identify the core problem
          "Users need to authenticate via Google
           and manage team members"

  Step 2: List ALL features you could build
          - Google login
          - GitHub login
          - Email/password login
          - Team management
          - Role-based access
          - Audit logging
          - SSO/SAML
          - API key management
          - Billing integration
          - Admin dashboard
          ...

  Step 3: Draw the MVP line
          ─────────────────────────────────
          ABOVE: Build now (MVP)
          - Google login
          - Team management (invite, remove)
          - Role-based access (OWNER, MEMBER)
          ─────────────────────────────────
          BELOW: Build later (if validated)
          - Everything else
          ─────────────────────────────────

  Step 4: Build, ship, learn
          │
          ▼
       Ship MVP ──► Real users try it
          │                │
          ▼                ▼
       Measure ◄──── Feedback
          │
          ▼
       Decide: expand, pivot, or stop
```

### What makes a good MVP

| Good MVP | Bad MVP |
|----------|---------|
| Solves one problem completely | Solves many problems partially |
| Works reliably | "Works on my machine" |
| Has error handling | Crashes on bad input |
| Is deployable by users | Requires the developer to set it up |
| Has clear boundaries ("we do X, not Y") | Promises everything, delivers nothing |

### MVP vs. prototype vs. proof of concept

| Concept | Audience | Quality | Purpose |
|---------|----------|---------|---------|
| **Proof of concept** | Developers | Throwaway | "Can this even work?" |
| **Prototype** | Stakeholders | Low | "Is this the right UX?" |
| **MVP** | Real users | Production | "Does this solve a real problem?" |

---

## How does volta-auth-proxy use it?

volta-auth-proxy is itself an MVP. It is Phase 1 of a larger vision, deliberately scoped to solve one problem well: multi-tenant authentication for small teams.

### What is in volta's MVP

| Feature | Status | Why it is in the MVP |
|---------|--------|---------------------|
| Google OIDC login | Built | Core auth flow -- the whole point |
| Tenant management | Built | Multi-tenancy is the differentiator |
| Role-based access (OWNER/MEMBER) | Built | Minimum viable access control |
| Invitation system | Built | Teams need to add members |
| Session management | Built | Security baseline |
| CSRF protection | Built | Security is not optional |
| Health check endpoint | Built | Deployability requirement |
| DSL-based configuration | Built | Core developer experience |

### What is NOT in volta's MVP

| Feature | Why it is excluded |
|---------|-------------------|
| GitHub/Azure/Okta login | Google OIDC proves the model; others come in Phase 2 |
| PostgreSQL support | SQLite is enough for small teams |
| High availability | Single-node is fine for the target audience |
| SOC2 compliance | Enterprise requirement, not indie hacker requirement |
| SLA guarantees | No paying customers yet, no SLA needed |
| Billing integration | Stripe webhooks planned for Phase 2 |
| Admin dashboard UI | API-first; UI comes later |

### The MVP validation loop

volta uses feedback from early adopters to decide what to build next:

```
  volta Phase 1 (MVP)
        │
        ▼
  Ship to indie hackers / internal tool builders
        │
        ▼
  Collect signals:
    - What do they ask for first?
    - Where do they get stuck?
    - What do they work around?
        │
        ▼
  Phase 2 scope = top requests + unblocking fixes
```

---

## Common mistakes and attacks

### Mistake 1: MVP means low quality

This is the most common misunderstanding. MVP means fewer features, not lower quality. A login system that sometimes fails is not an MVP -- it is a bug. An MVP has fewer login options (Google only, not Google + GitHub + SAML), but the ones it has work perfectly.

### Mistake 2: The feature creep trap

"We just need one more feature before we can launch." This sentence has delayed more products than any technical challenge. The MVP scope should be locked early and defended aggressively.

### Mistake 3: Building an MVP without talking to users

An MVP built on assumptions is still a guess. Talk to five potential users before writing code. volta's [DGE process](https://github.com/your-org/volta-auth-proxy) (Dialogue-driven Gap Extraction) is designed to surface real requirements through structured conversation.

### Mistake 4: Treating MVP as the final product

An MVP is a starting point, not a destination. If your MVP succeeds, you must invest in expanding it. If you keep running on MVP quality forever, users will outgrow you.

---

## Further reading

- [yagni.md](yagni.md) -- The principle behind MVP scope decisions.
- [build-only-what-you-need.md](build-only-what-you-need.md) -- volta's philosophy of phase-minimal development.
- [The Lean Startup](https://theleanstartup.com/) by Eric Ries -- The book that popularized MVP thinking.
- [health-check.md](health-check.md) -- Even an MVP needs operational basics.
