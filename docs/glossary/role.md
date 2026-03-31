# Role

[日本語版はこちら](role.ja.md)

---

## What is it in one sentence?

A role is a label assigned to a user that determines what they are allowed to do within a workspace -- like a job title that comes with a specific set of permissions.

---

## The office analogy

Think about a typical office. People have different job titles, and each title comes with different levels of access:

- **The CEO (Owner)** can do anything: hire and fire, access all rooms, sign contracts, even shut down the company.
- **The Office Manager (Admin)** can invite new employees, assign desks, manage the office -- but cannot shut down the company.
- **A Regular Employee (Member)** can do their job, use meeting rooms, access shared files -- but cannot hire people or change office policies.
- **A Visitor (Viewer)** can sit in the lobby, look at public materials -- but cannot touch anything or go into private areas.

You do not give each person a list of 50 individual permissions. Instead, you give them a title (role), and the permissions come with it. If you need to change what "Regular Employee" can do, you change the role definition once, and it applies to everyone with that role.

---

## volta's four roles

volta uses a simple hierarchy of four roles. "Hierarchy" means that higher roles automatically include all the permissions of lower roles:

```
  OWNER   (highest -- can do everything)
    │
  ADMIN   (can manage people and settings)
    │
  MEMBER  (normal usage)
    │
  VIEWER  (read-only, lowest)
```

Here is what each role can do:

### OWNER -- "It's my company"
- Everything an ADMIN can do, plus:
- Delete the entire tenant (workspace)
- Transfer ownership to someone else
- There must always be at least one OWNER per tenant (you cannot leave a workspace ownerless)

### ADMIN -- "I manage the team"
- Everything a MEMBER can do, plus:
- Invite new people to the workspace
- Remove people from the workspace
- Change people's roles (up to ADMIN level, cannot make someone OWNER)
- Change workspace settings

### MEMBER -- "I work here"
- Use all apps that allow the MEMBER role
- Create and edit content
- Cannot manage other users or change settings

### VIEWER -- "I'm just looking"
- View content in apps that allow the VIEWER role
- Cannot create, edit, or delete anything
- Think of this as "read-only" access

---

## Why roles matter

Without roles, you would need to manage permissions individually for every user. Imagine a workspace with 200 people:

**Without roles (nightmare):**
```
  Taro:   can_view, can_edit, can_invite, can_remove_users, can_change_settings
  Hanako: can_view, can_edit
  Jiro:   can_view
  ... repeat 197 more times ...

  New policy: "Editors can now export PDFs"
  → Update 150 users individually. Miss 3. Get a bug report.
```

**With roles (simple):**
```
  Taro:   ADMIN
  Hanako: MEMBER
  Jiro:   VIEWER
  ... repeat 197 more times, but it's just one word each ...

  New policy: "Members can now export PDFs"
  → Update the MEMBER role definition once. Done.
```

---

## How volta uses roles

### In volta-config.yaml

Each app behind volta specifies which roles are allowed:

```yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]   # Viewers cannot access

  - id: app-admin
    url: https://admin.example.com
    allowed_roles: [ADMIN, OWNER]           # Only admins and owners
```

When Taro (a MEMBER) tries to access the wiki, volta checks: "Is MEMBER in the allowed list? Yes." Taro gets in. When Taro tries to access the admin panel, volta checks: "Is MEMBER in the allowed list? No." Taro gets a 403 Forbidden error.

### In the JWT token

When volta authenticates a user, it creates a token (JWT) that includes the user's role:

```json
{
  "sub": "taro-uuid",
  "volta_tid": "acme-uuid",
  "volta_roles": ["MEMBER"],
  "volta_tname": "ACME Corp"
}
```

Your app can read this token to make its own permission decisions. For example, your wiki app might let MEMBERs edit pages but not delete them.

---

## A simple example

```
  ACME Corp workspace has three members:

  ┌──────────────────────────────────────────┐
  │ Yuki   - OWNER  - Created the workspace │
  │ Kenji  - ADMIN  - Manages the team      │
  │ Mika   - MEMBER - Regular user          │
  │ Client - VIEWER - External consultant   │
  └──────────────────────────────────────────┘

  Scenario: Client tries to edit a wiki page
  → volta: "Client is a VIEWER. Viewers have read-only access."
  → Result: The edit is blocked.

  Scenario: Mika tries to remove Kenji from the workspace
  → volta: "Mika is a MEMBER. Only ADMIN or OWNER can remove members."
  → Result: The action is blocked.

  Scenario: Kenji tries to make himself an OWNER
  → volta: "Kenji is an ADMIN. Only OWNERs can promote to OWNER."
  → Result: The action is blocked.
```

---

## Further reading

- [rbac.md](rbac.md) -- The full technical details of role-based access control.
- [authentication-vs-authorization.md](authentication-vs-authorization.md) -- Roles are part of authorization (the "what can you do" part).
- [tenant.md](tenant.md) -- Roles are per-tenant, meaning you can have different roles in different workspaces.
- [invitation-flow.md](invitation-flow.md) -- How new members get assigned a role when they join.
