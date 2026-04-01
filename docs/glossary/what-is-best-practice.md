# What Is "Best Practice," Actually?

[日本語版はこちら](what-is-best-practice.ja.md)

---

## The misconception

"Follow best practices."

This is the advice that sounds helpful and is almost completely useless. It is the "eat healthy" of software engineering. Everyone nods. Nobody disagrees. And nobody can tell you exactly what it means for your specific situation.

When someone says "best practice," what they usually mean is: "something I read in a blog post, or saw in a conference talk, or learned at my previous company, and I am now applying it to a completely different context without questioning whether it still makes sense."

---

## What "best practice" actually means (and does not mean)

A best practice is a technique or approach that has been observed to produce good results in a specific context. That last part -- "in a specific context" -- is the part that gets dropped every time the term is used.

### Whose best practice?

Google's best practices are for Google-scale problems. Netflix's best practices are for Netflix-scale streaming. Facebook's best practices are for Facebook-scale social graphs. When a team of three engineers building a SaaS for 500 users adopts Google's infrastructure patterns, they are not following best practices. They are cosplaying as Google.

### From when?

"Use microservices" was a best practice in 2018. By 2023, half the industry was writing articles about how microservices had been over-applied and monoliths were fine, actually. "Never store state on the server" was a best practice in the early JWT era. Now we know that sessions have clear advantages for many use cases.

Best practices have an expiration date. The problem is they do not come with one.

### For what context?

"Always use HTTPS." That is a best practice that applies almost universally. "Always use Kubernetes." That is a best practice for companies with enough traffic and team size to justify the operational complexity. For a single-service deployment, it is overhead.

The more specific a best practice gets, the narrower its context of applicability. But people apply them as if they were universal laws.

---

## Why "best practice" is dangerous

The phrase "best practice" shuts down thinking. It converts a decision into an appeal to authority. Instead of asking "what should we do and why?", the conversation becomes "what does the industry do?" And the industry is not a single entity with coherent opinions. It is millions of engineers making contradictory choices based on different constraints.

Here is what happens in practice:

1. An engineer reads that "best practice" is to use Redis for session storage.
2. The team adds Redis to the stack.
3. Now they have two data stores to manage, monitor, and back up.
4. The sessions could have lived in PostgreSQL (which they already have) with zero additional operational cost.
5. But nobody questioned it, because it was "best practice."

The cost of this pattern is invisible. It manifests as additional infrastructure, more things to monitor, more things that can fail, more things to learn -- all justified by a phrase that sounds authoritative but provides no actual reasoning.

---

## volta's approach: understand WHY, then decide

volta-auth-proxy makes deliberate decisions and documents the reasoning. Not "we followed best practice" but "we chose X because of Y, and the tradeoff is Z."

**Example 1: Session storage in PostgreSQL, not Redis.**

The "best practice" says: use Redis for sessions because it is fast. volta says: PostgreSQL is already a dependency. Adding Redis adds another dependency, another failure mode, another thing to monitor. PostgreSQL can handle the session load for the target scale. The tradeoff is that PostgreSQL is slower than Redis for key-value lookups. But "slower" here means single-digit milliseconds vs. sub-millisecond -- a difference that does not matter when the total request time is dominated by network latency.

The reasoning is documented. You can disagree with it. But you cannot say it was not thought through.

**Example 2: Single IdP (Google OIDC) in Phase 1.**

The "best practice" says: support multiple identity providers from day one. volta says: supporting one IdP well is better than supporting five IdPs poorly. Google OIDC covers the majority of use cases for the target audience. Adding SAML, GitHub, Microsoft, and email/password in Phase 1 would multiply the attack surface, testing matrix, and configuration complexity. Phase 2 adds more IdPs, but Phase 1 does one thing and does it right.

**Example 3: No microservices.**

The "best practice" (circa 2018) says: break your application into microservices for independent scaling and deployment. volta says: this is a single-purpose authentication gateway. Breaking it into microservices would add inter-service communication, distributed tracing, and deployment coordination -- all to decompose something that has one clear responsibility and runs in 30MB of RAM.

---

## How to evaluate a "best practice"

Next time someone says "best practice," ask these questions:

1. **Best for whom?** What company or team first established this practice? What were their constraints?
2. **Best for what scale?** Is this a practice for 100 users or 100 million users? For a team of 3 or a team of 300?
3. **Best compared to what?** What are the alternatives? What are the tradeoffs of each?
4. **Best as of when?** When was this practice established? Has the tooling, ecosystem, or threat landscape changed since then?
5. **Best according to what evidence?** Is there data showing this practice produces better outcomes? Or is it just widely adopted?

A practice that survives all five questions is probably a good practice (not necessarily the best). A practice that fails any of them deserves scrutiny before adoption.

---

## The practices that actually are universal

To be fair, some practices are close to universal:

- Use HTTPS everywhere
- Hash passwords, never store them in plaintext
- Validate and sanitize all input
- Use parameterized queries (never concatenate SQL)
- Log security-relevant events
- Keep dependencies updated
- Have backups and test your restore process

These are universal because they cost little, protect against well-understood threats, and have no meaningful tradeoff. Everything else is contextual.

---

## It is OK not to know this

If you have been following "best practices" without asking why, you are in the overwhelming majority. The industry encourages this behavior. Job postings ask for "knowledge of best practices." Code reviews cite "best practices." Architecture documents mandate "best practices."

The next step is not to reject all best practices. It is to start asking "why?" before adopting them. The answer might be "because it genuinely solves our problem." Or it might be "because someone at a FAANG company said so five years ago, and we never questioned it."

Both answers are informative. Only one is a good reason to adopt the practice.

Now you know.
