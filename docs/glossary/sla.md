# SLA (Service Level Agreement)

[日本語版はこちら](sla.ja.md)

---

## What is it?

An SLA (Service Level Agreement) is a formal contract between a service provider and a customer that defines guaranteed levels of service -- primarily uptime, but also response times, support availability, and what happens when the provider fails to meet the guarantee.

Think of it like a pizza delivery guarantee. "Your pizza in 30 minutes, or it's free." The pizza shop is making a commitment (30 minutes), and there is a consequence if they fail (refund). An SLA is the same thing: "99.9% uptime, or we give you service credits."

SLAs are not aspirational goals. They are contractual obligations with financial penalties. When an enterprise customer signs a contract with a 99.9% uptime SLA, they expect that number to be met. If it is not, they expect compensation -- and may terminate the contract.

---

## Why does it matter?

For enterprise customers, SLAs are table stakes. Their own products depend on your service. If your authentication system goes down, their users cannot log in, their business stops, and they lose money. An SLA gives them:

- **Predictability**: They can plan around your guaranteed uptime.
- **Accountability**: If you fail, there are defined consequences.
- **Legal protection**: The SLA is part of the contract, enforceable by law.

For service providers, SLAs are a double-edged sword. They build trust with customers, but they also create obligations that require significant investment in infrastructure, monitoring, on-call teams, and redundancy.

---

## How does it work?

### Understanding the "nines"

Uptime is measured in "nines." Each additional nine is exponentially harder to achieve:

```
  Uptime     Downtime per year    Downtime per month
  ────────   ──────────────────   ──────────────────
  99%        3.65 days            7.3 hours
  99.9%      8.77 hours           43.8 minutes
  99.95%     4.38 hours           21.9 minutes
  99.99%     52.6 minutes         4.38 minutes
  99.999%    5.26 minutes         26.3 seconds
```

Most SaaS products offer 99.9% ("three nines"). Banks and critical infrastructure aim for 99.99% ("four nines"). Five nines is essentially "never go down" and requires massive investment.

### SLA components

| Component | Description | Example |
|-----------|-------------|---------|
| **Uptime guarantee** | Percentage of time the service is available | 99.9% monthly uptime |
| **Measurement window** | How uptime is calculated | Monthly rolling window |
| **Exclusions** | What does not count as downtime | Scheduled maintenance, customer-caused issues |
| **Service credits** | Compensation when SLA is breached | 10% credit for each 0.1% below target |
| **Response time SLA** | How fast support responds | P1: 15 min, P2: 1 hour, P3: 4 hours |
| **Resolution time SLA** | How fast issues are fixed | P1: 4 hours, P2: 24 hours |

### How SLAs are enforced

```
  Month ends
      │
      ▼
  Calculate actual uptime
  (total minutes - downtime minutes) / total minutes
      │
      ▼
  Compare against SLA target
      │
  ┌───┴───┐
  Met     Missed
  │       │
  ▼       ▼
  Nothing  Calculate service credits
  happens      │
               ▼
           Credit applied to
           next invoice
           (or refund issued)
```

### What it takes to offer an SLA

Offering even a 99.9% SLA requires:

- **Redundancy**: No single point of failure. Multiple servers, multiple availability zones.
- **Monitoring**: Know when something is down before customers notice. Health checks every few seconds.
- **On-call rotation**: Someone must be reachable 24/7 to fix issues.
- **Incident response**: Documented procedures for common failure modes.
- **Automated failover**: Systems that self-heal when components fail.
- **Load testing**: Know your limits before customers hit them.
- **Chaos engineering**: Deliberately break things to prove resilience.

---

## How does volta-auth-proxy use it?

volta-auth-proxy does **not** offer an SLA. This is a deliberate decision aligned with its Phase 1 scope.

### Why no SLA (yet)

| Reason | Detail |
|--------|--------|
| **Single-node architecture** | volta runs as a single process. No redundancy = no SLA |
| **SQLite database** | Single-file DB does not support multi-node replication |
| **No on-call team** | A meaningful SLA requires 24/7 human support |
| **Target audience** | Indie hackers and internal tools tolerate occasional downtime |
| **Self-hosted** | The customer controls uptime, not volta |

### The self-hosted SLA model

When software is self-hosted, the SLA picture changes dramatically:

```
  Managed SaaS (Auth0):
    Auth0 ──► SLA guarantee to customer ──► 99.99% uptime
    Auth0 is responsible for uptime.

  Self-hosted (volta):
    Customer ──► Runs volta on their infra ──► Customer's SLA
    The customer's ops team is responsible for uptime.
    volta provides software quality, not uptime guarantees.
```

volta's responsibility is shipping reliable software. The customer's responsibility is running it reliably. This is the same model as PostgreSQL, Nginx, or any other self-hosted infrastructure.

### What volta does for reliability

Even without a formal SLA, volta is designed for reliability:

- **Fast startup (~200ms)**: If the process crashes, it restarts almost instantly
- **Health check endpoint**: `GET /healthz` returns `{"status":"ok"}` for orchestrator monitoring
- **Graceful shutdown**: In-flight requests complete before the process exits
- **SQLite WAL mode**: Database remains consistent even during crashes
- **Minimal dependencies**: Fewer things that can fail

### The Phase 3 SLA roadmap

When volta moves to Phase 3 (enterprise), an SLA will require:

- PostgreSQL backend (multi-node replication)
- Multi-instance deployment behind a load balancer
- Automated health checks and failover
- On-call engineering support
- Status page with real-time uptime reporting
- Defined service credit structure

---

## Common mistakes and attacks

### Mistake 1: Offering an SLA you cannot meet

An SLA is a contract. If you guarantee 99.9% uptime but run on a single server with no monitoring, you will breach the SLA, pay credits, and lose customer trust. Do not offer an SLA until you have the infrastructure to back it.

### Mistake 2: Not reading the fine print

SLA exclusions matter. "99.9% uptime excluding scheduled maintenance windows of up to 8 hours per month" is very different from "99.9% uptime." Always read the measurement methodology.

### Mistake 3: Confusing SLA with SLO and SLI

| Term | What it is | Who sees it |
|------|-----------|-------------|
| **SLI** (Service Level Indicator) | The actual measurement (e.g., latency, error rate) | Engineering |
| **SLO** (Service Level Objective) | Internal target (e.g., "99.95% uptime target") | Engineering + management |
| **SLA** (Service Level Agreement) | External contract with penalties (e.g., "99.9% or credits") | Customers |

Your SLA should be lower than your SLO, which should be lower than what you actually achieve. This gives you a safety buffer.

### Mistake 4: Treating downtime as binary

"The service was up" is too simple. Was it up but responding in 30 seconds instead of 300ms? Was it up for 90% of users but down for 10%? SLAs must define what "available" means precisely.

---

## Further reading

- [soc2.md](soc2.md) -- Security certification that often accompanies SLA guarantees.
- [health-check.md](health-check.md) -- The endpoint that monitors volta's availability.
- [compliance.md](compliance.md) -- Regulatory requirements that may mandate SLAs.
- [Google SRE Book - SLIs, SLOs, SLAs](https://sre.google/sre-book/service-level-objectives/) -- Definitive guide to service level concepts.
