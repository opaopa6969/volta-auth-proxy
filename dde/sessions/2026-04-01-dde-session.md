# DDE Session: volta-auth-proxy Documentation Enrichment

**Date:** 2026-04-01
**Tool:** @unlaxer/dde-toolkit v0.1.4
**Session type:** Term extraction → Glossary generation → dde-link → Diagrams

---

## Target Files

User-facing markdown files (11 files):

| File | dde-link additions |
|------|-------------------|
| `README.md` | 208 |
| `README.ja.md` | 41 |
| `docs/target-audience.md` | applied |
| `docs/target-audience.ja.md` | applied |
| `docs/getting-started-dialogue.md` | applied |
| `docs/getting-started-dialogue.ja.md` | applied |
| `docs/llm-integration-guide.md` | applied |
| `docs/llm-integration-guide.ja.md` | applied |
| `docs/no-traefik-guide.md` | applied |
| `docs/no-traefik-guide.ja.md` | applied |
| `docs/dsl-overview.md` | applied |

Excluded: spec files, LLM-internal docs (not user-facing).

---

## Reader Context

- Level: **全部＋おばあちゃん** (all levels, including non-technical readers)
- Intent: **educational** (terms link to glossary for learning)
- Languages: **EN + JA** (both for all generated articles)

---

## Glossary Articles Generated

~130 new terms, ~260 files (EN + JA each). Batches processed in parallel:

### Batch A — Internet Basics
browser, domain, subdomain, login, logout, redirect, network, network-isolation, protocol, ssl-tls, credentials, qr-code, routing, cross-origin, wildcard-certificate, responsive

### Batch B — Programming Concepts
framework, library, middleware, spa, endpoint, frontend-backend, client, template, type-safe, compile, build, process, repository, uuid, variable, clone

### Batch C — Infrastructure
deployment, production, architecture, integration, dependencies, runtime, schema, migration, redis, horizontal-scaling, high-availability, load-balancer, ci-cd, connection-pool, in-memory, lts

### Batch D — volta Core Concepts
dsl, state-machine, guard, transition, invariant, single-source-of-truth, policy-engine, delegation, crud, enforcement, hierarchy, content-negotiation, interstitial, audit-log, service-token, membership

### Batch E — Auth & Crypto
token, claim, key-cryptographic, self-signed, auto-key-generation, signing-key, revoke, graceful-transition, signed-cookie, session-expiry, silent-refresh, verification, invalidation, propagation, regenerate, cryptographic-signature, hmac, crypto-random

### Batch F — Patterns & Protocols
webhook, m2m, scim, abac, outbox-pattern, provisioning, ingestion, retry, invitation-code, suspension, whitelist, base64, payload, vanilla-javascript, upstream, billing

### Batch G — Philosophy & Tools
yagni, mvp, greenfield, soc2, sla, compliance, penetration-test, cel, health-check, startup, javalin, docker-volume, docker-label, mermaid

### Batch H — Products & Services
traefik, nginx, caddy, jcasbin, opa, stripe, zitadel, ory-stack, cloudflare-zero-trust, okta, active-directory, google-cloud-console, google-workspace, supertokens, elasticsearch, kafka, sendgrid, responsive

---

## dde-link Application

Applied to all 11 source files + all glossary articles (cross-linking).

### Known Issue: "port" false positives

dde-link matched "port" inside compound words. Fixed with sed after application:

```bash
# Compounds affected: important, support, export, transport, report
sed -i 's/im\[port\](docs\/glossary\/port\.md)ant/important/g' <files>
sed -i 's/sup\[port\](docs\/glossary\/port\.md)/support/g' <files>
sed -i 's/ex\[port\](docs\/glossary\/port\.md)/export/g' <files>
sed -i 's/trans\[port\](docs\/glossary\/port\.md)/transport/g' <files>
sed -i 's/re\[port\](docs\/glossary\/port\.md)/report/g' <files>
```

**Root cause:** dde-link does not apply word-boundary checks for short terms.
**Recommendation:** Add word-boundary option to dde-link CLI, or add "port" to a stoplist for compound-word-prone terms.

---

## Diagrams Added

6 diagrams added to sections identified as lacking visual explanation:

| Location | Diagram type |
|----------|-------------|
| `README.md` — Google OAuth Setup | OIDC runtime sequence (Browser→Traefik→volta→Google) |
| `README.md` — Connecting a New App | Component overview (volta-config.yaml → Traefik → App) |
| `docs/getting-started-dialogue.md` — Scene 4 | Invitation flow sequence (Admin→volta→Google→NewUser) |
| `docs/llm-integration-guide.md` — Phase 1 | Decision tree (proxy type → integration approach) |
| `docs/no-traefik-guide.md` — Pattern A Detailed | Runtime data-flow (Browser:3000→volta:7070→App:8080) |
| `docs/target-audience.md` — Path to Enterprise | Phase 1→4 roadmap table |

---

## Glossary Format Reference

Existing articles used as format reference: `jwt.md`, `csrf.md`, `tenant.md`

Structure:
1. What is it? (grandma-friendly analogy)
2. Why does it matter?
3. How does it work? (technical detail)
4. volta-specific usage
5. Common mistakes
6. Further reading (links to related glossary articles)

Length: 100–250 lines per article. ASCII diagrams where helpful.

---

## Final Glossary Count

- Pre-session: ~163 articles (EN) + ~162 articles (JA)
- Post-session: ~293 articles (EN) + ~292 articles (JA)
- Net new: ~130 EN + ~130 JA = **~260 files**
