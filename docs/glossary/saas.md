# SaaS (Software as a Service)

[日本語版はこちら](saas.ja.md)

---

## What is it in one sentence?

SaaS is software that you use through the internet (usually a web browser) without installing anything on your computer -- the company that made it runs it for you, and you just log in.

---

## The real-world analogy: renting vs. buying

Think about watching movies. You have two options:

- **Buy the DVD** -- You own it. You play it on your own DVD player. If the disc gets scratched, that is your problem. If you want a newer version, you buy it again.
- **Subscribe to Netflix** -- You do not own anything. You just log in and watch. Netflix handles the servers, the updates, and the storage. If they add a new feature, you get it automatically.

SaaS is the Netflix model for software. Instead of buying a program and installing it on your computer, you open your browser and use it. The software company handles everything behind the scenes.

---

## Examples you already use

You probably use SaaS every day without realizing it:

| SaaS product | What it replaced |
|---|---|
| **Gmail** | Outlook installed on your PC, downloading emails to your hard drive |
| **Google Docs** | Microsoft Word installed on your PC, saving files to your desktop |
| **Slack** | Office chat software installed on a company server |
| **Notion** | A wiki application running on your company's own hardware |
| **GitHub** | A Git server you set up and maintained yourself |

The pattern is the same: instead of installing and maintaining the software yourself, someone else runs it, and you access it through the internet.

---

## How SaaS differs from installed software

| | Installed software | SaaS |
|---|---|---|
| **Where it runs** | Your computer or your server | The company's servers (the "cloud") |
| **Updates** | You download and install updates manually | Updates happen automatically, you just refresh |
| **Data storage** | On your hard drive | On the company's servers |
| **Access** | Only from the computer where it is installed | From any device with a browser |
| **Payment** | One-time purchase (usually) | Monthly or yearly subscription |
| **Setup** | You install and configure it | You create an account and start using it |

---

## Why auth matters for SaaS

Here is the key thing: with SaaS, many different companies and people are using the same application. This makes authentication (verifying who you are) and authorization (determining what you can do) absolutely critical.

Imagine if Gmail did not have proper authentication. You could open your browser and accidentally see someone else's emails. That would be a disaster.

SaaS applications need to:

1. **Know who you are** -- You must log in so the app knows which data to show you
2. **Know which organization you belong to** -- If your company uses Slack, you should see your company's channels, not someone else's
3. **Know what you are allowed to do** -- An intern should not have the same permissions as the CEO
4. **Keep data completely separate between customers** -- Company A must never see Company B's data

This is exactly what volta-auth-proxy does. It sits in front of your SaaS application and handles all of these concerns, so your app can focus on its actual purpose.

---

## How volta-auth-proxy fits into SaaS

When you are building a SaaS product, volta-auth-proxy acts as the front door:

```
  A user visits your SaaS app:

  User's Browser
       │
       ▼
  volta-auth-proxy ← "Who are you? Which company? What role?"
       │
       │  (verified! adds identity info to the request)
       ▼
  Your App ← "Ah, this is Taro from ACME Corp, he's an ADMIN"
       │
       ▼
  Shows only ACME Corp's data to Taro
```

Without volta, your app would have to build all of this login, tenant, and role logic from scratch. volta lets you skip that and focus on building the features your customers actually pay for.

---

## A simple example

Say you are building a project management SaaS (like a simple Notion). Without volta, your app needs to:

- Build a login page
- Integrate with Google/GitHub for social login
- Create a session management system
- Build a tenant (workspace) system
- Handle invitations so team members can join
- Manage roles (who is admin, who is viewer)
- Make sure Company A cannot see Company B's projects

With volta, your app just needs to:

- Read the `X-Volta-Tenant-Id` header to know which workspace
- Read the `X-Volta-User-Id` header to know which user
- Read the `X-Volta-Roles` header to know what they can do
- Focus on building project management features

volta handles everything else.

---

## Further reading

- [multi-tenant.md](multi-tenant.md) -- How one app serves many companies safely.
- [authentication-vs-authorization.md](authentication-vs-authorization.md) -- The difference between "who are you" and "what can you do."
- [identity-gateway.md](identity-gateway.md) -- What volta-auth-proxy actually is.
