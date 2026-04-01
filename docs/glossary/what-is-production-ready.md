# What Is "Production-Ready," Actually?

[日本語版はこちら](what-is-production-ready.ja.md)

---

## The misconception

"Is it production-ready?"

This question gets asked about every open-source project, every new framework, every internal service. And it is almost always the wrong question, because it implies that "production-ready" is a binary state. It is not. It is a spectrum, and where you sit on that spectrum depends entirely on what "production" means for you.

A side project serving 50 users has a very different definition of "production-ready" than a healthcare platform serving 5 million patients. The code could be identical. The readiness is not.

---

## What "production-ready" actually means

Production-ready means: this system can run in an environment where real users depend on it, and when things go wrong (they will), the team can detect, diagnose, and recover from the problem without catastrophic consequences.

That definition has several parts, and most people only think about the first one ("can run"). The rest matter more.

### The parts of production readiness

**1. It works correctly.** The happy path functions as expected. This is the part everyone focuses on. It is necessary but wildly insufficient.

**2. It fails gracefully.** What happens when the database is unreachable? When an upstream service times out? When a malformed request arrives? When disk is full? Production-ready software does not crash on unexpected input. It returns meaningful error responses, logs the problem, and continues serving other requests.

**3. It is observable.** You can see what the system is doing without reading the code. Health check endpoints. Structured logs. Metrics on request latency, error rates, and resource usage. If something goes wrong at 3 AM, can you figure out what happened from the logs alone?

**4. It handles load.** Not just average load. Peak load. Sustained load. Sudden spikes. What happens when traffic doubles? Does it degrade gracefully or fall over?

**5. It can be deployed safely.** You can update the system without downtime. You can roll back if the update causes problems. Database migrations are backward-compatible. Configuration changes do not require rebuilding the entire system.

**6. It has operational runbooks.** When something goes wrong, there are documented procedures for common scenarios. "Database connection pool exhausted: here is what to check." "JWT verification failures spiking: here is the likely cause." This is not glamorous work. It saves you at 3 AM.

**7. It has backup and recovery.** The data can be backed up. The backups have been tested (not assumed to work). Recovery time is known and acceptable.

**8. It has incident response.** When something goes wrong, who gets alerted? How? What is the escalation path? What information do they need to start investigating?

---

## volta's honest assessment

volta-auth-proxy is honest about where it is and is not production-ready. Here is the breakdown:

### Where volta IS production-ready

**Correct functionality.** The authentication flow (OIDC + session + JWT) is implemented with proper security measures: PKCE, state, nonce, RS256 signing, algorithm allowlisting, session fixation prevention, rate limiting. This is not a proof-of-concept. It is carefully built.

**Graceful failure.** volta returns proper HTTP status codes (401 for unauthenticated, 403 for unauthorized, 500 for internal errors). JSON API endpoints never return HTML redirects. Error responses include enough information to debug without leaking internal details.

**Observability.** `GET /healthz` for monitoring. Audit logging for every auth event. Structured logs for request processing. Downstream apps receive identity information via headers, making debugging straightforward.

**Deployment.** Docker Compose for local and staging. ~200ms startup means deployments are fast. Configuration via environment variables and a single YAML file means no complex deployment pipelines.

**Data safety.** Private keys encrypted at rest. Sessions in PostgreSQL (which has mature backup tooling). Configuration externalized (not baked into the binary).

### Where volta is NOT yet production-ready

**High availability.** Phase 1 is single-instance. If volta goes down, ForwardAuth fails, and downstream apps become inaccessible. For a side project, this is acceptable. For a business-critical SaaS, you need a failover strategy. This is planned for Phase 2.

**Distributed rate limiting.** Rate limiting is in-memory on the single instance. With multiple instances, rate limits would not be shared. This requires Redis or a similar shared store. Known gap, planned for multi-instance support.

**Automated alerting.** volta logs events but does not integrate with alerting systems (PagerDuty, OpsGenie, etc.) out of the box. You need to set up log-based alerting externally.

**Load testing results.** volta has not published load test benchmarks. "A single Javalin instance can handle hundreds of requests per second" is a reasonable estimate based on the framework, but it is not a verified, documented number.

**Operational runbooks.** The documentation is extensive (283 glossary articles and counting), but operational runbooks for specific failure scenarios are not yet written.

**Penetration testing.** volta has not undergone professional penetration testing. The security measures are solid by design, but "we designed it securely" is not the same as "an adversary tested it and could not break it."

---

## The honest answer to "is it production-ready?"

It depends on your production.

| Your scenario | volta readiness |
|---|---|
| Side project, personal SaaS, <1000 users | Ready. The security model is more thorough than what most side projects implement. |
| Startup, early-stage product, <10,000 users | Ready with caveats. Set up external monitoring and alerting. Have a database backup strategy. Understand the single-instance limitation. |
| Growth-stage product, >10,000 users | Partially ready. You will likely need multi-instance support, distributed rate limiting, and load testing before committing. |
| Enterprise, regulated industry, >100,000 users | Not yet ready. You need HA, penetration testing, compliance certifications, and operational maturity that volta does not yet provide. |

This table is not a sales pitch. It is an honest assessment. Most open-source projects never give you this. They either say "production-ready!" (when they are not) or stay silent (leaving you to find out the hard way).

---

## It is OK not to know this

If you thought "production-ready" meant "the tests pass and it works on my machine," you are far from alone. Most engineers learn what production readiness really means by surviving their first production incident -- the hard way.

The better path is to understand the dimensions of production readiness before your first incident. Not all of them need to be perfect on day one. But you need to know which ones you are deferring and what the consequences of deferral are.

Now you know.
