# Self-Hosting

[日本語版はこちら](self-hosting.ja.md)

---

## What is it?

Self-hosting means running software on servers that you own or control, instead of paying someone else to run it for you in their cloud.

Think of it like cooking at home versus ordering delivery. When you cook at home, you buy the ingredients, you control the recipe, you know exactly what goes in your food, and it is cheaper per meal. But you also have to do the dishes, keep your kitchen clean, and learn how to cook. When you order delivery, someone else handles everything -- but you pay more, you cannot customize much, and you are stuck with whatever restaurants are available.

Self-hosting software is the "cook at home" model for your tech stack.

---

## Why does it matter?

In the SaaS world, most tools are offered as cloud services: you pay a monthly fee, and the company runs the software for you. This works well for many things. But for some things -- especially authentication -- self-hosting has significant advantages.

### Pros of self-hosting

| Advantage | Why it matters |
|-----------|---------------|
| **Cost control** | No per-user pricing. Your cost is just the server (a $10-50/month VM can handle a lot). |
| **Data ownership** | User data stays on your servers. No third party has copies of your user database. |
| **Privacy/compliance** | Some regulations (GDPR, healthcare, government) require data to stay in specific locations. Self-hosting lets you choose exactly where data lives. |
| **No vendor lock-in** | You can modify the software, fork it, or replace it without anyone's permission. See [vendor-lock-in.md](vendor-lock-in.md). |
| **Customization** | Full control over login UI, email templates, session behavior, and every setting. |
| **No surprise bills** | Your infrastructure cost is predictable, not tied to [MAU](mau.md). |

### Cons of self-hosting

| Disadvantage | The reality |
|-------------|-------------|
| **Ops responsibility** | You are responsible for uptime, monitoring, and incident response. If the server goes down at 3 AM, it is your problem. |
| **Security patches** | You must keep the software updated. Nobody pushes automatic security fixes for you. |
| **Backups** | You set up database backups. If you forget and the disk dies, data is gone. |
| **Setup effort** | Initial configuration takes more time than clicking "Create Account" on a cloud service. |
| **No support team** | There is no vendor support hotline. You rely on documentation, community, and your own skills. |

### The honest trade-off

```
  Cloud-hosted auth (Auth0/Clerk):
  ┌──────────────────────────────────┐
  │  Easy to start                   │
  │  Someone else handles ops        │
  │  Gets expensive at scale         │
  │  You lose control over time      │
  └──────────────────────────────────┘

  Self-hosted auth (volta):
  ┌──────────────────────────────────┐
  │  More setup work upfront         │
  │  You handle ops                  │
  │  Stays cheap at any scale        │
  │  You keep full control forever   │
  └──────────────────────────────────┘
```

---

## How does volta-auth-proxy approach self-hosting?

volta is self-host only. There is no cloud-hosted volta service. This is a deliberate design decision, not a limitation.

To make self-hosting practical, volta minimizes the operational burden:

| Concern | volta's approach |
|---------|-----------------|
| **Dependencies** | Just PostgreSQL. No Redis required (optional), no Kafka required (optional), no Elasticsearch required (optional). |
| **Memory** | ~30MB RAM. Runs on tiny instances. |
| **Startup** | ~200ms. Fast restarts, fast deployments. |
| **Configuration** | One `.env` file + one `volta-config.yaml`. No 500-line config files. See [config-hell.md](config-hell.md). |
| **Database migrations** | Automatic via [Flyway](flyway.md). Start the app, schema updates happen. |
| **Deployment** | Single Java process. No complex orchestration needed. |

### Minimal self-hosting setup

```yaml
# docker-compose.yml -- this is all you need
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: volta_auth
      POSTGRES_USER: volta
      POSTGRES_PASSWORD: volta

  volta:
    image: volta-auth-proxy:latest
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/volta_auth
      GOOGLE_CLIENT_ID: your-google-client-id
      GOOGLE_CLIENT_SECRET: your-google-client-secret
      SESSION_SECRET: your-random-secret
    ports:
      - "7070:7070"
```

That is a production-capable auth system in about 15 lines of configuration.

---

## Self-hosting versus other deployment models

| Model | Example | You control | You pay |
|-------|---------|-------------|---------|
| **Cloud SaaS** | Auth0, Clerk | Nothing (it is their servers, their data, their rules) | Per-MAU monthly fee |
| **Self-hosted on cloud VMs** | volta on AWS EC2 | The software, the data, the config. AWS manages the hardware. | VM cost (~$10-50/month) |
| **Self-hosted on-premise** | volta on your physical servers | Everything, including the physical hardware | Hardware + electricity |

Most volta users will self-host on cloud VMs (AWS, GCP, or a simple VPS). You get the cost and control benefits of self-hosting without managing physical hardware.

---

## Further reading

- [vendor-lock-in.md](vendor-lock-in.md) -- Why self-hosting eliminates vendor lock-in.
- [mau.md](mau.md) -- Why cloud auth pricing is scary at scale.
- [config-hell.md](config-hell.md) -- How volta keeps self-hosting config simple.
- [flyway.md](flyway.md) -- How database migrations work automatically.
