# Authentication vs. Authorization

[日本語版はこちら](authentication-vs-authorization.ja.md)

---

## What is it in one sentence?

Authentication is proving **who you are** (like showing your ID card), and authorization is determining **what you are allowed to do** (like which doors your key card opens).

---

## The most confusing pair in tech

These two words sound almost the same and are often shortened to "authn" (authentication) and "authz" (authorization), which makes it even worse. But they are two completely different questions:

| | Authentication (AuthN) | Authorization (AuthZ) |
|---|---|---|
| **Question** | "Who are you?" | "What can you do?" |
| **Real world** | Showing your passport at the airport | Your boarding pass saying you can board Flight 101 |
| **Result** | "You are Taro Yamada" | "Taro can edit projects but cannot delete users" |
| **When it fails** | 401 Unauthorized ("I don't know who you are") | 403 Forbidden ("I know who you are, but you can't do that") |

---

## The ID card vs. key card analogy

Imagine you work at a big company with a secure office building.

**Authentication = Your photo ID badge**
When you walk in the door, the security guard looks at your photo ID. Are you really Taro Yamada? Does your face match the photo? The guard is not checking what you are allowed to do -- just that you are who you claim to be. If you do not have an ID, you cannot enter at all.

**Authorization = Your key card access levels**
Once inside, you tap your key card on doors to open them. Your key card says:
- Floor 3 (your team's floor): Allowed
- Floor 5 (executive floor): Denied
- Server room: Denied
- Break room: Allowed

The building knows who you are (authentication happened at the door), and now it checks what you are allowed to access (authorization happens at every door).

**The key insight:** Authentication always comes first. You cannot check what someone is allowed to do until you know who they are.

---

## Examples in everyday life

| Situation | Authentication | Authorization |
|---|---|---|
| Netflix | Entering your email and password | Your plan determines if you can watch 4K |
| Hotel | Checking in at the front desk with your ID | Your room key only opens your room, not others |
| Concert | Showing your ticket at the gate | VIP ticket lets you go backstage, general admission does not |
| ATM | Inserting your card and entering your PIN | Your account determines your withdrawal limit |

---

## How volta handles authentication

volta supports several ways to prove who you are:

1. **Google login (OIDC)** -- You click "Sign in with Google." Google confirms your identity. volta trusts Google's answer.
2. **SAML (for enterprise)** -- Your company's identity provider (like Okta or Azure AD) confirms who you are.
3. **Session cookies** -- After you log in once, volta gives your browser a cookie so you do not have to log in every time.

When authentication succeeds, volta knows: "This is taro@acme.com."

When authentication fails, volta returns **401 Unauthorized** -- "I do not know who you are. Please log in."

---

## How volta handles authorization

Once volta knows who you are, it checks what you are allowed to do:

1. **Tenant membership** -- Are you a member of the tenant (workspace) you are trying to access? If not, you cannot access anything in that tenant.
2. **Role checking** -- What is your role in this tenant? volta has four levels: OWNER > ADMIN > MEMBER > VIEWER.
3. **App access** -- Each app defines which roles can access it. For example, the admin panel might require ADMIN or OWNER.

```
  User: taro@acme.com tries to access admin.example.com

  Step 1 - Authentication:
  volta: "Session cookie found. You are taro@acme.com." ✓

  Step 2 - Authorization:
  volta: "Checking your role in ACME Corp..."
  volta: "You are a MEMBER."
  volta: "admin.example.com requires ADMIN or OWNER."
  volta: "403 Forbidden. You are authenticated but not authorized." ✗
```

When authorization fails, volta returns **403 Forbidden** -- "I know who you are, but you do not have permission for this."

---

## A simple example

```
  Taro (MEMBER of ACME Corp) tries to access two apps:

  ┌─────────────────────────────────────────────────┐
  │ wiki.example.com                                 │
  │ allowed_roles: [MEMBER, ADMIN, OWNER]            │
  │                                                  │
  │ Authentication: ✓ (Taro is logged in)            │
  │ Authorization:  ✓ (MEMBER is in the allowed list)│
  │ Result: 200 OK -- Taro can use the wiki          │
  └─────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────┐
  │ admin.example.com                                │
  │ allowed_roles: [ADMIN, OWNER]                    │
  │                                                  │
  │ Authentication: ✓ (Taro is logged in)            │
  │ Authorization:  ✗ (MEMBER is NOT in the list)    │
  │ Result: 403 Forbidden -- Taro cannot access admin│
  └─────────────────────────────────────────────────┘
```

---

## The common mistake

The most common mistake beginners make is mixing up 401 and 403:

- **401 Unauthorized** actually means "not authenticated." The server does not know who you are. Solution: log in.
- **403 Forbidden** means "not authorized." The server knows who you are but you do not have permission. Solution: ask an admin to give you the right role.

Yes, the name "401 Unauthorized" is confusing because it sounds like it means "not authorized." This is widely considered a historical naming mistake in the HTTP specification. Just remember: 401 = "who are you?" and 403 = "not allowed."

---

## Further reading

- [role.md](role.md) -- The four roles in volta and what each can do.
- [http-status-codes.md](http-status-codes.md) -- What 401, 403, and other status codes mean.
- [identity-gateway.md](identity-gateway.md) -- How volta handles both authn and authz at the gateway.
- [rbac.md](rbac.md) -- Role-based access control in detail.
