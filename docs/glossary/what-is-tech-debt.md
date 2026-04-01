# What Is Tech Debt, Actually?

[日本語版はこちら](what-is-tech-debt.ja.md)

---

## The misconception

"We have tech debt."

Everyone says it. It appears in sprint retrospectives, architecture reviews, and late-night Slack messages. It is used to explain why things are slow, why refactoring is needed, and why the codebase is hard to work with.

But ask someone to quantify their tech debt and you will get a blank stare. Ask them to list it, and you will get vague gestures at "the old code." Ask them to explain the interest rate, and they will not know what you mean.

Tech debt is the most overused and least understood metaphor in software engineering.

---

## What tech debt actually is

Ward Cunningham, who coined the term, described it as a financial metaphor: just like financial debt, technical debt is a deliberate decision to take a shortcut now, with the understanding that you will pay interest on that decision until you pay it back.

The key words are **deliberate** and **interest.**

**Deliberate:** Tech debt is a conscious decision to defer a better solution. "We will use a simple in-memory rate limiter now, knowing we will need a distributed one when we scale to multiple instances." That is tech debt. "We wrote spaghetti code because we were in a hurry and did not think about design" is not tech debt. That is just bad code. The distinction matters because tech debt implies a plan to repay, while bad code implies a problem you did not know you were creating.

**Interest:** Every deferred decision has an ongoing cost. The in-memory rate limiter works fine for one instance. But every month it remains in place, the team builds more features on top of the single-instance assumption. When the time comes to go multi-instance, the migration is harder than it would have been if done earlier. That increasing difficulty is the interest.

---

## The four types of tech debt

Not all tech debt is created equal:

### 1. Deliberate and prudent
"We know this is not the final architecture, but it is good enough for now and we have documented what needs to change later."

This is healthy tech debt. It is a tool for moving fast with clear eyes. volta's entire Phase 1 strategy is deliberate, prudent tech debt.

### 2. Deliberate and reckless
"We do not have time to do it right. Ship it."

This creates debt with high interest and no repayment plan. It accumulates fast and compounds faster.

### 3. Inadvertent and prudent
"Now that we have built it, we realize there was a better approach."

This is unavoidable. You learn by building. The key is recognizing it when it happens and deciding whether to pay it down.

### 4. Inadvertent and reckless
"What is a design pattern?"

This is not debt. This is a skills gap. The solution is education, not refactoring.

---

## volta's explicit tech debt list

This is what makes volta unusual. Most projects accumulate tech debt silently. volta documents it explicitly under "Phase 1 intentionally not doing." Here is the actual list of deferred decisions and their known interest:

**Single-instance architecture.**
- Decision: Run one volta instance behind Traefik.
- Interest: No high availability. If volta goes down, all ForwardAuth fails.
- Repayment plan: Phase 2 adds multi-instance support. PostgreSQL sessions already work across instances. The main work is distributed rate limiting and coordinated JWKS cache invalidation.

**In-memory rate limiting.**
- Decision: Rate limit counters live in the JVM process.
- Interest: Rate limits do not survive restarts. Multiple instances would each have separate counters.
- Repayment plan: Move to Redis or PostgreSQL-backed rate limiting when multi-instance support is added.

**Single IdP (Google OIDC).**
- Decision: Support only Google as an identity provider in Phase 1.
- Interest: Users who need GitHub, Microsoft, SAML, or email/password cannot use volta yet.
- Repayment plan: Phase 2 adds additional IdPs. The OIDC flow is abstracted enough that adding providers is configuration, not architecture change.

**No email sending.**
- Decision: Invitations are shared via link/QR code, not email.
- Interest: Invitation workflow requires manual link sharing. Less polished user experience.
- Repayment plan: Phase 2 adds SMTP/SendGrid integration for invitation emails.

**No automated penetration testing.**
- Decision: Security measures are implemented by design but not validated by external adversarial testing.
- Interest: Unknown vulnerabilities may exist. "We designed it securely" is weaker than "it was tested and survived."
- Repayment plan: Phase 3 includes professional penetration testing.

**5-minute JWT revocation lag.**
- Decision: JWTs are valid for 5 minutes. Session revocation does not invalidate already-issued JWTs.
- Interest: A revoked user can still access downstream apps for up to 5 minutes.
- Repayment plan: Acceptable for Phase 1 target audience. Phase 2 could add a revocation list cache, but the tradeoff (added complexity vs. 5-minute window) may not be worth it.

Each item has three parts: the decision, the interest, and the repayment plan. That is what real tech debt management looks like.

---

## Why acknowledging debt is better than pretending

Most projects handle tech debt in one of two ways:

**Denial:** "We do not have tech debt. The code is fine." This leads to surprise when the debt comes due. The team planned for multi-instance support but never estimated the work because they never acknowledged the gap.

**Panic:** "We have so much tech debt we need to stop everything and refactor." This leads to multi-month refactoring projects that deliver no user value and often introduce new bugs.

volta takes a third approach: **explicit acknowledgment with deferred repayment.**

"Here is our tech debt. Here is the interest rate. Here is when we plan to pay it back. Here is what happens if we do not."

This approach has several advantages:
- New team members understand the known limitations immediately.
- Product decisions can account for the interest cost.
- Repayment can be planned and prioritized alongside feature work.
- Nobody is surprised when a limitation is hit.

---

## How to talk about tech debt

Next time you say "we have tech debt," try being specific:

Instead of: "The auth system has tech debt."
Try: "The rate limiter is in-memory, which means it does not survive restarts and will not work across multiple instances. The interest is that we cannot scale horizontally until we fix it. The repayment cost is approximately 2 weeks of engineering time to move to Redis-backed rate limiting."

That is a conversation you can have with a product manager, a CTO, or a new hire. "We have tech debt" is not.

---

## It is OK not to know this

If you have been using "tech debt" to mean "code I do not like" or "things we should fix someday," you are using the term the way most of the industry uses it -- which is to say, imprecisely.

The original metaphor is powerful because it gives you a framework for making and communicating tradeoffs. A decision is not good or bad. It has a cost (the debt), an ongoing cost (the interest), and a plan (the repayment). When you frame decisions this way, "tech debt" stops being a vague complaint and becomes a management tool.

Now you know.
