# Compliance

[日本語版はこちら](compliance.ja.md)

---

## What is it?

Compliance means meeting the rules that apply to your software. These rules come from laws (GDPR, HIPAA), industry standards (SOC 2, PCI DSS, ISO 27001), and contractual obligations (customer security requirements). If your software handles personal data, payments, or healthcare records, compliance is not optional -- it is legally required.

Think of it like driving a car. You can build the fastest car in the world, but if it does not have seatbelts, turn signals, and emission controls, it cannot legally be driven on public roads. Compliance is the set of seatbelts and signals your software must have before it can operate in regulated environments.

Compliance is not the same as security. A system can be secure but non-compliant (it protects data well but does not document how). Or compliant but insecure (it checks all the boxes on paper but has vulnerabilities). The goal is both.

---

## Why does it matter?

Non-compliance has real consequences:

- **Fines**: GDPR fines can reach 4% of global annual revenue or 20 million euros, whichever is higher. This is not theoretical -- companies have been fined billions.
- **Contract loss**: Enterprise customers require compliance certifications. No certification, no contract.
- **Legal liability**: If a data breach occurs and you were not compliant, liability increases dramatically.
- **Reputation damage**: "Company X fined for GDPR violation" is a headline that kills trust.

For early-stage startups, compliance is a spectrum. You do not need SOC 2 on day one, but you need to know which regulations apply to you and have a plan for meeting them.

---

## How does it work?

### The compliance landscape

| Standard | Full name | Applies to | Requires |
|----------|-----------|-----------|----------|
| **GDPR** | General Data Protection Regulation | Anyone handling EU residents' data | Privacy by design, consent, right to delete, DPO |
| **SOC 2** | Service Organization Control 2 | SaaS vendors selling to US enterprises | Independent audit of security controls |
| **ISO 27001** | Information Security Management | Global enterprises | Formal ISMS (Information Security Management System) |
| **HIPAA** | Health Insurance Portability Act | US healthcare data handlers | Encryption, access controls, audit trails, BAAs |
| **PCI DSS** | Payment Card Industry Data Security Standard | Anyone processing credit cards | Network security, encryption, vulnerability management |
| **CCPA** | California Consumer Privacy Act | Companies handling California residents' data | Disclosure, opt-out, deletion rights |

### How compliance works in practice

```
  Step 1: Identify which regulations apply
          ┌──────────────────────────┐
          │ Do you store EU user     │
          │ data? ──► GDPR applies   │
          │                          │
          │ Do you sell to US        │
          │ enterprises? ──► SOC 2   │
          │                          │
          │ Do you process           │
          │ payments? ──► PCI DSS    │
          │                          │
          │ Healthcare data?         │
          │ ──► HIPAA               │
          └──────────────────────────┘
              │
              ▼
  Step 2: Gap analysis
          What do you already do?
          What are you missing?
              │
              ▼
  Step 3: Implement controls
          - Technical (encryption, access control, logging)
          - Procedural (policies, training, incident response)
          - Documentation (evidence collection)
              │
              ▼
  Step 4: Verify / audit
          - Self-assessment (GDPR)
          - Third-party audit (SOC 2, ISO 27001)
          - Certification body (PCI DSS)
              │
              ▼
  Step 5: Maintain continuously
          Compliance is not a one-time event.
          Annual audits, ongoing monitoring,
          policy updates as regulations change.
```

### GDPR essentials for SaaS

Since most SaaS products handle EU user data, GDPR is the most universally relevant regulation:

| GDPR requirement | What it means in practice |
|-----------------|--------------------------|
| **Lawful basis** | You need a legal reason to process data (consent, contract, legitimate interest) |
| **Data minimization** | Collect only what you need. Do not hoard data "just in case" |
| **Right to access** | Users can request all data you hold about them |
| **Right to deletion** | Users can request you delete their data ("right to be forgotten") |
| **Data breach notification** | You must notify authorities within 72 hours of a breach |
| **Privacy by design** | Build privacy into the system architecture, not as an afterthought |
| **Data Processing Agreement** | Required contract with any third party that processes your users' data |

---

## How does volta-auth-proxy use it?

volta-auth-proxy takes a pragmatic approach to compliance: implement the technical controls now, defer the certifications until they are needed.

### What volta does today

| Control | Implementation | Relevant standards |
|---------|---------------|-------------------|
| **Encryption in transit** | HTTPS/TLS for all auth flows | GDPR, SOC 2, PCI DSS |
| **Session security** | HttpOnly, Secure, SameSite cookies | SOC 2, OWASP |
| **CSRF protection** | Token-based + SameSite | SOC 2, OWASP |
| **Data minimization** | Only stores email, name, provider ID | GDPR |
| **Audit logging** | Auth events logged with timestamps | SOC 2, GDPR |
| **Access control** | Role-based (OWNER, MEMBER) | SOC 2, ISO 27001 |
| **Input validation** | All inputs validated and sanitized | OWASP, SOC 2 |

### What volta defers to later phases

| Requirement | Phase | Why deferred |
|------------|-------|-------------|
| SOC 2 certification | Phase 3 | Requires independent audit ($50K-$200K) |
| GDPR data export API | Phase 2 | Right to access requires dedicated endpoint |
| GDPR deletion API | Phase 2 | Right to deletion requires cascade delete logic |
| ISO 27001 | Phase 3+ | Requires formal ISMS |
| HIPAA | Not planned | volta is not a healthcare product |

### The self-hosted compliance advantage

Because volta is self-hosted, compliance responsibility is shared:

```
  volta (software vendor):
    Responsible for:
    - Secure code (no vulnerabilities)
    - Privacy by design (data minimization)
    - Documentation (what data is stored, how it flows)

  Customer (data controller):
    Responsible for:
    - Infrastructure security (network, OS, firewall)
    - Data processing agreements with their users
    - Breach notification to authorities
    - Access control to the server running volta
```

This model is well-understood in compliance frameworks. The customer's SOC 2 audit covers their infrastructure, and volta is treated as an internal component -- similar to PostgreSQL or Nginx.

---

## Common mistakes and attacks

### Mistake 1: "We're too small for compliance"

GDPR applies to any company processing EU residents' data, regardless of size. A two-person startup with EU users is subject to GDPR. Start with the basics: privacy policy, data minimization, encryption.

### Mistake 2: Compliance theater

Checking boxes without actually implementing controls. Writing a privacy policy that says "we protect your data" while storing passwords in plaintext. Auditors and regulators see through this.

### Mistake 3: Treating compliance as a one-time project

Regulations change. Your product changes. New features may introduce new compliance requirements. Compliance is an ongoing process, not a project with a deadline.

### Mistake 4: Ignoring third-party compliance

If you use SendGrid for email and Stripe for payments, their compliance status affects yours. You need Data Processing Agreements with processors and must verify they meet your compliance requirements.

### Mistake 5: Over-collecting data

"We might need this data later" violates GDPR's data minimization principle. Collect only what you need for the current purpose. volta stores only email, display name, and provider ID -- nothing more.

---

## Further reading

- [soc2.md](soc2.md) -- The most common enterprise security certification.
- [sla.md](sla.md) -- Service guarantees that compliance may require.
- [penetration-test.md](penetration-test.md) -- Security testing for compliance.
- [GDPR Official Text](https://gdpr-info.eu/) -- The full regulation.
- [OWASP Compliance Guidelines](https://owasp.org/www-project-web-security-testing-guide/) -- Security testing standards.
