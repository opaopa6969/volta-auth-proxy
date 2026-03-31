# Invitation Flow

[日本語版はこちら](invitation-flow.ja.md)

---

## What is it in one sentence?

An invitation flow is the step-by-step process by which someone is invited to join a workspace, from receiving the invitation link to becoming a full member of the team.

---

## The housewarming party analogy

Think about inviting someone to your new apartment:

1. **You send an invitation** -- "Hey, come visit! Here's my address and the door code."
2. **They show up** -- They arrive at the building and identify themselves to the doorman.
3. **They agree to house rules** -- "Please take off your shoes and don't touch the cat."
4. **They're in** -- Now they can hang out, eat snacks, and use the bathroom.

In software, inviting someone to a workspace follows a very similar pattern:

1. **Admin sends an invitation** -- An email or link with a special code.
2. **The person logs in** -- They prove who they are (authentication).
3. **They accept the invitation** -- They agree to join the workspace.
4. **They become a member** -- Now they can use the workspace with the role they were given.

---

## Why does an invitation flow matter?

You might think: "Why not just let anyone join any workspace?" Here is why that would be a disaster:

- **Security** -- You do not want random people accessing your company's data. Only people specifically invited should be able to join.
- **Role assignment** -- The person inviting decides what role the new member gets. An intern should not accidentally get OWNER access.
- **Audit trail** -- You need to know who invited whom and when. If something goes wrong, you can trace it back.
- **Controlled growth** -- Workspaces might have member limits (volta enforces `max_members` per tenant plan).

---

## The invitation flow in volta, step by step

Here is exactly what happens when someone is invited to a volta workspace:

```
  Step 1: Admin creates invitation
  ─────────────────────────────────
  Kenji (ADMIN of ACME Corp) opens the admin panel and types:
    Email: mika@example.com
    Role:  MEMBER

  volta creates an invitation record with:
    - A unique invitation code
    - The target email
    - The assigned role
    - An expiration time (invitations don't last forever)
    - Who sent it (Kenji)

  Step 2: Invitation is sent
  ──────────────────────────
  volta sends an email to mika@example.com:
    "Kenji invited you to join ACME Corp.
     Click here to accept: https://volta.example.com/invite/abc123"

  Step 3: Mika clicks the link
  ─────────────────────────────
  Mika clicks the link in the email.
  volta: "Is Mika logged in?"
    - If yes → go to Step 4
    - If no  → redirect to login page, then come back

  Step 4: Mika logs in (if needed)
  ─────────────────────────────────
  Mika logs in with Google (or whatever method is configured).
  volta: "OK, you are mika@example.com. Verified."

  Step 5: Consent / acceptance
  ─────────────────────────────
  volta shows a page:
    "You've been invited to ACME Corp as a MEMBER.
     Do you want to join?"
    [Accept]  [Decline]

  Mika clicks [Accept].

  Step 6: Membership created
  ──────────────────────────
  volta creates a membership record:
    user_id:    mika-uuid
    tenant_id:  acme-uuid
    role:       MEMBER
    invited_by: kenji-uuid

  The invitation is marked as "used" (cannot be used again).

  Step 7: Mika is in!
  ────────────────────
  Mika is redirected to the ACME Corp workspace.
  She can now use all apps that allow the MEMBER role.
```

---

## What about security?

Invitation flows have several security protections built in:

- **Expiration** -- Invitation links expire after a set time. An old link found in someone's email history cannot be used.
- **Single use** -- Once accepted, the invitation code cannot be reused. If someone intercepts the link, they cannot use it after Mika already accepted.
- **Email verification** -- The invitation is tied to a specific email. If someone else tries to use the link, volta checks that the logged-in user's email matches the invitation.
- **Role limits** -- An ADMIN can only invite people with roles up to ADMIN level. Only an OWNER can make someone else an OWNER.
- **Member limits** -- volta checks the tenant's `max_members` limit before allowing a new member.

---

## A simple example

```
  ACME Corp workspace:
    Yuki (OWNER) - Created the workspace
    Kenji (ADMIN) - Team manager

  Kenji wants to add a new intern, Riku:

  1. Kenji → POST /api/v1/tenants/acme-uuid/invitations
     Body: { "email": "riku@example.com", "role": "VIEWER" }

  2. volta → Sends email to riku@example.com with link

  3. Riku clicks link → Logs in with Google → Sees "Join ACME Corp?"

  4. Riku clicks Accept → volta creates membership:
     Riku - VIEWER in ACME Corp, invited by Kenji

  5. Riku can now view content in ACME Corp's apps
     (but cannot edit, because VIEWER is read-only)
```

---

## Further reading

- [role.md](role.md) -- Understanding the four roles that can be assigned during invitation.
- [tenant.md](tenant.md) -- The workspace that members are being invited to.
- [authentication-vs-authorization.md](authentication-vs-authorization.md) -- Login (Step 4) is authentication; role checking is authorization.
