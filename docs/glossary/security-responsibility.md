# Security Responsibility

[日本語版はこちら](security-responsibility.ja.md)

---

When you build your own auth, you own the security. This sentence is easy to say and hard to live. This essay is about what "owning the security" actually means in practice -- not in theory, but in the day-to-day reality of running a self-built authentication system.

---

## What you are signing up for

When you choose volta-auth-proxy (or any self-hosted auth solution) instead of Auth0, you are implicitly agreeing to the following:

**"I will watch for security vulnerabilities in my dependencies and fix them promptly."**

volta depends on libraries: JJWT for JWT handling, Jetty (via Javalin) for HTTP, HikariCP for database connections, and others. Each of these libraries has potential vulnerabilities. Auth0 monitors these for you. With volta, you do it yourself.

**"I will keep up with security advisories for the protocols I implement."**

OAuth2 and OIDC have known attack vectors. New ones are discovered periodically. If a new attack against PKCE is published, you need to evaluate whether volta is affected and patch it if necessary.

**"I will respond to incidents quickly."**

If a user reports that their account was compromised, you need to investigate, determine the cause, and respond. Auth0 has an incident response team. With volta, your team IS the incident response team.

---

## What security responsibility looks like, month by month

### Month 1: Setup

Everything is fresh. You review volta's code, understand the auth flow, and deploy. Security feels manageable because you just built it.

### Month 3: Dependency update

GitHub's Dependabot flags that `io.jsonwebtoken:jjwt-impl` has a new version. Is it a security fix? You check the release notes. It is a minor fix for a specific JWT parsing edge case. You decide to update. You test. You deploy.

```
  Dependabot alert:
  ┌──────────────────────────────────────────────────────┐
  │ jjwt-impl 0.12.5 → 0.12.6                           │
  │ Severity: Moderate                                   │
  │ "Fixed: JWT with specific malformed header could     │
  │  bypass signature verification in rare cases"        │
  └──────────────────────────────────────────────────────┘

  Your response:
  1. Read the CVE details (30 minutes)
  2. Assess impact on your system (1 hour)
  3. Update pom.xml (5 minutes)
  4. Run tests (10 minutes)
  5. Deploy to staging (30 minutes)
  6. Verify in staging (1 hour)
  7. Deploy to production (30 minutes)
  8. Verify in production (30 minutes)

  Total: ~4 hours of focused security work
```

With Auth0, this is zero hours for you. Their team handles it.

### Month 6: Penetration testing

You hire a penetration testing firm. They spend a week trying to break your auth system. They find:

1. A CSRF token is not validated on one specific admin endpoint (medium severity)
2. The rate limiter allows slightly more requests than configured when burst traffic hits (low severity)
3. Error messages on the login page reveal whether an email exists in the system (information disclosure, low severity)

You fix all three. Time: 2-3 days of focused work.

### Month 9: A real incident

A user reports receiving a password reset email they did not request. (volta uses Google OIDC so there is no password reset, but let's say you added email/password login.) You investigate:

- Check audit logs for the user's account
- Look for suspicious IP addresses
- Determine if it was a legitimate mistake or an attack attempt
- Communicate with the affected user
- Write an incident report

Time: 1-2 days.

### Month 12: Protocol evolution

A new OAuth2 specification is published (e.g., OAuth 2.1 consolidating best practices). You evaluate:

- Does volta already follow these practices? (Mostly yes, because PKCE was always required.)
- Are there new recommendations to implement? (Maybe a new header or parameter.)
- Do you need to update? (Probably not urgently, but plan for it.)

Time: 4-8 hours of research and evaluation.

---

## The cost comparison: dollars vs responsibility

Auth0 at 100,000 MAU costs approximately $2,400/month ($28,800/year). What does that buy?

```
  What $28,800/year buys from Auth0:
  ┌────────────────────────────────────────────────┐
  │ ✓ 24/7 security monitoring                     │
  │ ✓ Automatic dependency patching                 │
  │ ✓ Incident response team                       │
  │ ✓ SOC 2 Type II compliance                     │
  │ ✓ Regular penetration testing                   │
  │ ✓ DDoS protection                              │
  │ ✓ Uptime SLA                                   │
  │ ✓ Vulnerability disclosure program             │
  │ ✓ Security research team                       │
  └────────────────────────────────────────────────┘

  What $0/year with volta requires from YOU:
  ┌────────────────────────────────────────────────┐
  │ ✓ Monitor dependency vulnerabilities            │
  │ ✓ Apply patches promptly                       │
  │ ✓ Respond to security incidents                │
  │ ✓ Conduct or commission pen testing            │
  │ ✓ Set up DDoS protection (Cloudflare, etc.)    │
  │ ✓ Monitor uptime                               │
  │ ✓ Review security advisories                   │
  │ ✓ Keep auth protocol knowledge current          │
  └────────────────────────────────────────────────┘
```

This is the trade-off. $28,800/year to have someone else worry, or $0/year plus your team's time and attention. Neither option is objectively better. It depends on your team's capabilities and priorities.

---

## volta's trade-off: $0 but you are the security team

volta's position is transparent:

1. **We give you well-tested, secure code.** volta follows security best practices: PKCE, RS256, signed cookies, CSRF tokens, rate limiting, encrypted key storage.

2. **We make the code understandable.** volta's codebase is small enough that one developer can read and understand all of it. You cannot audit what you cannot understand.

3. **We make dependencies minimal.** Fewer dependencies = fewer vulnerability surfaces. volta's core has a handful of dependencies, not hundreds.

4. **The rest is on you.** Monitoring. Patching. Testing. Incident response. These are your responsibilities.

This is not a bug in volta's model. It is the model. You save $28,800/year. You spend that savings in engineering time and attention. For teams with strong engineering culture, this is a good deal. For teams that want to focus purely on product and never think about auth security, Auth0 is the better choice.

---

## Practical security checklist for volta operators

If you operate volta-auth-proxy, this is the minimum security hygiene:

- [ ] **Enable Dependabot** (or Renovate) on your volta repository
- [ ] **Review dependency updates** within 48 hours of notification
- [ ] **Subscribe to CVE feeds** for JJWT, Jetty, PostgreSQL JDBC driver
- [ ] **Conduct penetration testing** at least annually
- [ ] **Monitor audit logs** for suspicious patterns (brute force, unusual IPs)
- [ ] **Keep Java runtime updated** (security patches)
- [ ] **Keep PostgreSQL updated** (security patches)
- [ ] **Use HTTPS everywhere** (TLS termination at Traefik)
- [ ] **Restrict network access** to volta's internal API
- [ ] **Back up the database** and test recovery regularly
- [ ] **Set up alerting** for failed auth attempts exceeding thresholds

---

## Further reading

- [tradeoff.md](tradeoff.md) -- The broader trade-off framework.
- [native-implementation.md](native-implementation.md) -- Why teams choose to build their own.
- [self-hosting.md](self-hosting.md) -- The operational side of self-hosting.
- [auth0.md](auth0.md) -- The alternative that handles security for you.
- [rate-limiting.md](rate-limiting.md) -- volta's built-in security measure.
- [brute-force.md](brute-force.md) -- The attack volta's rate limiter prevents.
