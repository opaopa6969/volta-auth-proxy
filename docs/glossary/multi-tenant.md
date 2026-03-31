# Multi-Tenancy

[日本語版はこちら](multi-tenant.ja.md)

---

## What is it in one sentence?

Multi-tenancy is a design where one application serves many separate groups of users (called "tenants") at the same time, keeping each group's data completely private from the others.

---

## The apartment building analogy

Imagine a large apartment building. The building itself is one structure -- one set of walls, one roof, one elevator, shared plumbing and electricity. But inside, each apartment is a completely private space. You have your own key. Your neighbor cannot walk into your apartment and look through your fridge. The building manager decides who gets a key to which apartment.

In software, this is exactly what multi-tenancy is:

- **The building** = the application (volta-auth-proxy)
- **Each apartment** = a tenant (a company, team, or organization using the software)
- **The building manager** = the authentication and authorization system
- **Your apartment key** = your login credentials and permissions

Everyone shares the same application code, the same server, and even the same database. But each tenant's data is walled off from the others, as if they each had their own private room.

---

## Why does SaaS need multi-tenancy?

If you have ever used Slack, Notion, or Google Workspace, you have used a multi-tenant application. Your company's Slack workspace is one tenant. Another company's workspace is a different tenant. You are all using the same Slack software, but you cannot see each other's messages.

Without multi-tenancy, you would need to run a completely separate copy of the software for every single customer. Imagine if Slack had to set up a separate server for each of the millions of companies using it. That would be:

- **Extremely expensive** -- each customer needs their own server, database, and maintenance
- **Impossible to update** -- you would have to update millions of separate installations
- **A nightmare to manage** -- bugs in one copy might not exist in another

Multi-tenancy lets you run one application that serves everyone, while keeping everyone's data safe and separate.

---

## How is data kept separate?

There are three common approaches. Think of them as levels of separation:

**Level 1: Shared table with a label (what volta uses)**
Imagine a filing cabinet where everyone's documents are stored together, but every document has a colored sticker showing who it belongs to. When you open the cabinet, you can only see documents with your sticker color.

In database terms, every row in every table has a `tenant_id` column. When the app looks up data, it always filters by that column. Simple and efficient.

**Level 2: Separate drawers**
Each tenant gets their own drawer in the filing cabinet. Stronger separation, but harder to manage.

**Level 3: Separate cabinets**
Each tenant gets their own entire filing cabinet. The strongest separation, but very expensive.

---

## How volta-auth-proxy handles multi-tenancy

volta uses Level 1 (shared tables with tenant_id). Here is what that looks like in practice:

```
  Imagine a database table for "projects":

  ┌────────────┬───────────┬─────────────────┐
  │ tenant_id  │ id        │ project_name    │
  ├────────────┼───────────┼─────────────────┤
  │ acme-uuid  │ proj-1    │ Website Redesign│
  │ acme-uuid  │ proj-2    │ Mobile App      │
  │ globex-uuid│ proj-3    │ Rocket Engine   │
  │ globex-uuid│ proj-4    │ Moon Base       │
  └────────────┴───────────┴─────────────────┘

  When a user from ACME logs in, volta makes sure
  all queries include: WHERE tenant_id = 'acme-uuid'
  So the ACME user only ever sees "Website Redesign"
  and "Mobile App". They have no idea that Globex exists.
```

volta enforces this separation at the gateway level. When a request comes in, volta identifies which tenant the user belongs to, puts that information into a special header (`X-Volta-Tenant-Id`), and sends it to the downstream app. The app then uses that tenant ID to filter all its database queries.

---

## A simple example

Say you are building a wiki app that sits behind volta-auth-proxy. When a user from "ACME Corp" visits the wiki:

1. The user's browser sends a request to your wiki
2. The request first hits volta-auth-proxy (the "building manager")
3. volta checks: "Is this person logged in? Yes. Which tenant? ACME Corp."
4. volta adds `X-Volta-Tenant-Id: acme-uuid` to the request
5. Your wiki app receives the request and uses `acme-uuid` to query only ACME's wiki pages
6. The user sees only their company's wiki -- never anyone else's

---

## Further reading

- [tenant.md](tenant.md) -- Deep dive into volta's tenant data model and resolution logic.
- [saas.md](saas.md) -- What is SaaS and why it needs multi-tenancy.
- [row-level-security.md](row-level-security.md) -- Database-level enforcement of tenant isolation.
