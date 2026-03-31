# Identity Gateway

[日本語版はこちら](identity-gateway.ja.md)

---

## What is it in one sentence?

An identity gateway is a service that sits in front of your application and handles all the "who are you and what are you allowed to do" logic, so your application does not have to.

---

## The security guard analogy

Imagine a large office building with many companies inside. Instead of each company hiring their own security guard, the building has one security desk at the entrance. When you walk in:

1. The guard checks your ID (authentication -- "Who are you?")
2. The guard checks which floor you are allowed to visit (authorization -- "What can you access?")
3. The guard gives you a visitor badge with your name and floor number
4. You walk to your destination, and everyone inside can see your badge

This is exactly what an identity gateway does. It is the security desk for your software.

- **The office building** = your entire system of applications
- **The security guard** = the identity gateway (volta-auth-proxy)
- **Your ID** = your login credentials (email, password, Google account)
- **The visitor badge** = headers and tokens that tell apps who you are
- **The companies inside** = your downstream applications (wiki, admin panel, etc.)

---

## Why don't apps do their own auth?

You might wonder: "Why not just add login logic to each app?" Great question. Here is why:

**Imagine five apps, each doing their own auth:**

```
  App 1 (Wiki)     → builds its own login page, session management, Google integration
  App 2 (Admin)    → builds its own login page, session management, Google integration
  App 3 (Dashboard)→ builds its own login page, session management, Google integration
  App 4 (API)      → builds its own login page, session management, Google integration
  App 5 (Chat)     → builds its own login page, session management, Google integration

  Problems:
  - 5x the code to maintain
  - 5x the security bugs to find
  - User logs in 5 separate times
  - "Who has access to what?" is scattered across 5 databases
  - Changing the login flow means updating 5 apps
```

**Now imagine one identity gateway:**

```
  volta-auth-proxy → handles ALL login, sessions, roles, tenants
       │
       ├── App 1 (Wiki)      → just reads headers, focuses on wiki features
       ├── App 2 (Admin)     → just reads headers, focuses on admin features
       ├── App 3 (Dashboard) → just reads headers, focuses on dashboard features
       ├── App 4 (API)       → just reads headers, focuses on API features
       └── App 5 (Chat)      → just reads headers, focuses on chat features

  Benefits:
  - Auth logic in ONE place
  - ONE security surface to audit
  - User logs in ONCE (single sign-on)
  - Central view of "who has access to what"
  - Change login flow in one place, all apps benefit
```

---

## volta-auth-proxy's role as an identity gateway

volta-auth-proxy is the identity gateway for your SaaS application. Here is what it does:

1. **Login** -- Handles the entire login flow (Google login, SAML, email/password)
2. **Sessions** -- Manages user sessions so users stay logged in
3. **Tenant resolution** -- Figures out which company/workspace the user belongs to
4. **Role checking** -- Determines if the user is an OWNER, ADMIN, MEMBER, or VIEWER
5. **Token creation** -- Creates JWTs (secure tokens) that carry the user's identity info
6. **Header injection** -- Adds identity headers to requests before passing them to your apps

Your apps never see a password. They never deal with login pages. They just receive requests that already have identity information attached, like reading a visitor badge.

---

## A simple example

Here is what happens when a user visits your wiki app that is protected by volta:

```
  1. User types wiki.example.com in their browser

  2. The request arrives at volta-auth-proxy first
     volta: "Do you have a valid session cookie?"
     User: "No, this is my first visit."
     volta: "OK, redirecting you to the login page..."

  3. User logs in with Google
     volta: "Google says you're taro@acme.com. Let me check..."
     volta: "Found you! You're a MEMBER of ACME Corp."

  4. volta creates a session and redirects back to wiki.example.com
     This time, the request includes a session cookie.

  5. volta sees the session cookie and adds headers:
     X-Volta-User-Id: taro-uuid
     X-Volta-Tenant-Id: acme-uuid
     X-Volta-Roles: MEMBER
     X-Volta-Email: taro@acme.com

  6. The wiki app receives the request with these headers
     Wiki: "Ah, this is Taro from ACME, he's a MEMBER.
            Let me show him ACME's wiki pages."
```

The wiki app never had to deal with Google login, sessions, or tenant logic. It just reads the headers that volta provides.

---

## Further reading

- [reverse-proxy.md](reverse-proxy.md) -- How the request routing works behind the scenes.
- [authentication-vs-authorization.md](authentication-vs-authorization.md) -- The two things an identity gateway checks.
- [downstream-app.md](downstream-app.md) -- What "downstream app" means in this context.
- [forwardauth.md](forwardauth.md) -- The technical mechanism volta uses to inject headers.
