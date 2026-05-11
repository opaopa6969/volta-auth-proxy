# Provisioning

[日本語版はこちら](provisioning.ja.md)

---

## What is it?

Provisioning is the process of automatically creating, updating, and deactivating user accounts in an application based on commands from an external identity source. When an employee joins a company, provisioning creates their accounts in all connected systems. When they leave, deprovisioning disables those accounts everywhere.

Think of it like a hotel reservation system. When you book a room, the hotel does not just put your name on a list -- it provisions your stay: a room key is programmed, the minibar is stocked, your name appears on the welcome screen, and the Wi-Fi password is generated. When you check out, everything is deprovisioned: the key stops working, your name is removed, and the room is prepared for the next guest. Provisioning in software works the same way -- setting up everything a user needs to start working.

In the identity management world, provisioning is typically automated via [SCIM](scim.md) (a standard protocol) or JIT (Just-In-Time) provisioning during first login.

---

## Why does it matter?

Manual account management does not scale and creates security risks:

```text
Manual provisioning (the nightmare):

  New hire
  "Alice"

  IT Admin's to-do list:

  □ Create account in email system
  □ Create account in Slack
  □ Create account in GitHub
  □ Create account in volta (your SaaS app)
  □ Create account in 12 more systems...

  Time: 2 hours per employee
  Error rate: "forgot GitHub" happens weekly

Automated provisioning:

  New hire       >  Okta      >  All systems
  "Alice"           (IdP)        (via SCIM)

Time: 30 seconds. Error rate: 0%.
```

Key benefits:

- **Speed**: Onboarding minutes instead of hours
- **Security**: Offboarded users lose access instantly -- no forgotten accounts
- **Consistency**: Every user gets the same setup, every time
- **Compliance**: Audit trail of every provision/deprovision action
- **Scale**: Works the same for 10 users or 10,000

---

## How does it work?

### Provisioning lifecycle

```text
  PROVISION       >    UPDATE          >  DEPROVISION

Create user          Change role          Deactivate
Assign role          Update name          Revoke access
Grant access         Move team            Clean up

               volta-auth-proxy
 users table: active=true    role changed    active=false
 sessions: created           sessions: valid  sessions: revoked
 webhook: user.created       webhook: role    webhook: user.deleted
```

### Provisioning methods

| Method | Trigger | Protocol | Use case |
|--------|---------|----------|----------|
| SCIM provisioning | IdP pushes changes | [SCIM](scim.md) 2.0 REST API | Enterprise SSO + user management |
| JIT provisioning | User's first login | [OAuth2](oauth2.md) callback | Self-service apps, smaller teams |
| [Invitation](invitation-code.md) | Admin sends invite | volta invitation API | Controlled onboarding, B2B |
| API provisioning | Admin creates user | volta REST API | Custom integrations |

### SCIM provisioning flow

```text
Okta                          volta-auth-proxy
====                          ================

Admin assigns user to app

POST /scim/v2/Users                          >
{                                              volta:
  "userName": "alice@acme.com",                1. Create user record
  "name": {...},                               2. Assign to tenant
  "active": true                               3. Set default role
}                                              4. Fire user.created webhook

                             201 Created
                             {"id": "volta-uuid", ...}

Admin changes user's group

PATCH /scim/v2/Users/volta-uuid               >
{                                              volta:
  "Operations": [{                             1. Update role
    "op": "replace",                           2. Fire role_changed webhook
    "path": "groups",
    "value": [{"value": "admin-group"}]
  }]
}

                             200 OK

Admin removes user from app

PATCH /scim/v2/Users/volta-uuid               >
{                                              volta:
  "Operations": [{                             1. Set active=false
    "op": "replace",                           2. Revoke all sessions
    "path": "active",                          3. Fire user.deleted webhook
    "value": false
  }]
}

                             200 OK
```

### JIT (Just-In-Time) provisioning flow

```text
User                  volta-auth-proxy         Identity Provider
====                  ================         =================

First login

                     Redirect to IdP

                                               User authenticates

                     Receives OAuth callback with user info

                     User exists in volta?
                       NO → Create user (JIT provision)
                            Assign default role
                            Fire user.created webhook
                       YES → Update if attributes changed

Logged in (new account ready)
```

---

## How does volta-auth-proxy use it?

### Current provisioning methods

volta supports three provisioning methods:

1. **Invitation-based** (Phase 1): Admin sends [invitation code](invitation-code.md), user signs up with it
2. **SCIM-based** (Phase 3): Identity provider pushes users via [SCIM](scim.md) endpoints
3. **JIT** (Phase 1): First OAuth login auto-creates user in tenant

### Provisioning and tenant membership

Every provisioned user belongs to a [tenant](tenant.md). volta does not allow "free-floating" users:

```text
Provisioning always answers two questions:
1. WHO is the user? (email, name, identity)
2. WHERE do they belong? (tenant_id)

   User: alice@acme.com
   Tenant: acme-corp (tenant_id)
   Role: MEMBER (default)
   Source: SCIM / Invitation / JIT
   Active: true
```

### Deprovisioning and security

When a user is deprovisioned (via SCIM `active: false` or admin action):

1. User record is marked `active: false` ([suspension](suspension.md))
2. All active [sessions](session.md) are immediately revoked
3. All [cookies](cookie.md) are invalidated
4. The `user.deleted` [webhook](webhook.md) is fired via the [outbox](outbox-pattern.md)
5. The user cannot log in again until re-provisioned

### Provisioning events and webhooks

Every provisioning action fires a [webhook](webhook.md) event so your [upstream](upstream.md) app can react:

| Provisioning action | Webhook event | Your app should... |
|--------------------|---------------|-------------------|
| New user created | `user.created` | Create app-level profile |
| User role changed | `user.role_changed` | Update permissions |
| User deactivated | `user.deleted` | Archive user data |
| User reactivated | `user.created` | Restore user profile |

---

## Common mistakes and attacks

### Mistake 1: No deprovisioning process

Provisioning without deprovisioning is a security hole. Former employees retain access to your system. Always implement both halves of the lifecycle.

### Mistake 2: JIT provisioning without tenant assignment

If a user logs in via OAuth and you JIT-provision them without a tenant, they exist in your system with no context. Always require a tenant assignment during provisioning (via invitation code, domain matching, or admin approval).

### Mistake 3: Not revoking sessions on deprovision

Setting `active: false` is not enough if existing sessions remain valid. Always revoke all sessions when deprovisioning.

### Mistake 4: Allowing self-registration without controls

If anyone can provision themselves (e.g., public signup), you may get spam accounts or unauthorized access. Use invitations or domain-based restrictions.

### Attack: Provision escalation

An attacker with SCIM access provisions themselves as an ADMIN. Defense: SCIM-provisioned users get the minimum [role](role.md) by default. Admin promotion requires a separate approval flow.

### Attack: Ghost account exploitation

A deprovisioned user whose data was not cleaned up may have residual access tokens or API keys. Defense: revoke all tokens and sessions at deprovision time, not just set `active: false`.

---

## Further reading

- [scim.md](scim.md) -- The protocol used for automated provisioning.
- [invitation-code.md](invitation-code.md) -- Invitation-based provisioning.
- [invitation-flow.md](invitation-flow.md) -- The complete invitation and provisioning flow.
- [tenant.md](tenant.md) -- Every provisioned user belongs to a tenant.
- [suspension.md](suspension.md) -- Deprovisioning via suspension.
- [role.md](role.md) -- Roles assigned during provisioning.
