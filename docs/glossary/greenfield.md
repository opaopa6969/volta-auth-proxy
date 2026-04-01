# Greenfield

[日本語版はこちら](greenfield.ja.md)

---

## What is it?

A greenfield project is a software project built from scratch, with no existing codebase, no legacy constraints, and no backward compatibility requirements. You start with an empty folder and make every decision fresh.

The term comes from construction. A greenfield site is an undeveloped plot of land -- literally a green field -- where builders can design anything without worrying about existing structures, plumbing, or zoning compromises. The opposite is a brownfield site: land that was previously developed, possibly contaminated, where you must work around what already exists.

In software, greenfield means total freedom. You choose the language, the framework, the database, the architecture. No old decisions constrain you. This is both the opportunity and the danger -- freedom without discipline leads to the same mess you were trying to escape.

---

## Why does it matter?

Greenfield opportunities are rare and valuable. Most software work is brownfield: maintaining, extending, or refactoring existing systems. When you get a greenfield start, the decisions you make in the first weeks will shape the project for years.

This is why greenfield projects demand more discipline, not less. In a brownfield project, the architecture is already decided (for better or worse). In a greenfield project, every bad early decision becomes the legacy that future developers curse.

The key question for any greenfield project is: "How do we make sure this codebase is still clean in two years?" The answer usually involves: small scope (MVP/YAGNI), strong conventions, and resisting the urge to over-engineer.

---

## How does it work?

### Greenfield vs. brownfield comparison

| Aspect | Greenfield | Brownfield |
|--------|-----------|------------|
| Starting point | Empty folder | Existing codebase |
| Technology choice | Unlimited | Constrained by existing stack |
| Architecture | Design from scratch | Work within existing patterns |
| Risk | Building the wrong thing | Breaking the existing thing |
| Speed (initial) | Fast -- no constraints | Slow -- must understand existing code |
| Speed (6 months) | Depends on early decisions | Depends on tech debt level |
| Team knowledge | Everyone starts equal | Tribal knowledge matters |

### The greenfield lifecycle

```
  Phase 1: Euphoria (Week 1-4)
  ┌──────────────────────────────┐
  │  "We can do anything!"       │
  │  "No legacy constraints!"    │
  │  "Let's use the latest..."   │
  └──────────────────────────────┘
              │
              ▼
  Phase 2: Decisions (Week 2-8)
  ┌──────────────────────────────┐
  │  Language? Framework? DB?    │
  │  Architecture? Deployment?   │
  │  Testing strategy? CI/CD?   │
  └──────────────────────────────┘
              │
              ▼
  Phase 3: Reality (Month 2-6)
  ┌──────────────────────────────┐
  │  Early decisions are now     │
  │  constraints. The greenfield │
  │  is becoming a brownfield.   │
  └──────────────────────────────┘
              │
              ▼
  Phase 4: The new legacy (Month 6+)
  ┌──────────────────────────────┐
  │  "Why did we do it this way?"│
  │  This is now a brownfield    │
  │  project for the next person.│
  └──────────────────────────────┘
```

### Common greenfield traps

**The "use everything new" trap**: Greenfield is not an excuse to adopt every shiny technology. New language + new framework + new database + new deployment platform = five things you do not understand, simultaneously.

**The "design it perfectly" trap**: Spending weeks on architecture before writing a single line of code. You do not have enough information to design perfectly. Ship something, learn, and refine.

**The "we'll clean it up later" trap**: Writing messy code because "it's just a prototype." Greenfield code written in week one is still in production three years later.

---

## How does volta-auth-proxy use it?

volta-auth-proxy is a greenfield project. It was started from scratch with deliberate technology choices informed by the author's experience with brownfield identity systems.

### Why greenfield was the right call

The alternative to building volta would have been extending an existing identity platform (Keycloak, Auth0 self-hosted, etc.). This brownfield approach was rejected because:

| Brownfield option | Problem |
|-------------------|---------|
| Extend Keycloak | 30-second startup, XML config hell, Java EE complexity |
| Fork an existing proxy | Inherit architecture decisions you disagree with |
| Wrap Auth0/Firebase | Vendor lock-in, no control over auth logic |

Starting fresh allowed volta to make opinionated choices:

- **Javalin over Spring Boot**: Lightweight, explicit, ~200ms startup vs ~5s
- **SQLite over PostgreSQL**: Zero ops for Phase 1
- **Single-process over microservices**: Debug in one place
- **DSL over config files**: Type-safe, version-controlled policy

### Greenfield discipline in volta

volta avoids the common greenfield traps through its design philosophy:

1. **YAGNI / phase-minimal**: Do not build Phase 2 features in Phase 1. Prevents the "use everything" trap.
2. **Tight coupling on purpose**: "Choose the hell you understand." Prevents over-abstraction.
3. **Control is king**: Every line of auth logic is written, not generated or imported from a framework. Prevents the "magic framework" trap.

### The brownfield future

volta will eventually become a brownfield project. The discipline applied now -- clean code, clear boundaries, thorough documentation -- is an investment for that future.

---

## Common mistakes and attacks

### Mistake 1: Treating greenfield as permission to experiment

A greenfield project is a production system from day one. Experimental technology choices are fine for a proof of concept, not for the foundation of a product.

### Mistake 2: Ignoring brownfield realities

Even greenfield projects have constraints: the team's skills, the deployment environment, the users' expectations. Choosing Haskell for a team of Java developers is greenfield thinking gone wrong.

### Mistake 3: Not documenting decisions

In a brownfield project, the code documents decisions (however poorly). In a greenfield project, there is no history. If you do not write down why you chose Javalin over Spring Boot, the next developer will wonder and possibly rewrite.

### Mistake 4: Scope explosion

"Since we're starting fresh, let's build it right this time" quickly becomes "let's build everything." Greenfield projects need even stricter scope control than brownfield ones. See [yagni.md](yagni.md).

---

## Further reading

- [yagni.md](yagni.md) -- Scope discipline for greenfield projects.
- [mvp.md](mvp.md) -- How to scope a greenfield project's first release.
- [javalin.md](javalin.md) -- volta's greenfield framework choice.
- [startup.md](startup.md) -- Why startup time mattered in volta's greenfield decisions.
