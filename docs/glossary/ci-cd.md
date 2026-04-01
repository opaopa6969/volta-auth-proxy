# CI/CD (Continuous Integration / Continuous Delivery)

[日本語版はこちら](ci-cd.ja.md)

---

## What is it?

CI/CD stands for Continuous Integration and Continuous Delivery (or Continuous Deployment). It is the practice of automatically building, testing, and deploying your code every time you push changes. Instead of manually building and deploying, a pipeline does it for you -- consistently, reliably, and without human error.

Think of it like an automated car wash. You drive your car (push code) into the entrance. The machine automatically soaps, scrubs, rinses, and dries your car in the exact same order every time. You do not need to remember each step or worry about skipping one. CI/CD is the same for software: push code, and the pipeline automatically compiles, tests, packages, and deploys it.

**CI (Continuous Integration)** means: every code push triggers an automated build and test. Broken code is caught immediately.

**CD (Continuous Delivery)** means: after passing tests, the code is automatically packaged and ready to deploy (you click a button). **Continuous Deployment** goes further -- it deploys automatically without human approval.

---

## Why does it matter?

- **Catches bugs early.** A failing test on push is cheaper to fix than a bug discovered in [production](production.md) by a customer.
- **Reduces deployment fear.** When deploying is automated and tested, teams deploy more often and with less risk.
- **Ensures consistency.** The same build process runs every time. No "works on my machine" problems.
- **Speeds up delivery.** Features reach users in hours instead of weeks.
- **Enforces quality gates.** Code that does not pass tests does not get deployed. Period.

---

## How does it work?

### The CI/CD pipeline

```
  Developer pushes code to main branch
       │
       ▼
  ┌──────────────────────────────────────────────────┐
  │              CI/CD Pipeline                       │
  │                                                    │
  │  ┌─────────┐  ┌─────────┐  ┌─────────┐          │
  │  │  Build   │─▶│  Test   │─▶│ Package │          │
  │  │          │  │         │  │         │          │
  │  │ mvn      │  │ mvn     │  │ mvn     │          │
  │  │ compile  │  │ test    │  │ package │          │
  │  └─────────┘  └─────────┘  └────┬────┘          │
  │                                  │               │
  │                    ┌─────────────┴──────────┐    │
  │                    │                        │    │
  │               ┌────┴────┐            ┌──────┴──┐ │
  │               │ Deploy  │            │ Deploy  │ │
  │               │ Staging │            │  Prod   │ │
  │               └─────────┘            └─────────┘ │
  │                                (manual approval)  │
  └──────────────────────────────────────────────────┘
```

### CI: Build and test

Every push triggers compilation and testing:

```
  git push origin main
       │
       ▼
  GitHub Actions (or similar) starts:

  Step 1: Checkout code
  Step 2: Set up Java 21
  Step 3: mvn compile        ← Does it compile?
  Step 4: mvn test           ← Do all tests pass?
  Step 5: mvn package        ← Build the fat JAR

  ┌────────┐
  │ Result │
  ├────────┤
  │ ✓ Pass │ → Ready for deployment
  │ ✗ Fail │ → Developer notified, deploy blocked
  └────────┘
```

### CD: Package and deploy

After tests pass, the pipeline packages and deploys:

```
  Tests pass
       │
       ▼
  ┌──────────────────────────┐
  │  Build artifact           │
  │  volta-auth-proxy-1.2.0.jar │
  └────────────┬─────────────┘
               │
       ┌───────┴───────┐
       │               │
       ▼               ▼
  ┌─────────┐    ┌─────────┐
  │ Deploy  │    │  Store  │
  │ to      │    │ artifact│
  │ staging │    │ (backup)│
  └────┬────┘    └─────────┘
       │
  Smoke tests pass?
       │
       ▼
  ┌─────────┐
  │ Deploy  │
  │ to prod │
  └─────────┘
```

### Example GitHub Actions workflow

```yaml
name: CI/CD
on:
  push:
    branches: [main]

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Build and test
        run: mvn clean verify

      - name: Package
        run: mvn package -DskipTests

      - name: Upload artifact
        uses: actions/upload-artifact@v4
        with:
          name: volta-auth-proxy
          path: target/volta-auth-proxy-*.jar

  deploy-staging:
    needs: build-and-test
    runs-on: ubuntu-latest
    steps:
      - name: Deploy to staging
        run: ./deploy.sh staging

  deploy-production:
    needs: deploy-staging
    runs-on: ubuntu-latest
    environment: production  # requires manual approval
    steps:
      - name: Deploy to production
        run: ./deploy.sh production
```

---

## How does volta-auth-proxy use it?

### volta's CI/CD pipeline

A typical CI/CD pipeline for volta includes:

```
  ┌────────────────────────────────────────────────────┐
  │  volta-auth-proxy CI/CD Pipeline                   │
  │                                                     │
  │  1. ✓ Compile (Java 21 + Maven)                    │
  │  2. ✓ Run unit tests                               │
  │  3. ✓ Run integration tests (test DB + Flyway)     │
  │  4. ✓ Check dependencies for vulnerabilities       │
  │  5. ✓ Build fat JAR                                │
  │  6. ✓ Deploy to staging                            │
  │  7. ✓ Run smoke tests on staging                   │
  │  8. ◎ Deploy to production (manual gate)           │
  └────────────────────────────────────────────────────┘
```

### What CI/CD validates for volta

| Check | What it catches |
|-------|----------------|
| `mvn compile` | Syntax errors, type mismatches |
| `mvn test` | Logic bugs, regression in auth flow |
| Flyway migration test | Schema changes that break existing data |
| Dependency check | Known vulnerabilities in libraries |
| Integration test | ForwardAuth, OIDC flow, session lifecycle |
| Smoke test | Basic health check on deployed instance |

### Database migrations in CI/CD

The pipeline runs [Flyway migrations](migration.md) against a test database to verify:
- New migrations apply cleanly
- Existing migrations have not been modified (checksum validation)
- Application code works with the new schema

```
  CI Pipeline:
  ┌────────────────────────────────────────┐
  │ 1. Start test PostgreSQL (Docker)      │
  │ 2. Flyway migrate (apply all V*.sql)   │
  │ 3. Run tests against migrated DB       │
  │ 4. Destroy test DB                     │
  └────────────────────────────────────────┘
```

---

## Common mistakes and attacks

### Mistake 1: Tests that pass locally but fail in CI

"Works on my machine" often means the CI environment is different -- different Java version, different OS, missing environment variables. Use the same Java [LTS](lts.md) version everywhere.

### Mistake 2: Skipping tests to deploy faster

`mvn package -DskipTests` in the CI pipeline defeats the entire purpose. Never skip tests in the deployment pipeline.

### Mistake 3: No staging environment

Going directly from CI to production means your first real test is in production. Always deploy to staging first.

### Mistake 4: Secrets in the CI configuration

Database passwords, Google OIDC secrets, and JWT private keys must be stored in the CI platform's secret manager, not in the workflow file (which is committed to git).

### Mistake 5: No rollback plan in the pipeline

If production deployment fails, the pipeline should either automatically roll back to the previous version or make it trivially easy to do so manually.

---

## Further reading

- [deployment.md](deployment.md) -- The deployment process CI/CD automates.
- [production.md](production.md) -- The final target of the CD pipeline.
- [migration.md](migration.md) -- Database migrations tested and applied by CI/CD.
- [git.md](git.md) -- The version control system that triggers CI/CD.
- [maven.md](maven.md) -- The build tool CI/CD invokes.
