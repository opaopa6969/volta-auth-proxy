# Active Directory

[日本語版はこちら](active-directory.ja.md)

---

## What is it?

Active Directory (AD) is Microsoft's directory service for managing users, computers, and resources in a network. Originally built for on-premise Windows networks in 2000, it has evolved into two distinct products: **Active Directory Domain Services (AD DS)** for on-premise and **Microsoft Entra ID** (formerly Azure Active Directory / Azure AD) for the cloud.

Think of Active Directory like a giant employee phonebook combined with a security system. It knows who works at the company, what department they are in, what computers they can log into, what files they can access, and what printers they can use. When you log into a Windows PC at work and see your name, desktop settings, and network drives -- Active Directory made that happen.

For the modern cloud world, Microsoft Entra ID extends this concept to SaaS applications. Instead of just managing access to on-premise file shares, it manages access to Salesforce, Slack, and -- through [OIDC](oidc.md) or [SAML](sso.md) -- your SaaS application protected by volta-auth-proxy.

---

## Why does it matter?

Active Directory is the most widely deployed enterprise identity system in the world. Over 90% of Fortune 1000 companies use it. If you build a SaaS product for enterprise customers, you will inevitably face the question: "Does it work with Active Directory?"

This matters for volta-auth-proxy because:

1. **Enterprise adoption**: When a company evaluates your SaaS, their IT team will ask if users can log in with their existing AD/Entra ID credentials
2. **SAML/SCIM integration**: Entra ID supports [SAML](sso.md) and [SCIM](okta.md) -- the enterprise standards for SSO and user provisioning
3. **Compliance**: Many regulated industries require all authentication to go through AD/Entra ID for audit and policy enforcement

---

## How does it work?

### On-premise AD (AD DS)

```
  ┌─────────────────────────────────────┐
  │     Corporate Network                │
  │                                      │
  │  ┌──────────────────────┐           │
  │  │  Domain Controller    │           │
  │  │  (Active Directory)   │           │
  │  │                       │           │
  │  │  Users, Groups,       │           │
  │  │  Computers, Policies  │           │
  │  └──────────┬────────────┘           │
  │             │ LDAP / Kerberos        │
  │             │                        │
  │  ┌──────────┼────────────┐          │
  │  │          │             │          │
  │  ▼          ▼             ▼          │
  │ Windows   File Server   Printer     │
  │ PC                                   │
  └─────────────────────────────────────┘
```

Key protocols:

| Protocol | Purpose |
|----------|---------|
| **LDAP** | Querying the directory (find users, groups, attributes) |
| **Kerberos** | Authentication (ticket-based, no passwords over the network) |
| **NTLM** | Legacy authentication (weaker, being phased out) |
| **Group Policy** | Enforcing settings on computers and users |

### Cloud: Microsoft Entra ID (Azure AD)

```
  ┌───────────────────────────────────────────┐
  │          Microsoft Entra ID (Cloud)        │
  │                                            │
  │  Users, Groups, App Registrations          │
  │  Conditional Access Policies               │
  │  MFA, Identity Protection                  │
  │                                            │
  │     SAML / OIDC / SCIM                     │
  │         │         │         │              │
  └─────────┼─────────┼─────────┼──────────────┘
            │         │         │
            ▼         ▼         ▼
         Microsoft  Salesforce  Your SaaS
         365                    (via volta)
```

### Hybrid: AD Connect

Most enterprises run both on-premise AD and cloud Entra ID, synchronized via **AD Connect**:

```
  On-premise AD ◄──── AD Connect (sync) ────► Microsoft Entra ID
  (Kerberos/LDAP)                              (SAML/OIDC/SCIM)
       │                                            │
       ▼                                            ▼
  Internal apps                              SaaS apps (via volta)
```

### Comparison with other IdPs

| Feature | AD DS (on-prem) | Entra ID (cloud) | [Okta](okta.md) | [Google Workspace](google-workspace.md) |
|---------|----------------|-------------------|------|-----------------|
| LDAP | Native | Via proxy | Via adapter | Via LDAP service |
| SAML | Via ADFS | Built-in | Built-in | Built-in |
| OIDC | Via ADFS | Built-in | Built-in | Built-in |
| SCIM | Manual | Built-in | Built-in | Built-in |
| Kerberos | Native | N/A | N/A | N/A |
| Conditional Access | Group Policy | Built-in (powerful) | Adaptive MFA | Context-Aware Access |
| Primary ecosystem | Windows/Microsoft | Microsoft 365 | Any | Google |

---

## How does volta-auth-proxy use it?

volta-auth-proxy plans to support Microsoft Entra ID as an upstream IdP in **Phase 3**, using SAML and/or OIDC. This enables enterprise customers whose employees authenticate through Microsoft to use volta-protected applications without a separate login.

### Integration architecture (Phase 3)

```
  Employee ──► volta-auth-proxy
                    │
                    │  SAML or OIDC
                    ▼
              Microsoft Entra ID
                    │
                    │  Authenticates user
                    │  Returns: email, groups, tenant info
                    │
                    ▼
              volta-auth-proxy
                    │
                    ├── Map Entra groups to volta roles
                    ├── Resolve volta tenant
                    ├── Create/update user
                    ├── Create session
                    └── Issue volta JWT
```

### SCIM integration (Phase 3+)

Beyond SSO, SCIM integration allows Entra ID to automatically provision and deprovision volta users:

| Entra ID Event | SCIM Operation | volta Action |
|----------------|---------------|--------------|
| User assigned to app | POST /Users | Create volta user in tenant |
| User updated (name, role) | PATCH /Users/{id} | Update volta user |
| User removed from app | DELETE /Users/{id} | Deactivate volta user |
| Group membership changed | PATCH /Groups/{id} | Update volta role mapping |

### Why not directly use Entra ID for everything?

Entra ID is an excellent IdP, but volta adds value on top:

1. **Multi-tenancy**: Entra ID does not know about your SaaS tenants. volta maps Entra identities to tenants.
2. **Cross-IdP support**: One tenant might use Entra ID, another Google, another [Okta](okta.md). volta normalizes them all.
3. **Self-hosted control**: volta's session management, rate limiting, and authorization logic run on your servers.
4. **No per-user cloud fees**: Entra ID has per-user pricing for premium features. volta is free.

---

## Common mistakes and attacks

### Mistake 1: Confusing AD DS and Entra ID

They are different products. AD DS is on-premise (LDAP/Kerberos). Entra ID is cloud (SAML/OIDC). Many developers say "Active Directory" when they mean Entra ID. Ask: "Are we integrating with on-premise AD or cloud Entra ID?"

### Mistake 2: Not mapping groups to roles

Entra ID sends group membership in the SAML assertion or OIDC token. If you ignore groups and just check "is this user authenticated?", all Entra users get the same access regardless of their role.

### Mistake 3: Not handling nested groups

In AD, groups can contain other groups. User A is in Group X, which is in Group Y. If your application only checks direct group membership, User A will not appear to be in Group Y. Handle recursion.

### Mistake 4: Trusting the email claim without verification

Entra ID allows custom domains. An attacker who compromises a tenant could configure a domain they control and create users with your domain's email addresses. Always verify the `tid` (tenant ID) claim, not just the email.

### Attack: Token replay across tenants

If you accept Entra ID tokens from any tenant (multi-tenant app registration), an attacker in their own Entra tenant could obtain a valid token and attempt to access your application. Restrict accepted tenant IDs in your OIDC configuration.

---

## Further reading

- [Microsoft Entra ID documentation](https://learn.microsoft.com/en-us/entra/identity/) -- Official reference.
- [SAML SSO with Entra ID](https://learn.microsoft.com/en-us/entra/identity/enterprise-apps/what-is-single-sign-on) -- SSO setup guide.
- [SCIM provisioning with Entra ID](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/use-scim-to-provision-users-and-groups) -- Automatic user provisioning.
- [okta.md](okta.md) -- Competing enterprise IdP.
- [google-workspace.md](google-workspace.md) -- Google's enterprise IdP.
- [sso.md](sso.md) -- Single sign-on concepts.
- [oidc.md](oidc.md) -- The protocol used for cloud integration.
- [idp.md](idp.md) -- What an Identity Provider is.
