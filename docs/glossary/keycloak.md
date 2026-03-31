# Keycloak

[日本語版はこちら](keycloak.ja.md)

---

## What is it?

Keycloak is a free, open-source Identity and Access Management (IAM) system originally developed by Red Hat that handles login, user management, and access control for applications.

Think of it like a Swiss Army knife for authentication. It has a blade, a screwdriver, a can opener, a corkscrew, scissors, and 15 other tools you did not ask for. It can do almost anything related to authentication. But it weighs a lot, it is hard to find the tool you need, and sometimes you just wanted a simple knife.

---

## Why does it matter?

Keycloak is the most well-known open-source alternative to cloud services like [Auth0](auth0.md). When teams want to [self-host](self-hosting.md) their auth instead of paying per-[MAU](mau.md), Keycloak is usually the first thing they consider. Understanding what Keycloak does well and where it struggles explains why volta-auth-proxy was built differently.

---

## What Keycloak does well

| Strength | Detail |
|----------|--------|
| **Feature-complete** | OIDC, SAML, LDAP, social login, MFA, user federation, admin console, account management -- it has everything. |
| **Enterprise-proven** | Used by large organizations. Battle-tested in production for years. |
| **Open source** | Free to use, free to modify. Apache 2.0 license. |
| **Standards-compliant** | Full OIDC and SAML 2.0 support. Works as an IdP for any standards-compliant app. |
| **Active community** | Large community, regular releases, extensive documentation. |
| **Red Hat backing** | Professional support available through Red Hat SSO (the commercial version). |

If your requirement is "I need a fully featured identity server that implements every standard," Keycloak is hard to beat.

---

## What's problematic

### 1. Resource heavy

Keycloak is built on the Quarkus framework (previously WildFly) and requires significant resources:

```
  Keycloak:                          volta-auth-proxy:
  ┌────────────────────┐             ┌────────────────────┐
  │  RAM: ~512MB+      │             │  RAM: ~30MB        │
  │  Startup: ~30s     │             │  Startup: ~200ms   │
  │  Docker image: big │             │  Docker image: small│
  └────────────────────┘             └────────────────────┘
```

For a small SaaS that just needs "Google login + multi-tenancy," Keycloak is like using a bulldozer to plant a flower.

### 2. Configuration hell

Keycloak has hundreds of configuration options organized into "realms," "clients," "roles," "flows," "authenticators," "mappers," and more. A typical realm export (`realm.json`) can be 500+ lines:

```json
{
  "realm": "my-saas",
  "enabled": true,
  "sslRequired": "external",
  "registrationAllowed": false,
  "loginWithEmailAllowed": true,
  "duplicateEmailsAllowed": false,
  "resetPasswordAllowed": false,
  "editUsernameAllowed": false,
  "bruteForceProtected": true,
  "permanentLockout": false,
  "maxFailureWaitSeconds": 900,
  "minimumQuickLoginWaitSeconds": 60,
  "waitIncrementSeconds": 60,
  "quickLoginCheckMilliSeconds": 1000,
  "maxDeltaTimeSeconds": 43200,
  "failureFactor": 30,
  "defaultRoles": ["offline_access", "uma_authorization"],
  "requiredCredentials": ["password"],
  "otpPolicyType": "totp",
  "otpPolicyAlgorithm": "HmacSHA1",
  // ... hundreds more lines ...
}
```

Compare this with volta's entire configuration:

```yaml
# volta-config.yaml -- that's it
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
```

See [config-hell.md](config-hell.md) for more on this problem.

### 3. Theme customization pain (FreeMarker)

Keycloak's login pages use FreeMarker templates. FreeMarker is a Java template engine from 2002. Customizing Keycloak's login page means:

1. Finding the correct theme folder in Keycloak's directory structure
2. Learning FreeMarker syntax (which is not intuitive)
3. Overriding specific template files while inheriting from the base theme
4. Dealing with Keycloak's CSS class structure
5. Rebuilding/restarting when you make changes

Many developers have described this process as "painful" or "nightmarish." There is even a popular open-source project (Keycloakify) whose entire purpose is making Keycloak themes less terrible.

volta uses [jte](jte.md) templates with full control. You edit an HTML file, refresh, and see your changes.

### 4. Multi-tenancy limitations

Keycloak's concept of "realms" is its multi-tenancy model. Each tenant gets a separate realm. But:

- Creating a new realm means creating a full isolated environment (users, clients, roles, everything)
- Cross-realm operations are limited
- Realm management is administrative, not self-service
- At 100+ realms, Keycloak's admin console becomes slow

volta's multi-tenancy is built into the core data model. Users, tenants, and roles are all in the same PostgreSQL database with proper foreign keys. Tenant creation is an API call, not an admin operation.

---

## Why volta chose not to use Keycloak

| Concern | Keycloak | volta |
|---------|----------|-------|
| Memory | ~512MB+ | ~30MB |
| Startup | ~30s | ~200ms |
| Configuration | Hundreds of settings | .env + 1 YAML file |
| Login UI | FreeMarker templates | [jte](jte.md) templates (type-safe, modern) |
| Multi-tenancy | Realm-based (heavy) | Native (lightweight) |
| Dependencies | Keycloak server + Postgres | Just Postgres |
| Learning curve | Steep (weeks to be productive) | Shallow (hours to understand) |

volta is not "Keycloak lite." volta is a different approach entirely. Instead of being a general-purpose identity server, volta is purpose-built for one use case: multi-tenant SaaS auth with ForwardAuth. It does less, but what it does, it does simply and efficiently.

---

## When Keycloak makes sense

- You need full SAML 2.0 IdP support for enterprise SSO
- You need LDAP/Active Directory federation
- You are in a Red Hat / JBoss ecosystem and want vendor support
- You need to be a full identity provider for third-party applications
- You have dedicated ops staff to manage Keycloak

---

## When volta makes more sense

- You are building a multi-tenant SaaS and want lightweight auth
- You want login pages you can actually customize easily
- You do not want to learn a 500-setting configuration system
- You want something that starts in 200ms, not 30 seconds
- You need a small footprint (~30MB, not ~512MB)

---

## Further reading

- [config-hell.md](config-hell.md) -- Why volta avoids configuration complexity.
- [iam.md](iam.md) -- The broader IAM landscape where Keycloak fits.
- [auth0.md](auth0.md) -- The cloud-hosted alternative to Keycloak.
- [jte.md](jte.md) -- The template engine volta uses instead of FreeMarker.
- [self-hosting.md](self-hosting.md) -- Self-hosting trade-offs (Keycloak and volta are both self-hostable).
