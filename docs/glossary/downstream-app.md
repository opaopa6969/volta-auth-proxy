# Downstream App

[日本語版はこちら](downstream-app.ja.md)

---

## What is it in one sentence?

A downstream app is any application that sits "behind" volta-auth-proxy and relies on volta to handle login, identity, and permissions before requests reach it.

---

## The water pipe analogy

Imagine a water treatment plant connected to houses in a neighborhood:

```
  Water source (rain, river)
       │
       ▼
  ┌──────────────────────┐
  │  Water treatment      │  ← Cleans and tests the water
  │  plant                │     (volta-auth-proxy)
  └──────────────────────┘
       │
       ├──── House A (wiki app)        ← gets clean water
       ├──── House B (admin panel)     ← gets clean water
       └──── House C (dashboard app)   ← gets clean water

  The houses are "downstream" -- they are after the treatment plant.
  They receive water that has already been cleaned and tested.
  They do NOT need their own water treatment equipment.
```

In this analogy:
- **Water source** = the raw HTTP request from a user's browser
- **Treatment plant** = volta-auth-proxy (checks identity, validates sessions, determines roles)
- **Clean water** = the request enriched with identity headers (X-Volta-User-Id, X-Volta-Tenant-Id, etc.)
- **Houses** = your downstream apps (they receive pre-processed, trusted requests)

---

## What "downstream" means in software

In software architecture, "upstream" and "downstream" describe the direction that data flows:

- **Upstream** = closer to the source (the user, the request origin)
- **Downstream** = further from the source (receiving processed data)

volta-auth-proxy sits upstream. Your apps sit downstream. Requests flow from the user, through volta, and then down to your apps. That is why they are called "downstream" apps.

```
  User (upstream)
    → volta-auth-proxy (middle)
      → Your app (downstream)
```

You might also hear the opposite: "the downstream app calls the upstream API." This means data is flowing in the other direction -- your app is asking volta for information.

---

## Why the concept matters

Understanding the upstream/downstream relationship explains a critical design decision in volta:

**Downstream apps trust volta completely.** When your wiki app receives a request with the header `X-Volta-User-Id: taro-uuid`, it does not need to verify that independently. volta already did the verification. The app just reads the header and uses it.

This is like how the houses in our analogy trust the treatment plant. They do not re-test the water. If the treatment plant says the water is clean, the houses use it.

This design has big benefits:
- **Apps are simpler** -- no login pages, no session management, no auth logic
- **Security is centralized** -- one place to audit, one place to fix vulnerabilities
- **Consistency** -- all apps enforce the same auth rules

But it also means: **if volta goes down, all downstream apps lose authentication.** The treatment plant is a single point of failure. That is why volta needs to be reliable and well-monitored.

---

## volta's downstream apps in practice

In volta-config.yaml, you define your downstream apps:

```yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]

  - id: app-admin
    url: https://admin.example.com
    allowed_roles: [ADMIN, OWNER]
```

Each downstream app gets these headers from volta on every request:

```
  X-Volta-User-Id:    taro-uuid
  X-Volta-Tenant-Id:  acme-uuid
  X-Volta-Roles:      MEMBER
  X-Volta-Email:      taro@acme.com
```

The app reads these headers and knows exactly who is making the request and what they are allowed to do, without doing any authentication work itself.

---

## A simple example

```
  You are building two apps: a wiki and an admin panel.
  Both are downstream of volta.

  Request from Taro (MEMBER of ACME Corp):

  Taro's browser
       │
       ▼
  volta-auth-proxy
       │ "Taro is a MEMBER of ACME Corp"
       │
       ├──→ wiki.example.com
       │    volta adds: X-Volta-User-Id: taro-uuid
       │                X-Volta-Tenant-Id: acme-uuid
       │                X-Volta-Roles: MEMBER
       │    Wiki app: "Welcome, Taro! Here are ACME's wiki pages."
       │    Result: 200 OK
       │
       └──→ admin.example.com
            volta checks: MEMBER is NOT in [ADMIN, OWNER]
            Result: 403 Forbidden (request never reaches the admin app)
```

Notice that for the admin panel, volta blocks the request before it even reaches the app. The downstream app does not have to reject Taro -- volta does it for free.

---

## Further reading

- [reverse-proxy.md](reverse-proxy.md) -- The infrastructure that routes requests from volta to downstream apps.
- [identity-gateway.md](identity-gateway.md) -- volta's role as the upstream identity gateway.
- [forwardauth.md](forwardauth.md) -- The technical mechanism for passing identity to downstream apps.
