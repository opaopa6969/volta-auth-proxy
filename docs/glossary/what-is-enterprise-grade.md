# What Is "Enterprise-Grade," Actually?

[日本語版はこちら](what-is-enterprise-grade.ja.md)

---

## The misconception

"Is it enterprise-grade?"

Most engineers hear this question and think it means "is the code good enough?" It does not. Enterprise-grade has almost nothing to do with code quality. Some of the worst code in the world runs in enterprise environments, and some of the best code in the world would never pass an enterprise procurement process.

Enterprise-grade is not a technical standard. It is a trust and liability framework.

---

## What enterprise-grade actually means

When a large organization evaluates whether to adopt software, they are not primarily asking "does it work?" They are asking:

**"If something goes wrong, who is responsible?"**

That question drives everything. And the answer needs to be documented, contractual, and legally enforceable.

### The pillars of enterprise-grade

**1. Compliance certifications (SOC2, ISO 27001, HIPAA, etc.)**

SOC2 is not a technical standard. It is an audit that verifies your organization has controls for security, availability, processing integrity, confidentiality, and privacy. An auditor comes to your company, reviews your processes, interviews your team, and writes a report saying "yes, they do what they claim to do."

This costs money ($50,000-$200,000 for the initial audit), takes months, and requires organizational processes that go far beyond code quality: employee background checks, access control policies, incident response procedures, vendor management, change management workflows.

The code could be identical to a hobby project. The certification makes it enterprise-grade.

**2. SLAs (Service Level Agreements)**

An SLA is a contractual promise: "we guarantee 99.9% uptime, and if we fail to deliver it, here is the financial penalty we will pay you." Enterprise customers need this because they are building their own SLAs on top of yours. If your auth system goes down and their product goes down, they need someone to point to in the post-mortem.

Providing an SLA requires: redundancy, monitoring, alerting, on-call rotations, incident response procedures, and the financial backing to absorb penalties. It is an organizational capability, not a code feature.

**3. Support contracts**

When something breaks at 2 AM, can the customer call someone? Is there a guaranteed response time? A dedicated support engineer? An escalation path to the development team?

Enterprise support is not "open a GitHub issue." It is "call this number, a human answers within 30 minutes, and they have the context to help you."

**4. Security documentation**

Enterprise procurement teams want to see: penetration test reports, vulnerability disclosure policies, data processing agreements, encryption standards documentation, access control documentation, audit log capabilities, and data retention policies.

They are not reading your code. They are reading your documentation and checking it against their compliance requirements.

**5. Vendor stability**

Will this company exist in three years? Is it funded? Does it have more than one developer? What happens if the maintainer gets bored? Enterprise customers are making a long-term bet, and they need confidence that the bet will not go bad.

A solo developer's open-source project could be technically brilliant. It fails the vendor stability test.

---

## Why volta is not enterprise-grade (yet)

volta-auth-proxy is honest about this. It is not enterprise-grade, and here is exactly why:

| Enterprise requirement | volta status |
|---|---|
| SOC2 certification | No. volta is an open-source project, not an audited organization. |
| SLA | No. There is no contractual uptime guarantee. |
| Support contract | No. Support is via documentation and community. |
| Penetration testing | No. Not professionally tested by an external firm. |
| High availability | Not in Phase 1. Single-instance architecture. |
| Vendor stability | Uncertain. Open-source project with active development, but no corporate backing. |
| Data processing agreement | No. You host it yourself, so you are the data processor. |
| Compliance documentation | Partial. Security measures are documented, but not in the format enterprise procurement teams expect. |

This is not a failure. It is a scope decision. volta is designed for teams that want control over their auth infrastructure and are willing to own the operational responsibility. That is a different audience than enterprise procurement.

---

## What it would take for volta to become enterprise-grade

This is the honest roadmap, not a promise:

**Phase 1 (current): Technical foundation.** The code is solid. The security model is thoughtful. The documentation is extensive. This is the prerequisite for everything else.

**Phase 2: Operational maturity.** High availability (multi-instance). Distributed rate limiting. Load testing with published benchmarks. Operational runbooks. These make volta reliable enough for serious production use.

**Phase 3: Security validation.** Professional penetration testing. Vulnerability disclosure policy. Security advisory process. Bug bounty program. These provide external validation that the security model works.

**Phase 4: Organizational wrapper.** SOC2 audit. SLA offering. Support contracts. Compliance documentation in enterprise-expected formats. This is the part that turns a good open-source project into an enterprise product.

Each phase builds on the previous one. Skipping to Phase 4 without Phase 2 and 3 would produce enterprise theater -- the appearance of enterprise readiness without the substance.

---

## The uncomfortable truth about enterprise-grade

Here is what nobody says out loud: a significant portion of "enterprise-grade" is paperwork. The same software, with the same code, the same bugs, and the same capabilities, can go from "not enterprise-grade" to "enterprise-grade" by adding certifications, contracts, and documentation.

This is not cynicism. It is how risk management works at scale. Large organizations cannot evaluate every line of code in every tool they adopt. So they use proxies: certifications, audits, contracts, and vendor reputation. These proxies are imperfect, but they are the best available mechanism for managing risk across thousands of software dependencies.

Understanding this distinction -- that enterprise-grade is about trust infrastructure, not code quality -- is essential for anyone building software that might eventually serve enterprise customers.

---

## It is OK not to know this

If you thought enterprise-grade meant "really good code" or "handles lots of users," you had a reasonable but incomplete understanding. The technical foundation matters, but it is the table stakes, not the game. The game is trust, liability, and organizational capability.

volta's approach is to be transparent about where it stands. That transparency itself is unusual in the software world, where projects often either overclaim readiness or avoid the conversation entirely.

Now you know.
