# SOC 2 (Service Organization Control 2)

[日本語版はこちら](soc2.ja.md)

---

## What is it?

SOC 2 is a security audit framework created by the American Institute of CPAs (AICPA). It certifies that a company handles customer data securely. When a company says "we are SOC 2 compliant," it means an independent auditor has verified that their systems meet specific security, availability, and privacy standards.

Think of it like a restaurant health inspection. The restaurant might cook great food, but the health inspector comes in, checks the kitchen, the refrigerators, the handwashing stations, and writes a report. A passing score means "this kitchen meets safety standards." SOC 2 is the same thing, but for software companies handling your data.

SOC 2 is not a law -- it is a voluntary standard. But in practice, enterprise customers (banks, healthcare companies, large corporations) require their vendors to be SOC 2 certified before they will sign a contract. It is the entry ticket to selling to enterprises.

---

## Why does it matter?

For SaaS companies, SOC 2 is the dividing line between "startup selling to startups" and "company selling to enterprises." Without SOC 2:

- Enterprise procurement teams will not approve your product
- Security review questionnaires will be impossible to pass
- You cannot bid on government or regulated-industry contracts
- Larger customers will not trust you with their data

The flip side: SOC 2 certification is expensive and time-consuming. It typically costs $50,000-$200,000 and takes 6-12 months to prepare for the first audit. This is why early-stage startups skip it.

---

## How does it work?

### The five trust service criteria

SOC 2 is built around five principles (you do not need all five):

| Criterion | What it covers | Required? |
|-----------|---------------|-----------|
| **Security** | Protection against unauthorized access | Always (mandatory) |
| **Availability** | System uptime and performance | Optional |
| **Processing integrity** | Data is processed correctly and completely | Optional |
| **Confidentiality** | Sensitive data is protected | Optional |
| **Privacy** | Personal information is handled per policy | Optional |

### The audit process

```
  Step 1: Decide which criteria to include
          (Security is mandatory; others are optional)
              │
              ▼
  Step 2: Implement controls
          - Access management (who can access what)
          - Encryption (data at rest and in transit)
          - Logging and monitoring
          - Incident response procedures
          - Employee security training
          - Change management (how code gets to production)
          - Vendor management (your dependencies)
              │
              ▼
  Step 3: Operate controls for a period
          ┌─────────────────────────────┐
          │  Type I:  Point-in-time     │
          │  "Controls exist today"     │
          │  (snapshot, easier)         │
          ├─────────────────────────────┤
          │  Type II: Over a period     │
          │  "Controls worked for 6-12  │
          │   months continuously"      │
          │  (much harder, much more    │
          │   valuable to customers)    │
          └─────────────────────────────┘
              │
              ▼
  Step 4: Independent auditor reviews
          - Examines evidence
          - Tests controls
          - Interviews employees
              │
              ▼
  Step 5: Auditor issues SOC 2 report
          - Pass: "Controls are effective"
          - Fail: "These controls have gaps"
```

### What controls look like in practice

| Control area | Example requirement |
|-------------|-------------------|
| Access control | MFA required for all production systems |
| Change management | All code changes require peer review |
| Encryption | Data encrypted at rest (AES-256) and in transit (TLS 1.2+) |
| Logging | All access to customer data is logged and retained 90 days |
| Incident response | Security incidents documented and resolved within SLA |
| Vendor management | Third-party libraries reviewed for vulnerabilities |
| Employee onboarding | Background checks, security training within 30 days |

---

## How does volta-auth-proxy use it?

volta-auth-proxy does **not** have SOC 2 certification. This is a deliberate Phase 1 decision, not an oversight.

### Why volta skips SOC 2 (for now)

| Reason | Detail |
|--------|--------|
| **Target audience** | Indie hackers and early startups do not require SOC 2 |
| **Cost** | $50K-$200K is not justified for a Phase 1 product |
| **Overhead** | SOC 2 controls add process overhead incompatible with rapid iteration |
| **Self-hosted** | volta is self-hosted -- the customer controls their own data |

### The self-hosted advantage

Because volta runs on the customer's infrastructure, many SOC 2 concerns shift to the customer:

```
  Managed SaaS (Auth0, etc.):
    Customer data ──► Vendor's servers ──► SOC 2 required by vendor

  Self-hosted (volta):
    Customer data ──► Customer's servers ──► Customer's SOC 2 covers it
```

The customer's existing SOC 2 certification can cover volta as an internal tool, similar to how it covers their database or web server.

### What volta does instead

Even without formal SOC 2, volta follows security best practices that align with SOC 2 controls:

- **Encryption in transit**: All auth flows use HTTPS/TLS
- **Session security**: HttpOnly, Secure, SameSite cookies
- **CSRF protection**: Token-based + SameSite defense
- **Input validation**: All inputs validated and sanitized
- **Logging**: Auth events logged for audit trails
- **Minimal dependencies**: Fewer third-party libraries = smaller supply chain risk

### The Phase 3 roadmap

SOC 2 certification is planned for Phase 3 (enterprise readiness). By then, volta will need:

- Formal access control policies
- Continuous monitoring
- Incident response runbook
- Penetration testing (see [penetration-test.md](penetration-test.md))
- Audit log retention
- An independent auditor

---

## Common mistakes and attacks

### Mistake 1: Thinking SOC 2 means you are secure

SOC 2 certifies that controls exist and are followed. It does not mean the controls are good, or that the software has no vulnerabilities. Companies with SOC 2 certification still get breached.

### Mistake 2: Pursuing SOC 2 too early

If your customers are indie hackers and startups, spending $100K on SOC 2 is wasted money. Build the product first. Get SOC 2 when enterprise customers actually ask for it.

### Mistake 3: Treating SOC 2 as a one-time event

SOC 2 Type II requires continuous compliance. The controls must work every day, not just during audit season. This means ongoing investment in tooling, processes, and training.

### Mistake 4: Confusing SOC 2 with other standards

SOC 2 is not ISO 27001 (international security management). It is not HIPAA (healthcare data). It is not PCI DSS (payment card data). Different customers require different certifications.

---

## Further reading

- [compliance.md](compliance.md) -- The broader landscape of regulatory requirements.
- [sla.md](sla.md) -- Service guarantees that often accompany SOC 2.
- [penetration-test.md](penetration-test.md) -- Security testing required for SOC 2.
- [AICPA SOC 2 Overview](https://www.aicpa.org/topic/audit-assurance/audit-and-assurance-greater-than-soc-2) -- The official standard.
