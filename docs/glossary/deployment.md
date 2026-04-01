# Deployment

[日本語版はこちら](deployment.ja.md)

---

## What is it?

Deployment is the process of taking your finished software and putting it on a server where real users can reach it. Before deployment, your application only lives on your laptop -- after deployment, it lives on a machine connected to the internet.

Think of it like cooking a meal in your kitchen (development) versus opening a restaurant (deployment). In your kitchen, only you eat the food. When you deploy, you set up the restaurant, turn on the "Open" sign, and let the public walk in. Getting from kitchen to restaurant involves a lot of steps: setting up tables, hiring staff, posting a menu, making sure the health inspector approves. Deployment is all of those steps for software.

In a typical web project, deployment means copying your built application (a JAR file, a Docker image, etc.) to a production server, configuring it with the right environment variables, starting it, and making sure it stays running.

---

## Why does it matter?

- **Without deployment, nobody can use your software.** It stays on your laptop forever.
- **Bad deployments cause downtime.** If you deploy a broken version, real users see errors.
- **Security depends on deployment.** Misconfigured servers, exposed ports, or leaked secrets are deployment mistakes.
- **Reproducibility matters.** If you cannot deploy the same thing twice and get the same result, debugging becomes a nightmare.
- **Speed of deployment determines agility.** Teams that can deploy in minutes ship features faster than teams that take hours.

---

## How does it work?

### The deployment pipeline

A typical deployment flows through several stages:

```
  Developer's laptop
       │
       ▼
  ┌──────────────────┐
  │  Source Control   │  ← git push
  │  (GitHub, etc.)   │
  └────────┬─────────┘
           │
           ▼
  ┌──────────────────┐
  │  CI/CD Pipeline   │  ← build, test, package
  │  (GitHub Actions)  │
  └────────┬─────────┘
           │
           ▼
  ┌──────────────────┐
  │  Artifact Store   │  ← JAR file, Docker image
  │                    │
  └────────┬─────────┘
           │
           ▼
  ┌──────────────────┐
  │  Production       │  ← server running the app
  │  Server           │
  └──────────────────┘
```

### Deployment strategies

| Strategy | How it works | Risk |
|----------|-------------|------|
| **Big bang** | Stop old version, start new version | Downtime during switch |
| **Rolling** | Replace instances one at a time | Brief mixed versions |
| **Blue-green** | Run old + new side by side, switch traffic | Needs double resources |
| **Canary** | Send 5% of traffic to new version first | Complex routing needed |

### Key deployment artifacts

For a Java project like volta-auth-proxy, the main artifact is a **fat JAR** -- a single file containing the application and all its dependencies:

```bash
# Build the fat JAR
mvn clean package -DskipTests

# The result
target/volta-auth-proxy-1.0.0.jar   # ~30 MB, everything included

# Run it on the server
java -jar volta-auth-proxy-1.0.0.jar
```

### Environment-specific configuration

The same JAR runs in every environment. What changes is the configuration:

```
  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐
  │ Development  │   │  Staging     │   │ Production   │
  │              │   │              │   │              │
  │ DB: localhost│   │ DB: staging  │   │ DB: prod-db  │
  │ PORT: 8080  │   │ PORT: 8080   │   │ PORT: 8080   │
  │ LOG: debug  │   │ LOG: info    │   │ LOG: warn    │
  │ GOOGLE_ID:  │   │ GOOGLE_ID:   │   │ GOOGLE_ID:   │
  │  test-xxx   │   │  stage-xxx   │   │  prod-xxx    │
  └─────────────┘   └─────────────┘   └─────────────┘
        │                 │                  │
        └────────────┬────┘──────────────────┘
                     │
              Same JAR file
```

Configuration is injected via [environment variables](environment-variable.md), not hardcoded.

---

## How does volta-auth-proxy use it?

### Current deployment model (Phase 1)

volta-auth-proxy is a [single-process](single-process.md) Java application deployed as a fat JAR behind [Traefik](reverse-proxy.md) as a reverse proxy:

```
  Internet
     │
     ▼
  ┌──────────┐
  │ Traefik   │  ← TLS termination, routing
  └────┬─────┘
       │
       ▼
  ┌──────────────────┐
  │ volta-auth-proxy  │  ← single JVM process
  │ (fat JAR on JVM)  │
  └────────┬─────────┘
       │
       ▼
  ┌──────────┐
  │ Postgres  │  ← database
  └──────────┘
```

### What you need to deploy volta

1. **Java 21 LTS** runtime installed on the server (see [lts.md](lts.md))
2. **PostgreSQL** database with [migrations](migration.md) applied
3. **Environment variables** configured (Google OIDC credentials, DB connection, JWT keys)
4. **Traefik** configured with [ForwardAuth](forwardauth.md) pointing to volta
5. **DNS** records pointing your domain to the server

### Phase 2 deployment (future)

Phase 2 adds [Redis](redis.md) and [horizontal scaling](horizontal-scaling.md), changing the deployment topology:

```
  Internet
     │
     ▼
  ┌──────────────┐
  │ Load Balancer │
  └──┬─────┬─────┘
     │     │
     ▼     ▼
  ┌─────┐ ┌─────┐
  │volta│ │volta│  ← multiple instances
  │  1  │ │  2  │
  └──┬──┘ └──┬──┘
     │     │
     ▼     ▼
  ┌──────┐ ┌──────────┐
  │Postgres│ │  Redis    │  ← shared session store
  └──────┘ └──────────┘
```

---

## Common mistakes and attacks

### Mistake 1: Deploying with debug mode on

Running with debug logging or development settings in production leaks internal information (stack traces, SQL queries, session data) to anyone who triggers an error.

### Mistake 2: Hardcoding secrets in the JAR

If you bake database passwords or Google OIDC client secrets into the source code, they end up in your git history and your artifact. Use environment variables instead.

### Mistake 3: Skipping database migrations

Deploying a new version of the code without running the corresponding [Flyway migrations](migration.md) leads to runtime errors -- the code expects columns or tables that do not exist yet.

### Mistake 4: No rollback plan

If the new version has a critical bug, you need to be able to revert to the previous version quickly. Always keep the previous JAR or Docker image available.

### Mistake 5: Deploying on Friday afternoon

Seriously. If something breaks, you want a full team available to fix it, not a weekend skeleton crew.

---

## Further reading

- [production.md](production.md) -- The environment you deploy to.
- [ci-cd.md](ci-cd.md) -- Automating the deployment process.
- [environment-variable.md](environment-variable.md) -- How configuration reaches your deployed app.
- [docker.md](docker.md) -- Containerized deployment alternative.
- [fat-jar.md](fat-jar.md) -- The artifact volta deploys.
