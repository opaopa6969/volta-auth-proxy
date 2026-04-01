# Invitation Code

[日本語版はこちら](invitation-code.ja.md)

---

## What is it?

An invitation code is a unique, cryptographically random secret that allows a person to join a specific [tenant](tenant.md) in volta-auth-proxy. When a tenant admin invites someone, volta generates a 32-byte random value, encodes it as [base64url](base64.md), and sends it to the invitee. The invitee presents this code during registration to prove they were invited.

Think of it like a golden ticket to a private club. The club owner writes a unique code on a card and hands it to you. When you show up at the door, you present the card. The doorman checks that the code matches one in his book, it has not expired, and it has not been used before. If everything checks out, you are in. Nobody can guess the code because it is random, and nobody can reuse it because it is single-use.

Invitation codes are a critical part of volta's controlled [provisioning](provisioning.md) model. Unlike open registration (where anyone can sign up), invitations ensure that only explicitly invited people can join a tenant.

---

## Why does it matter?

In a multi-tenant SaaS application, you cannot let just anyone join any tenant:

```
  Open registration (dangerous for B2B):
  ┌──────────┐
  │ Anyone   │──── Signs up ────► Joins "Acme Corp" tenant?!
  │ on the   │                    (Who authorized this?)
  │ internet │
  └──────────┘

  Invitation-based (controlled):
  ┌──────────┐     ┌──────────┐     ┌──────────┐
  │ Acme     │────►│ volta    │────►│ Alice    │
  │ Admin    │     │ generates│     │ receives │
  │ invites  │     │ code     │     │ invite   │
  │ Alice    │     │          │     │ joins    │
  └──────────┘     └──────────┘     └──────────┘
  (authorized)     (secure code)    (verified)
```

Key benefits:

- **Controlled access**: Only invited people can join
- **Tenant isolation**: The code is bound to a specific tenant
- **Audit trail**: Who invited whom, when, and whether it was accepted
- **Expiry**: Codes expire, preventing stale invitations from being used months later
- **Single-use**: Each code can only be used once

---

## How does it work?

### Code generation

volta generates invitation codes using cryptographically secure random bytes:

```
  Step 1: Generate 32 bytes of random data
    SecureRandom.getBytes(32)
    → [0x7A, 0x3F, 0xB2, 0x91, ... 32 bytes total]

  Step 2: Encode as base64url (URL-safe, no padding)
    Base64.getUrlEncoder().withoutPadding().encode(bytes)
    → "ej-ykYH3mQ_7vKxN2bF..."

  Why 32 bytes?
    32 bytes = 256 bits of entropy
    Possible codes: 2^256 ≈ 1.16 × 10^77
    For comparison, atoms in the universe: ~10^80
    Brute force is impossible.
```

### The invitation flow

```
  Admin                    volta-auth-proxy              Invitee
  =====                    ================              =======

  1. POST /api/v1/tenants/{tid}/invitations
     {"email": "alice@example.com", "role": "MEMBER"}
  ──────────────────────────────────────►

                           2. Generate code:
                              code = base64url(SecureRandom(32))
                              Store in DB:
                              ┌────────────────────────────┐
                              │ invitation_id: inv-uuid     │
                              │ tenant_id: acme-uuid        │
                              │ email: alice@example.com    │
                              │ code_hash: SHA256(code)     │
                              │ role: MEMBER                │
                              │ invited_by: admin-uuid      │
                              │ expires_at: +7 days         │
                              │ used: false                 │
                              └────────────────────────────┘

                           3. Send invitation email with link:
                              https://app.com/invite?code=ej-ykYH3mQ_7vKxN...
                           ──────────────────────────────────────►

                                                          4. Click link
                                                          5. Complete registration

                           6. POST /auth/accept-invitation
                              {"code": "ej-ykYH3mQ_7vKxN..."}
  ◄────────────────────────────────────────────────────────────

                           7. Verify:
                              - Hash the presented code
                              - Find invitation by code_hash
                              - Check not expired
                              - Check not already used
                              - Check email matches
                              → Create user in tenant
                              → Mark invitation as used
                              → Fire invitation.accepted webhook
```

### Why hash the code in the database?

volta stores `SHA256(code)` in the database, not the raw code. This is the same principle as password hashing:

```
  If an attacker gets database access:

  Raw code stored:
    attacker reads code → can join as that user → BAD

  Hashed code stored:
    attacker reads hash → cannot reverse SHA256 → SAFE
    (the real code was only ever in the email)
```

### Code properties

| Property | Value | Reason |
|----------|-------|--------|
| Length | 32 bytes (256 bits) | Impossible to brute force |
| Encoding | [base64url](base64.md) | Safe in URLs, no padding issues |
| Storage | SHA256 hash only | Database breach does not leak codes |
| Expiry | 7 days (configurable) | Prevents stale invitations |
| Usage | Single-use | Prevents code sharing/reuse |
| Binding | Email + tenant | Code only works for intended recipient |

---

## How does volta-auth-proxy use it?

### Invitation API

```
  Create invitation:
    POST /api/v1/tenants/{tid}/invitations
    Authorization: Bearer <admin-session>
    {
      "email": "alice@example.com",
      "role": "MEMBER"
    }

    Response:
    {
      "invitation_id": "inv-uuid",
      "email": "alice@example.com",
      "role": "MEMBER",
      "expires_at": "2026-04-08T12:00:00Z",
      "invite_url": "https://app.com/invite?code=ej-ykYH3mQ_7vKxN..."
    }

  Accept invitation:
    POST /auth/accept-invitation
    {
      "code": "ej-ykYH3mQ_7vKxN...",
      "name": "Alice Smith",
      "password": "str0ng-p@ssword"
    }
```

### Invitation states

```
  ┌──────────┐     ┌──────────┐     ┌──────────┐
  │ PENDING  │────►│ ACCEPTED │     │ EXPIRED  │
  │          │     │          │     │          │
  │ Created, │     │ User     │     │ TTL      │
  │ awaiting │     │ joined   │     │ exceeded │
  │ response │     │ tenant   │     │          │
  └──────────┘     └──────────┘     └──────────┘
       │                                  ▲
       │           ┌──────────┐           │
       └──────────►│ REVOKED  │           │
                   │          │     (auto-transition
                   │ Admin    │      after expiry)
                   │ cancelled│
                   └──────────┘
```

### Integration with other volta features

- **[Roles](role.md)**: The invitation specifies which role the new user gets
- **[Tenant](tenant.md)**: The code is bound to a specific tenant
- **[Webhooks](webhook.md)**: `invitation.accepted` event fired via [outbox](outbox-pattern.md)
- **[Session](session.md)**: After accepting, the user gets a session immediately
- **[SCIM](scim.md)**: SCIM [provisioning](provisioning.md) is an alternative to invitations for enterprise

---

## Common mistakes and attacks

### Mistake 1: Using short or predictable codes

A 6-digit numeric code has only 1 million possibilities -- trivially brute-forced. Always use cryptographically random codes with sufficient entropy (256 bits).

### Mistake 2: Storing raw codes in the database

If someone dumps the database, they get all pending invitation codes. Always store SHA256 hashes.

### Mistake 3: No expiry

An invitation from 6 months ago should not still work. Set reasonable expiry times (volta: 7 days default).

### Mistake 4: Not binding to email

If the code is not bound to an email, anyone who intercepts it can use it. volta verifies that the email of the registering user matches the invitation email.

### Mistake 5: Allowing reuse

If a code can be used multiple times, sharing it on social media would let anyone join the tenant.

### Attack: Brute force code guessing

An attacker tries random codes against the accept-invitation endpoint. Defense: 32 bytes of entropy makes this impossible (2^256 possibilities), plus rate limiting on the endpoint.

### Attack: Email interception

If the invitation email is intercepted (e.g., compromised email account), the attacker can use the code. Defense: codes expire quickly, admins can revoke pending invitations, and accepted invitations trigger webhooks for monitoring.

---

## Further reading

- [invitation-flow.md](invitation-flow.md) -- The complete invitation and registration flow.
- [base64.md](base64.md) -- How invitation codes are encoded.
- [tenant.md](tenant.md) -- The tenant that invitation codes grant access to.
- [role.md](role.md) -- Roles assigned via invitation.
- [provisioning.md](provisioning.md) -- Invitations as a provisioning method.
- [session.md](session.md) -- Session created after invitation acceptance.
