# Changelog

All notable changes to volta-auth-proxy are documented here. This project follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Tags published to date: `dxe-v4.1.0`, `dxe-v4.1.1` (developer-experience toolkit subtags).
The auth-proxy itself is versioned via `pom.xml` (currently `0.3.0-SNAPSHOT`).

## [Unreleased] — 0.3.0-SNAPSHOT

### Added
- **Passkey**: authenticator type selection at registration (`0d17ce6`)
- **ForwardAuth**: local network bypass with CIDR matching — RFC1918 + Tailscale CGNAT + loopback defaults (`5f23f88`)
- **Observability**: auth event SSE stream (SAAS-016) for real-time flow monitoring (`9b4fe2c`)
- **Integration**: tramli-viz — live flow visualization UI (PR #22, `6315cc0`)
- **Admin APIs**: pagination + search + sort across all admin endpoints (PR #23, `f31a2f2`)
- **AUTH-010**: unified `AuthFlowHandler` replaces `OidcFlowRouter` + `MfaFlowRouter` (`99a2769`)
- **AUTH-014**: Tenant & Organization spec (`39d3b7e`)
- **volta-config** schema v3 — tenancy, access, binding layers (`6203de9`)
- **SAML**: XXE hardening (`disallow-doctype-decl`, `FEATURE_SECURE_PROCESSING`, `ACCESS_EXTERNAL_*` = `""`) in `SamlService`
- **SAML**: XSW mitigation — `secureValidation=true` on `DOMValidateContext`, audience + issuer + NotOnOrAfter + RequestId binding
- **Documentation**: auth textbook — 14 chapters from passwords to Auth as Code (`cafb39c`)
- **Documentation**: comprehensive README update (en/ja) — multi-tenancy, volta-gateway, security, observability (`4e870c9`)
- **Documentation**: pagination handoff for 100+ user scale (`ba222dd`)
- **Documentation**: tramli-quality docs — `README(-ja)`, `CHANGELOG`, `docs/architecture(-ja)`, `docs/getting-started(-ja)`, `docs/auth-flows(-ja)`

### Changed
- **tramli core**: 3.6.1 → 3.7.1 (`7c54950`)
- **Behavior**: local bypass only fires when no session — fixes MFA redirect loop (`4006ee7`)
- **Infra**: nginx proxies Java auth routes to backend — fixes MFA loop behind nginx (`afb6eab`)
- **Security (ADR-004)**: MFA verification is now tenant-scoped. `switch-tenant` no longer carries `mfaVerifiedAt` forward — the new session starts with `mfaVerifiedAt = null`, forcing re-verification on tenant change. Aligns with `volta-gateway/auth-server` Rust port.

### Fixed
- 20 security findings resolved (`abca91e`, issues #1–#21)
- Passkey finish: add `Accept` header + surface detailed error display (`a767d58`)
- MFA challenge: always create a fresh flow + `no-cache` (`4f4c55a`)
- MFA verify: graceful handling of expired/completed flows (`007fbb2`)
- MFA verify: skip tenant selection for single-tenant users (`d45d2ca`)
- Logout: use `location.replace` to prevent back-button to auth page (`9fbbdfb`)
- Logout: GET navigation rather than `fetch` + redirect (`92a1e6f`)
- Passkey login: missing `flow_id` + CSRF exempt (`a80822b`)
- `Set-Cookie` header overwrite + CSRF exempt for MFA/callback (`0058345`)
- `SqlFlowStore`: `SET LOCAL` in separate statement (`993ead2`)
- Fail-fast on missing `auth_flows` table at startup (`cdbac54`)
- `/auth/verify` reverted to procedural handler during AUTH-010 partial migration (`433486c`)
- Prioritise `BASE_URL` scheme over `X-Forwarded-Proto` (`7a8c8dd`)
- Wildcard subdomain matching in allowed redirect domains (`ac6bb8c`)
- Flyway version collisions + enable timestamp migrations (`f0c6451`)
- Fat JAR: `maven-shade-plugin` (`e3f0ec1`)
- CSRF Origin-based validation + `Set-Cookie` security hardening (`2458535`)

## [0.2.0] — 2026-04 (version bumped in `12124d6`)

### Added
- **tramli** plugin system adoption (`737c8ba`): 1.16.0 → 3.2.0
- **tramli** Logger API + `strictMode` + warnings (`6de74af`, v1.15.0)
- **tramli** `durationMicros` in logs (`e65474d`, 3.3.0)
- **tramli** `externallyProvided` declarations (`2fb679a`, 3.4.0)
- **tramli** `analyzeAndValidate` + `NoopTelemetrySink` (`c877cc7`, 3.6.1)
- **AUTH-010**: unified auth flow via tramli — fixes ForwardAuth redirect loop (`8c1d440`)
- **God Class split**: `Main.java` split into 5 routers — `AuthRouter`, `AdminRouter`, `ApiRouter`, `PasskeyRegistrationRouter`, etc. (`d5630bc`)
- **tramli-auth reference design**: 5 docs for Auth as Code migration (`9189e56`)
- **tramli-ization**: migrate to `org.unlaxer` package + Auth as Code README (`1d26ec9`)
- **Glossary Auto-Linker** CLI 002 (`c728ba2`)
- **SM Architecture**: conditional access, GDPR, i18n, `PolicyEngine` documented (`9de9f3c`)
- **volta-gateway**: Rust SM reverse proxy spec accepted through DGE tribunals (`2626fc0`, `11d3714`, `2d52cd3`)

### Fixed
- 20 pre-tramli security issues consolidated

## ADRs (Architecture Decision Records)

Current ADRs live under [`docs/decisions/`](docs/decisions/):

| # | Title | Status | Date |
|---|-------|--------|------|
| 001 | Reject form state restoration | Rejected | 2026-04-01 |
| 002 | Reject `TRUSTED_NETWORKS` bypass | Superseded → 003 | 2026-04-02 |
| 003 | Accept local network bypass (LAN/Tailscale) | Accepted | 2026-04-12 |
| 004 | Tenant-scoped MFA re-verification | Accepted | 2026-04-14 |

---

See [README.md](README.md) for the user-facing overview, [docs/architecture.md](docs/architecture.md) for
the internal layering (dxe / dge / dve responsibilities), and
[docs/auth-flows.md](docs/auth-flows.md) for the concrete OIDC / SAML / MFA / Passkey flows.
