# IAM (Identity and Access Management)

[日本語版はこちら](iam.ja.md)

---

## What is it?

IAM stands for Identity and Access Management -- it is the umbrella term for everything related to figuring out who someone is (identity) and what they are allowed to do (access management).

Think of it like the entire security system of a building. IAM is not just the lock on the front door -- it is the ID badges, the key cards, the visitor log, the "authorized personnel only" signs, the cameras, and the rules about who can go where. It is the whole system, not just one piece.

---

## Why does it matter?

Every application that has users needs IAM in some form. Even a simple app with a login page is doing identity management (who are you?) and access management (what can you see?).

The confusing thing is that "IAM" is used to describe:

1. **The concept** -- the general idea of managing identity and access
2. **A category of software** -- tools that help you implement identity and access management
3. **Specific cloud services** -- like AWS IAM, Google Cloud IAM

As a beginner, the most important thing is to understand that IAM is the big picture. Everything else -- OIDC, OAuth, JWTs, sessions, RBAC -- are specific tools and techniques that live under the IAM umbrella.

### The IAM landscape

```
  IAM (the big umbrella)
  ├── Authentication ("Who are you?")
  │   ├── Passwords
  │   ├── OIDC / OAuth (social login, SSO)
  │   ├── SAML (enterprise SSO)
  │   ├── MFA (multi-factor)
  │   ├── WebAuthn / Passkeys
  │   └── Certificates
  │
  ├── Authorization ("What can you do?")
  │   ├── RBAC (role-based access control)
  │   ├── ABAC (attribute-based access control)
  │   ├── ACL (access control lists)
  │   └── Policy engines (OPA, Cedar)
  │
  ├── User Management
  │   ├── Registration / signup
  │   ├── Profile management
  │   ├── Password reset
  │   └── Account deactivation
  │
  ├── Session Management
  │   ├── Cookies / tokens
  │   ├── Session expiry
  │   └── Concurrent session limits
  │
  └── Audit / Compliance
      ├── Login logs
      ├── Access logs
      └── Compliance reporting
```

---

## How does volta fit in the IAM landscape?

volta-auth-proxy is an IAM solution, but it does not try to cover everything. Here is where it fits:

| IAM area | volta's coverage |
|----------|-----------------|
| **Authentication** | Google OIDC, session cookies, JWT issuance. SAML via IdP config (Phase 4). |
| **Authorization** | [RBAC](rbac.md) (OWNER / ADMIN / MEMBER roles) per tenant per app. |
| **User management** | User creation on first login, profile via API, admin management UI. |
| **Session management** | 8-hour sliding window, max 5 concurrent sessions, session listing/revocation. |
| **Multi-tenancy** | Core design feature: tenant resolution, tenant switching, member management. |
| **Audit** | Audit log with configurable sinks (Postgres, Kafka, Elasticsearch). |

What volta does NOT cover:

- Password management (volta uses Google OIDC, so Google handles passwords)
- LDAP/Active Directory federation
- Being a general-purpose OIDC provider for third-party apps
- Fine-grained policy engines (ABAC, OPA)

This is intentional. volta is built for one use case -- multi-tenant SaaS authentication and authorization -- and does it well. If you need a full-spectrum IAM platform, look at [Keycloak](keycloak.md) or enterprise solutions like Okta.

---

## IAM products compared

| Product | Type | Strengths | Weaknesses |
|---------|------|-----------|------------|
| **Auth0 / Okta** | Cloud [IDaaS](idaas.md) | Quick start, everything managed | Expensive at scale, lock-in |
| **Keycloak** | Open-source IAM server | Feature-complete, enterprise-ready | Heavy, complex config |
| **AWS IAM** | Cloud provider IAM | Deep AWS integration | AWS-only, not for end-user auth |
| **Ory Stack** | Open-source IAM | Modular, API-first | Multiple services to manage |
| **ZITADEL** | Open-source IAM | Native multi-tenancy | Newer, smaller community |
| **volta-auth-proxy** | Purpose-built auth gateway | Lightweight, simple, multi-tenant | Narrower feature set |

---

## Simple example

When you log in to a web app, IAM is working behind the scenes at every step:

```
  1. You visit app.example.com
     → IAM question: "Is this person authenticated?"
     → Answer: No session cookie found → redirect to login

  2. You click "Sign in with Google"
     → IAM action: Start OIDC authentication flow

  3. Google confirms your identity
     → IAM action: Create session, issue JWT

  4. You try to access the admin panel
     → IAM question: "Is this person authorized?"
     → Answer: User has MEMBER role, admin requires ADMIN → 403 Forbidden

  5. An admin promotes you to ADMIN
     → IAM action: Update role in database

  6. You try admin panel again
     → IAM question: "Is this person authorized?"
     → Answer: User has ADMIN role → 200 OK
```

Every one of these steps involves identity (who are you?) and access (what can you do?). That is IAM.

---

## Further reading

- [oidc.md](oidc.md) -- The specific protocol volta uses for authentication.
- [rbac.md](rbac.md) -- The authorization model volta uses.
- [idaas.md](idaas.md) -- Cloud-hosted IAM services.
- [auth0.md](auth0.md) -- A specific IDaaS provider.
- [keycloak.md](keycloak.md) -- An open-source IAM server.
