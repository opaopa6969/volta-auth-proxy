# SCIM (System for Cross-domain Identity Management)

[日本語版はこちら](scim.ja.md)

---

## What is it?

SCIM (System for Cross-domain Identity Management) is a standard protocol for automatically managing user accounts across multiple systems. When a company uses an identity provider like Okta or Azure AD, SCIM allows that provider to automatically create, update, and delete user accounts in all connected applications -- no manual work required.

Think of it like a school's enrollment system. When a new student enrolls, the school office does not manually create accounts in the library system, the cafeteria system, and the email system one by one. Instead, the enrollment system automatically tells all connected systems: "New student Alice, grade 5, lunch option B." If Alice transfers out, the enrollment system tells all systems to deactivate her. SCIM does the same thing for enterprise software.

SCIM defines a REST API with standardized endpoints (`/Users`, `/Groups`) and a JSON schema for representing identities. Any application that implements SCIM can receive user [provisioning](provisioning.md) commands from any compatible identity provider.

---

## Why does it matter?

Without SCIM, IT admins face a nightmare:

```
  Manual provisioning (without SCIM):
  ┌──────────┐
  │ IT Admin │──── Create account in App A
  │          │──── Create account in App B
  │          │──── Create account in App C
  │          │──── Create account in App D
  │          │──── ... (for every new employee)
  │          │
  │          │──── Employee leaves?
  │          │──── Delete from App A (maybe forgot App B...)
  └──────────┘

  Automated provisioning (with SCIM):
  ┌──────────┐     ┌─────────┐     ┌───────┐
  │ IT Admin │────►│  Okta   │────►│ App A │ (SCIM)
  │          │     │ (IdP)   │────►│ App B │ (SCIM)
  │          │     │         │────►│ App C │ (SCIM)
  │          │     │         │────►│ App D │ (SCIM)
  └──────────┘     └─────────┘     └───────┘
  (one action)     (auto-syncs all apps)
```

Key benefits:

- **Security**: Deprovisioned users are removed from ALL apps instantly
- **Compliance**: Audit trails show exactly when access was granted/revoked
- **Efficiency**: Onboarding 100 employees takes seconds, not hours
- **Accuracy**: No typos, no forgotten apps, no stale accounts

---

## How does it work?

### SCIM endpoints

SCIM 2.0 (RFC 7644) defines these standard endpoints:

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `POST` | `/scim/v2/Users` | Create a new user |
| `GET` | `/scim/v2/Users/{id}` | Get a specific user |
| `GET` | `/scim/v2/Users?filter=...` | Search users |
| `PUT` | `/scim/v2/Users/{id}` | Replace a user (full update) |
| `PATCH` | `/scim/v2/Users/{id}` | Partial update |
| `DELETE` | `/scim/v2/Users/{id}` | Delete a user |
| `POST` | `/scim/v2/Groups` | Create a group |
| `GET` | `/scim/v2/Groups/{id}` | Get a specific group |
| `PATCH` | `/scim/v2/Groups/{id}` | Update group membership |

### SCIM user schema

```json
{
  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
  "id": "user-uuid-in-volta",
  "externalId": "okta-user-id-12345",
  "userName": "alice@acme.com",
  "name": {
    "givenName": "Alice",
    "familyName": "Smith"
  },
  "emails": [
    {
      "value": "alice@acme.com",
      "primary": true
    }
  ],
  "active": true,
  "groups": [
    {
      "value": "group-uuid",
      "display": "Engineering"
    }
  ]
}
```

### The provisioning flow

```
  Okta (Identity Provider)              volta-auth-proxy (Service Provider)
  =========================              ==================================

  1. IT admin creates user "Alice" in Okta.
     Okta has volta configured as a SCIM app.

  2. Okta sends:
     POST /scim/v2/Users
     Authorization: Bearer <scim-api-token>
     {
       "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
       "userName": "alice@acme.com",
       "name": {"givenName": "Alice", "familyName": "Smith"},
       "emails": [{"value": "alice@acme.com", "primary": true}],
       "active": true
     }

  ──────────────────────────────────────────────────────────►

                                        3. volta creates user:
                                           - Creates account
                                           - Assigns to tenant
                                           - Sets default role (MEMBER)
                                           - Returns SCIM response

  ◄──────────────────────────────────────────────────────────

  4. Okta stores volta's user ID (externalId mapping).
     Future updates use this ID.

  === Later: Alice is deactivated in Okta ===

  5. Okta sends:
     PATCH /scim/v2/Users/{volta-user-id}
     {
       "schemas": [
         "urn:ietf:params:scim:api:messages:2.0:PatchOp"
       ],
       "Operations": [
         {"op": "replace", "path": "active", "value": false}
       ]
     }

  ──────────────────────────────────────────────────────────►

                                        6. volta deactivates user:
                                           - Revokes all sessions
                                           - Marks account inactive
                                           - User can no longer log in
```

### SCIM filtering

Identity providers query for existing users before creating them (to avoid duplicates):

```
  GET /scim/v2/Users?filter=userName eq "alice@acme.com"

  Response:
  {
    "schemas": ["urn:ietf:params:scim:api:messages:2.0:ListResponse"],
    "totalResults": 1,
    "Resources": [
      {
        "id": "volta-user-uuid",
        "userName": "alice@acme.com",
        ...
      }
    ]
  }
```

---

## How does volta-auth-proxy use it?

### SCIM endpoints in volta

volta implements SCIM 2.0 service provider endpoints under `/scim/v2/*`:

```
  volta-auth-proxy SCIM endpoints:
  ┌─────────────────────────────────────────────────┐
  │  POST   /scim/v2/Users          → Create user   │
  │  GET    /scim/v2/Users/:id      → Get user      │
  │  GET    /scim/v2/Users?filter=  → Search users   │
  │  PUT    /scim/v2/Users/:id      → Replace user   │
  │  PATCH  /scim/v2/Users/:id      → Update user    │
  │  DELETE /scim/v2/Users/:id      → Delete user    │
  │                                                  │
  │  POST   /scim/v2/Groups         → Create group   │
  │  GET    /scim/v2/Groups/:id     → Get group      │
  │  PATCH  /scim/v2/Groups/:id     → Update group   │
  │  DELETE /scim/v2/Groups/:id     → Delete group   │
  │                                                  │
  │  GET    /scim/v2/ServiceProviderConfig            │
  │  GET    /scim/v2/Schemas                          │
  │  GET    /scim/v2/ResourceTypes                    │
  └─────────────────────────────────────────────────┘
```

### Authentication for SCIM

SCIM requests from identity providers are authenticated using a [bearer token](bearer-scheme.md) specific to the SCIM integration. This token is generated when a tenant admin configures SCIM provisioning and is separate from user [session](session.md) tokens.

### Mapping SCIM to volta concepts

| SCIM concept | volta concept |
|-------------|--------------|
| User | Member of a [tenant](tenant.md) |
| Group | [Role](role.md) group |
| `active: false` | User [suspension](suspension.md) |
| `externalId` | Identity provider's user ID |
| `userName` | Email address (volta's primary identifier) |

### SCIM + webhooks

When SCIM operations create or update users, volta fires [webhook](webhook.md) events so your application stays in sync:

```
  Okta → SCIM POST /Users → volta creates user
                                  ↓
                            webhook: user.created
                                  ↓
                            Your app receives notification
```

---

## Common mistakes and attacks

### Mistake 1: Not implementing SCIM filtering

Identity providers rely on filtering to check if a user already exists. Without `filter=userName eq "..."`, every provisioning attempt creates duplicates.

### Mistake 2: Ignoring the `active` field

When an IdP sends `active: false`, you must revoke access immediately. Some implementations update the field but do not invalidate [sessions](session.md) -- the user stays logged in until the session expires naturally.

### Mistake 3: Treating SCIM DELETE as hard delete

Best practice is to soft-delete: mark the user inactive and retain data for compliance. The IdP may re-provision the same user later.

### Mistake 4: Not supporting PATCH

Some implementations only support PUT (full replacement). Modern IdPs prefer PATCH for efficiency. Okta specifically uses PATCH for deactivation.

### Attack: SCIM token theft

If the SCIM bearer token is stolen, an attacker can create admin users in your tenant. Defense: restrict SCIM endpoints to known IdP IP ranges, rotate tokens regularly, and log all SCIM operations.

### Attack: Mass deprovisioning

A compromised IdP account could deactivate all users via SCIM. Defense: rate limiting on SCIM endpoints, alerts on bulk operations, and a manual confirmation step for mass deprovisioning.

---

## Further reading

- [RFC 7644](https://tools.ietf.org/html/rfc7644) -- SCIM 2.0 protocol specification.
- [RFC 7643](https://tools.ietf.org/html/rfc7643) -- SCIM 2.0 core schema.
- [provisioning.md](provisioning.md) -- The broader concept of user provisioning.
- [tenant.md](tenant.md) -- How volta organizes users into tenants.
- [role.md](role.md) -- How SCIM groups map to volta roles.
- [webhook.md](webhook.md) -- Events fired when SCIM operations occur.
