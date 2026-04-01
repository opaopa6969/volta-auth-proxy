# Production

[日本語版はこちら](production.ja.md)

---

## What is it?

Production is the live environment where real users interact with your software. It is the final destination after development, testing, and staging. When someone says "it's in production," they mean real people are using it right now, with real data, and real consequences if something breaks.

Think of it like a stage play. Development is rehearsal -- you try things, make mistakes, and nobody in the audience sees. Staging is the dress rehearsal -- everything looks real but it is still private. Production is opening night -- the curtain goes up, the audience is watching, and every mistake is visible. You cannot pause production to fix a typo.

Most projects have at least three environments: **development** (your laptop), **staging** (a server that mimics production but with fake data), and **production** (the real thing). The code is identical across environments; the difference is the data, the users, and the consequences.

---

## Why does it matter?

- **Production is where your business runs.** Revenue, user trust, and reputation depend on production stability.
- **Bugs in production affect real people.** A login bug in dev is an inconvenience; a login bug in production locks out paying customers.
- **Production data is sacred.** Accidentally deleting a test database is a shrug; accidentally deleting the production database is a disaster.
- **Security requirements escalate.** Development can use weak passwords and self-signed certs. Production must use strong secrets, TLS, and proper access controls.
- **Performance matters more.** 100ms latency in dev is fine. 100ms latency in production under 1000 concurrent users might mean your server is dying.

---

## How does it work?

### Environment comparison

```
  ┌──────────────────────────────────────────────────────────┐
  │                    Environments                          │
  ├──────────────┬──────────────┬──────────────┬─────────────┤
  │              │ Development  │  Staging     │ Production   │
  ├──────────────┼──────────────┼──────────────┼─────────────┤
  │ Users        │ Just you     │ QA team      │ Everyone     │
  │ Data         │ Fake/seed    │ Fake/copy    │ Real         │
  │ URL          │ localhost    │ staging.x    │ app.x.com    │
  │ DB           │ Local PG     │ Shared PG    │ Managed PG   │
  │ Secrets      │ Test keys    │ Test keys    │ Real keys    │
  │ Errors       │ Stack traces │ Stack traces │ User-friendly│
  │ Logging      │ DEBUG        │ INFO         │ WARN/ERROR   │
  │ If it breaks │ ¯\_(ツ)_/¯   │ Fix later    │ FIX NOW      │
  └──────────────┴──────────────┴──────────────┴─────────────┘
```

### What makes production different

**1. Real data, real consequences**

Production databases contain actual user information -- emails, sessions, tenant configurations, role assignments. Data loss or corruption is not recoverable from a seed script.

**2. Uptime expectations**

| Uptime % | Downtime per year | Typical target |
|----------|-------------------|----------------|
| 99%      | 3.65 days         | Hobby project  |
| 99.9%    | 8.76 hours        | SaaS product   |
| 99.99%   | 52.6 minutes      | Enterprise     |
| 99.999%  | 5.26 minutes      | Banking/health |

**3. Observability**

In development, you read logs in your terminal. In production, you need:
- Structured logging (JSON format for log aggregation)
- Health check endpoints
- Metrics (request rates, error rates, latency)
- Alerting (PagerDuty, Slack notifications)

**4. Security hardening**

```
  Development:                    Production:
  ┌───────────────────┐          ┌────────────────────────┐
  │ HTTP (no TLS)     │          │ HTTPS (TLS required)   │
  │ CORS: allow all   │          │ CORS: specific origins │
  │ Debug endpoints   │          │ Debug disabled         │
  │ Weak secrets      │          │ Strong rotated secrets │
  │ No rate limiting  │          │ Rate limiting enabled  │
  └───────────────────┘          └────────────────────────┘
```

---

## How does volta-auth-proxy use it?

### Production configuration

volta-auth-proxy uses [environment variables](environment-variable.md) to differentiate production from other environments. Critical production settings include:

```bash
# Production environment variables (examples)
VOLTA_BASE_URL=https://auth.yourdomain.com
DATABASE_URL=jdbc:postgresql://prod-db:5432/volta
GOOGLE_CLIENT_ID=<real-google-oauth-client-id>
GOOGLE_CLIENT_SECRET=<real-secret>
JWT_PRIVATE_KEY_PATH=/etc/volta/keys/private.pem
VOLTA_COOKIE_SECURE=true
VOLTA_COOKIE_SAMESITE=Lax
```

### Production checklist for volta

Before going live, verify:

1. **Database** -- PostgreSQL is running, [migrations](migration.md) are applied, backups are scheduled
2. **Secrets** -- Google OIDC credentials are for the production Google Cloud project
3. **JWT keys** -- [RS256](rs256.md) key pair generated and secured, not the dev keys
4. **Traefik** -- [ForwardAuth](forwardauth.md) correctly routes to volta, TLS certificates active
5. **Cookies** -- `Secure` flag is `true`, `SameSite` is `Lax` or `Strict`
6. **Rate limiting** -- Enabled to protect against [brute force](brute-force.md) attacks
7. **Logging** -- Set to WARN or ERROR level; no debug output leaking sensitive data
8. **Connection pool** -- [HikariCP](hikaricp.md) sized appropriately for expected load

### Production architecture

```
  DNS: auth.yourdomain.com
          │
          ▼
  ┌──────────────┐
  │   Traefik     │  ← TLS termination
  │   (reverse    │  ← ForwardAuth to volta
  │    proxy)     │  ← Routes to downstream apps
  └──────┬───────┘
         │
         ▼
  ┌──────────────────┐
  │ volta-auth-proxy  │  ← Validates sessions
  │                    │  ← Issues JWTs
  │                    │  ← Sets X-Volta-* headers
  └────────┬─────────┘
           │
           ▼
  ┌──────────────────┐
  │    PostgreSQL     │  ← Users, tenants, sessions,
  │                    │     roles, invitations
  └──────────────────┘
```

---

## Common mistakes and attacks

### Mistake 1: Using development secrets in production

If your production Google OIDC client ID is the same one you use on localhost, something is very wrong. Production secrets must be separate, rotated regularly, and never committed to git.

### Mistake 2: Leaving debug endpoints exposed

Endpoints like `/debug`, `/health` with verbose output, or stack traces in error responses leak internal architecture to attackers.

### Mistake 3: No backup strategy

Production databases need automated backups. If your only copy of user data is the live database, one bad `DELETE FROM users` away from catastrophe.

### Mistake 4: Testing in production

"Let me just try this real quick on prod" is how outages happen. Use staging. That is what it is for.

### Mistake 5: Identical credentials across environments

If dev and production share the same database password, a compromised dev machine gives an attacker access to production data.

---

## Further reading

- [deployment.md](deployment.md) -- How code reaches production.
- [high-availability.md](high-availability.md) -- Keeping production running even when things fail.
- [environment-variable.md](environment-variable.md) -- Configuring production differently from dev.
- [what-is-production-ready.md](what-is-production-ready.md) -- Criteria for "production ready."
- [ci-cd.md](ci-cd.md) -- Automating the path to production.
