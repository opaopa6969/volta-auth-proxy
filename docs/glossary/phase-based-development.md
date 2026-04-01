# Phase-Based Development

[日本語版はこちら](phase-based-development.ja.md)

---

## What is it?

Phase-based development is a strategy where you build a system in deliberate stages, starting with the minimum viable set of features and expanding over time. Each phase is a working product -- not a half-finished prototype, but a complete system that does fewer things than the final vision.

Think of it like building a house. Phase 1: build a solid foundation, walls, roof, and plumbing. You can live in it. Phase 2: add a second bathroom and a garage. Phase 3: add a home office and a deck. Each phase produces something livable. You do not wait until the deck is designed before you pour the foundation.

---

## Why does it matter?

Many software projects fail because they try to build everything at once. The team spends months designing a comprehensive system, then months building it, then discovers that half the features are not needed and the other half are wrong. Phase-based development avoids this by delivering working software early and expanding based on real needs.

volta-auth-proxy follows a 4-phase roadmap that demonstrates this approach. Phase 1 is a complete, working auth system. Each subsequent phase adds capabilities that real users have requested or that the architecture was designed to support.

---

## The anti-pattern: build everything first

```
  "Let's design everything before building anything" approach:

  Month 1-3:   Design OIDC + SAML + LDAP + MFA + SCIM + Billing
  Month 4-8:   Build it all simultaneously
  Month 9:     Nothing works yet. Everything depends on everything.
  Month 10:    Team morale drops. "When will this ship?"
  Month 12:    Ship a buggy mess because deadline pressure.

  Result: A system that does 10 things poorly.
```

This is the waterfall trap. It fails because:

1. **Requirements change** -- By month 8, the original requirements are outdated.
2. **Integration hell** -- 10 components built in isolation rarely work together smoothly.
3. **No feedback loop** -- You learn nothing from users for 12 months.
4. **Motivation death** -- Teams lose energy when they cannot show progress.

---

## The volta approach: phase-based delivery

### Phase 1: Core (minimum viable auth)

```
  What ships:
  ┌──────────────────────────────────────────────────────┐
  │ Google OIDC login (Authorization Code + PKCE)        │
  │ Session management (sliding window, max 5 per user)  │
  │ Multi-tenancy (tenant creation, resolution, switching)│
  │ Role-based access (OWNER > ADMIN > MEMBER > VIEWER)  │
  │ Invitation system (codes, expiry, consent screen)     │
  │ ForwardAuth (X-Volta-* headers to downstream apps)   │
  │ Internal API (/api/v1/* for app delegation)           │
  │ JWT issuance (RS256, 5-min expiry, JWKS endpoint)     │
  │ Login UI (jte templates, full control)                │
  └──────────────────────────────────────────────────────┘

  Components: volta-auth-proxy + PostgreSQL.
  That's it. It works. Ship it.
```

Phase 1 is not a demo. It is production-ready auth for a multi-tenant SaaS. A team can build real applications on top of it.

### Phase 2: Scale

```
  What's added:
  ┌──────────────────────────────────────────────────────┐
  │ Multiple IdPs (GitHub, Microsoft)                     │
  │ M2M OAuth (Client Credentials flow)                  │
  │ Redis session storage (higher throughput)             │
  │ Webhooks (event notifications to your services)       │
  │ Passkeys (WebAuthn/FIDO2)                            │
  └──────────────────────────────────────────────────────┘
```

Phase 2 addresses scaling needs. More login options, machine-to-machine communication, faster session lookups. These are features you discover you need after Phase 1 is in production.

### Phase 3: Enterprise

```
  What's added:
  ┌──────────────────────────────────────────────────────┐
  │ SAML SSO (enterprise IdP integration)                 │
  │ Email notifications (SMTP, SendGrid)                  │
  │ MFA/2FA (TOTP, WebAuthn)                             │
  │ i18n (internationalization)                           │
  │ Conditional access (risk-based auth)                  │
  │ Fraud detection/alerting                              │
  └──────────────────────────────────────────────────────┘
```

Phase 3 adds enterprise requirements. SAML is needed when you sell to companies that use Active Directory. MFA is needed when security policies require it. These are features you add when you have enterprise customers, not before.

### Phase 4: Platform

```
  What's added:
  ┌──────────────────────────────────────────────────────┐
  │ SCIM (automated user provisioning)                    │
  │ Policy Engine                                         │
  │ Billing integration (Stripe)                          │
  │ GDPR data export/deletion                            │
  │ Device trust                                          │
  │ Mobile SDK (iOS/Android)                              │
  └──────────────────────────────────────────────────────┘
```

Phase 4 turns volta into a platform. SCIM is for companies with thousands of employees. Billing integration is for SaaS products that tie subscriptions to tenants. These are features you need at scale, not at launch.

---

## Why NOT building everything at once is actually faster

### 1. You ship sooner

Phase 1 can ship in weeks. The "build everything" approach ships in months (if ever). Real users using your system provides feedback that shapes Phase 2.

### 2. Interfaces protect the future

volta defines interfaces (like `SessionStore`, `AuditSink`) in Phase 1 even though alternative implementations come later. The architecture is ready for expansion without being burdened by premature code:

```
  Phase 1: SessionStore interface + PostgresSessionStore
  Phase 2: + RedisSessionStore (drops in, zero changes to auth logic)
```

### 3. Each phase is testable in isolation

Phase 1 is a complete system with complete tests. Phase 2 adds features without breaking Phase 1. This is much easier than testing 10 features that were built simultaneously.

### 4. You avoid building the wrong thing

How do you know you need SAML before you have customers who use SAML? You don't. Phase-based development means you build SAML when a customer asks for it, not when you imagine they might.

---

## The critical requirement: architecture must support expansion

Phase-based development only works if the Phase 1 architecture can accommodate future phases without rewrites. This requires deliberate design:

- **Interfaces** for components that will have alternative implementations
- **Database schema** designed with foreign keys and extensibility in mind
- **API versioning** (`/api/v1/`) so future APIs do not break existing clients
- **Configuration** that is environment-variable-based and easily extendable

volta's Phase 1 was designed with Phases 2-4 in mind. The code was not written, but the architecture was prepared.

---

## Further reading

- [interface-extension-point.md](interface-extension-point.md) -- How volta's interfaces enable phase-based expansion.
- [api-versioning.md](api-versioning.md) -- How API versioning supports phased development.
- [database-migration.md](database-migration.md) -- How Flyway handles schema evolution across phases.
