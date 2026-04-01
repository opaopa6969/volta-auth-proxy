# Okta

[日本語版はこちら](okta.ja.md)

---

## What is it?

Okta is an enterprise identity platform that provides single sign-on (SSO), multi-factor authentication (MFA), and user lifecycle management as a cloud service. It is one of the largest dedicated identity providers in the world, used by over 18,000 organizations including major enterprises, governments, and universities.

Think of Okta like a corporate ID badge system run by an external security company. Instead of each office building managing its own badges, a single company (Okta) issues and manages badges for all buildings. When an employee joins, they get one badge. When they leave, one badge is revoked. That one badge opens every door they are authorized to enter.

Okta supports both [SAML](sso.md) and [OIDC](oidc.md) protocols, making it compatible with virtually any modern application. In the enterprise world, Okta is the default answer to "how do we manage 10,000 employees across 200 applications?"

---

## Why does it matter?

Okta matters in the volta-auth-proxy context for two reasons:

1. **As an upstream IdP**: In Phase 3, volta can integrate with Okta as an identity provider. Enterprise customers who already use Okta expect to connect it to every SaaS tool they adopt. If volta cannot work with Okta, those customers will not adopt it.

2. **As a competitive reference point**: Okta represents the "buy, not build" approach to identity. Understanding Okta helps explain why volta exists -- for organizations where Okta's pricing ($2-$15/user/month), cloud dependency, and black-box nature are unacceptable.

### The enterprise IdP landscape

| Provider | Type | Strength | Price range |
|----------|------|----------|-------------|
| Okta | Cloud SaaS | Enterprise SSO, lifecycle mgmt | $2-15/user/month |
| [Auth0](auth0.md) | Cloud SaaS (Okta subsidiary) | Developer-friendly, B2C/B2B | $23-240/month + per-user |
| [Active Directory](active-directory.md) | On-premise / Azure AD | Microsoft ecosystem | Included with Microsoft 365 |
| [Google Workspace](google-workspace.md) | Cloud SaaS | Google ecosystem | Included with Workspace |
| [Keycloak](keycloak.md) | Self-hosted | Free, open source | Free (you pay for infra) |

---

## How does it work?

### Okta as an SSO hub

```
  Employee
     │
     │  1. Goes to any app (Slack, Salesforce, your SaaS)
     ▼
  ┌──────────────┐
  │  Application  │
  │              │  2. Redirects to Okta
  └──────┬───────┘
         │
         ▼
  ┌──────────────┐
  │    Okta      │
  │              │  3. User authenticates (password + MFA)
  │              │  4. Okta issues SAML assertion or OIDC token
  └──────┬───────┘
         │
         ▼
  ┌──────────────┐
  │  Application  │
  │              │  5. Receives identity, grants access
  └──────────────┘
```

### Key features

| Feature | Description |
|---------|-------------|
| **Universal Directory** | Central user store that syncs with AD, LDAP, HR systems |
| **SSO** | One login for all applications (SAML, OIDC, WS-Fed) |
| **Adaptive MFA** | Risk-based MFA (device, location, behavior patterns) |
| **Lifecycle Management** | Auto-provision/deprovision users across apps (SCIM) |
| **API Access Management** | OAuth2 server for API authorization |
| **Workflows** | No-code automation for identity events |

### SAML vs OIDC with Okta

Okta supports both protocols. The choice depends on the application:

| Protocol | When to use | volta relevance |
|----------|-------------|-----------------|
| **SAML 2.0** | Enterprise apps (Salesforce, ServiceNow). XML-based. Mature. | Phase 3: volta accepts SAML assertions from Okta |
| **[OIDC](oidc.md)** | Modern apps, APIs, SPAs. JSON/JWT-based. Lighter. | Phase 3: volta uses Okta as OIDC provider |

### SCIM provisioning

SCIM (System for Cross-domain Identity Management) is how Okta automatically creates and deletes user accounts in applications:

```
  HR System: "Alice was hired"
       │
       ▼
  Okta: Create user Alice
       │
       ├──► SCIM POST /Users to Slack → Alice gets Slack account
       ├──► SCIM POST /Users to Salesforce → Alice gets Salesforce account
       └──► SCIM POST /Users to volta → Alice gets volta account
```

When Alice leaves:
```
  HR System: "Alice was terminated"
       │
       ▼
  Okta: Deactivate user Alice
       │
       ├──► SCIM PATCH /Users/alice to Slack → Account deactivated
       ├──► SCIM PATCH /Users/alice to Salesforce → Account deactivated
       └──► SCIM PATCH /Users/alice to volta → Account deactivated
```

---

## How does volta-auth-proxy use it?

volta-auth-proxy plans to integrate with Okta in **Phase 3** as an upstream identity provider. This means enterprise customers who use Okta can configure volta to accept Okta identities.

### Phase 3 integration plan

```
  Employee ──► volta-auth-proxy ──► Okta (as OIDC/SAML IdP)
                    │                    │
                    │                    └── Authenticates the user
                    │
                    ├── Receives identity (email, groups, roles)
                    ├── Maps to volta tenant + user
                    ├── Creates volta session
                    └── Issues volta JWT
```

### Why this matters for enterprise customers

Enterprise customers typically mandate: "All authentication must go through Okta." They will not adopt a SaaS tool that requires a separate login. By integrating with Okta:

1. Employees use their existing Okta credentials -- no new password
2. IT admins manage access from the Okta dashboard -- no separate volta admin
3. When an employee leaves, Okta deactivation cascades to volta via SCIM
4. MFA is handled by Okta (which their security team already trusts)

### Why volta does not just use Okta for everything

Okta is an external dependency with per-user pricing. volta's philosophy is self-hosted and free. volta uses Okta as an IdP source (like it uses Google), but the session management, tenant resolution, and authorization logic remain inside volta.

---

## Common mistakes and attacks

### Mistake 1: Not validating the Okta token properly

When Okta issues a SAML assertion or OIDC token, the application must verify the signature, issuer, audience, and expiration. Skipping any of these checks allows token forgery.

### Mistake 2: Granting too broad Okta API permissions

Okta API tokens have scopes. Granting `okta.users.manage` when you only need `okta.users.read` means a compromised token can modify user accounts.

### Mistake 3: Not implementing SCIM deprovisioning

If you integrate Okta SSO but not SCIM deprovisioning, terminated employees retain access. Their Okta login is disabled, but if they have a cached session or local password, they can still get in.

### Mistake 4: Ignoring group-based access

Okta groups are the standard way to control which users can access which applications. If you allow "all Okta users" instead of specific groups, you may grant access to contractors, interns, or other unintended users.

### Attack: Okta session hijacking (2022 Lapsus$ incident)

In 2022, the Lapsus$ group compromised an Okta support engineer's machine and used their access to reset customer passwords. This demonstrated that even identity providers can be compromised. Defense in depth -- not relying solely on Okta for security -- is essential.

---

## Further reading

- [Okta developer documentation](https://developer.okta.com/docs/) -- Official reference.
- [Okta OIDC guide](https://developer.okta.com/docs/concepts/oauth-openid/) -- How Okta implements OIDC.
- [Okta SAML guide](https://developer.okta.com/docs/concepts/saml/) -- How Okta implements SAML.
- [oidc.md](oidc.md) -- The protocol volta uses with Okta.
- [sso.md](sso.md) -- Single sign-on concepts.
- [idp.md](idp.md) -- What an Identity Provider is.
- [active-directory.md](active-directory.md) -- Microsoft's enterprise IdP.
- [auth0.md](auth0.md) -- Okta's developer-focused subsidiary.
