# Penetration Test

[日本語版はこちら](penetration-test.ja.md)

---

## What is it?

A penetration test (pentest) is an authorized, simulated attack on a system to find security vulnerabilities before real attackers do. A trained security professional tries to break into your application using the same techniques a real attacker would use -- SQL injection, XSS, authentication bypass, privilege escalation -- and then writes a report of everything they found.

Think of it like hiring a locksmith to try to break into your house. You give them permission, they try every window, every lock, every possible entry point. When they are done, they hand you a report: "Your back door lock can be picked in 30 seconds. Your garage code is the factory default. But your front door deadbolt is solid." Now you know what to fix before a real burglar tries.

The key word is "authorized." Without permission, this is hacking. With permission and a formal scope agreement, it is a penetration test.

---

## Why does it matter?

Every application has vulnerabilities. The question is whether you find them first or an attacker does. Penetration testing matters because:

- **Developers are too close to the code.** They think about how the system should work. Pentesters think about how it can break.
- **Automated scanners miss logic flaws.** A scanner can find missing headers, but it cannot find "invitations can be accepted after they expire" or "users can escalate their own role."
- **Compliance requires it.** SOC 2, PCI DSS, and many enterprise contracts require regular penetration testing.
- **It builds confidence.** After a pentest with clean results, you can tell customers "an independent security firm tested our system."

---

## How does it work?

### Types of penetration tests

| Type | Tester's knowledge | Simulates |
|------|-------------------|-----------|
| **Black box** | No knowledge of the system. Given only the URL. | External attacker with no inside info |
| **Gray box** | Partial knowledge. Given a user account and API docs. | Attacker who has basic access (e.g., a customer) |
| **White box** | Full access to source code, architecture, database schema. | Insider threat or determined attacker |

### The penetration testing process

```
  Phase 1: Scoping
  ┌────────────────────────────────────┐
  │  What is in scope?                 │
  │  - Web application                 │
  │  - API endpoints                   │
  │  - Authentication flows            │
  │                                    │
  │  What is out of scope?             │
  │  - Third-party services (Google)   │
  │  - Physical infrastructure         │
  │  - Social engineering              │
  └────────────────────────────────────┘
              │
              ▼
  Phase 2: Reconnaissance
  ┌────────────────────────────────────┐
  │  - Map all endpoints               │
  │  - Identify technologies           │
  │  - Find entry points               │
  │  - Review authentication flows     │
  └────────────────────────────────────┘
              │
              ▼
  Phase 3: Vulnerability discovery
  ┌────────────────────────────────────┐
  │  - Test for injection (SQL, XSS)   │
  │  - Test authentication bypass      │
  │  - Test authorization (IDOR, priv  │
  │    escalation)                     │
  │  - Test session management         │
  │  - Test CSRF protection            │
  │  - Test for info disclosure        │
  └────────────────────────────────────┘
              │
              ▼
  Phase 4: Exploitation
  ┌────────────────────────────────────┐
  │  Attempt to exploit found vulns:   │
  │  - Can I access another tenant's   │
  │    data?                           │
  │  - Can I escalate from MEMBER to   │
  │    OWNER?                          │
  │  - Can I bypass the auth proxy?    │
  └────────────────────────────────────┘
              │
              ▼
  Phase 5: Reporting
  ┌────────────────────────────────────┐
  │  For each finding:                 │
  │  - Severity (Critical/High/Med/Low)│
  │  - Description                     │
  │  - Proof of concept                │
  │  - Remediation recommendation      │
  └────────────────────────────────────┘
```

### Severity ratings

| Severity | Example | Impact |
|----------|---------|--------|
| **Critical** | SQL injection allowing data extraction | Full data breach |
| **High** | Authentication bypass | Unauthorized access to all accounts |
| **Medium** | CSRF on role-change endpoint | Attacker can modify permissions |
| **Low** | Verbose error messages revealing stack traces | Information leakage |
| **Informational** | Missing security headers | Minor hardening opportunity |

---

## How does volta-auth-proxy use it?

volta-auth-proxy has not undergone a formal penetration test yet. This is planned for Phase 2/3. However, volta's architecture is designed with pentest-readiness in mind.

### What a pentest of volta would target

| Attack surface | What a pentester would test | volta's defense |
|---------------|---------------------------|-----------------|
| **OIDC callback** | Can the callback be forged? State tampering? | State parameter, nonce validation |
| **Session management** | Session fixation? Session hijacking? | Regeneration on login, HttpOnly/Secure/SameSite |
| **CSRF** | Can forms be submitted cross-origin? | Token-based + SameSite defense |
| **Tenant isolation** | Can user A access tenant B's data? | `enforceTenantMatch()`, row-level filtering |
| **Role escalation** | Can a MEMBER become an OWNER? | Server-side role checks on every request |
| **Invitation system** | Can expired invitations be used? Can invites be brute-forced? | Expiry checks, cryptographic invite codes |
| **API endpoints** | Injection? Mass assignment? IDOR? | Input validation, explicit field mapping |
| **Health check** | Does /healthz leak information? | Returns only `{"status":"ok"}` |

### Why volta's design helps pentesters

volta's single-process, tight-coupling philosophy makes penetration testing easier:

```
  Microservices pentest:
  ┌──────┐  ┌──────┐  ┌──────┐  ┌──────┐
  │Svc A │──│Svc B │──│Svc C │──│Svc D │
  └──────┘  └──────┘  └──────┘  └──────┘
  4 services, 6 interfaces, 4 auth boundaries
  Hard to test, easy to miss gaps

  volta pentest:
  ┌──────────────────────────────────┐
  │        volta-auth-proxy          │
  │  (single process, one boundary)  │
  └──────────────────────────────────┘
  1 service, 1 interface, 1 auth boundary
  Easy to test thoroughly
```

### Pentest preparation checklist

When volta reaches the pentest phase, the preparation will include:

1. Document all API endpoints and their expected behavior
2. Provide test accounts at each role level (OWNER, MEMBER)
3. Set up a staging environment identical to production
4. Define scope (web app + API, exclude Google OIDC provider)
5. Establish communication channels for critical findings
6. Budget for remediation sprints after the report

---

## Common mistakes and attacks

### Mistake 1: Pentesting only once

A single pentest is a snapshot. New features introduce new vulnerabilities. Pentest at least annually, and after major feature releases.

### Mistake 2: Pentesting too early

Pentesting a half-built application wastes money. The pentest finds issues that would have been caught anyway during development. Wait until the application is feature-complete for its current phase.

### Mistake 3: Ignoring the report

The pentest report is useless if findings are not remediated. Prioritize critical and high findings for immediate fixes. Medium findings should be addressed within 30 days.

### Mistake 4: Confusing vulnerability scanning with pentesting

Automated vulnerability scanners (Nessus, OWASP ZAP) find known vulnerability patterns. They are useful but they cannot replace a human pentester who can find logic flaws, chained attacks, and business logic vulnerabilities.

### Mistake 5: Not defining scope

Without a clear scope, the pentester might test things you did not intend (production database, third-party services) or miss things you cared about (specific API endpoints). Always sign a scope agreement.

---

## Further reading

- [soc2.md](soc2.md) -- SOC 2 requires regular penetration testing.
- [compliance.md](compliance.md) -- Compliance standards that mandate pentesting.
- [csrf.md](csrf.md) -- One of the vulnerabilities pentesters look for.
- [xss.md](xss.md) -- Another common pentest finding.
- [cross-tenant-access.md](cross-tenant-access.md) -- Tenant isolation testing.
- [OWASP Testing Guide](https://owasp.org/www-project-web-security-testing-guide/) -- The standard methodology for web application pentesting.
