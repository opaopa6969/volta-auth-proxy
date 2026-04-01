# CockroachDB (CRDB)

[日本語版はこちら](crdb.ja.md)

---

## What is it?

CockroachDB (often abbreviated CRDB) is a distributed SQL database designed to survive failures and scale horizontally across multiple machines, data centers, or even continents. It speaks the PostgreSQL wire protocol, so applications that talk to Postgres can often talk to CockroachDB with minimal changes.

Think of it like a chain of banks. If one branch burns down, your money is still safe because it is replicated across other branches. You can open a new branch in Tokyo and your customers in Japan get faster service. CockroachDB does this for your data: it replicates across nodes automatically, survives failures, and serves queries from the nearest location.

The name comes from the cockroach -- an organism famously difficult to kill. The database is designed to be equally hard to take down.

---

## Why does it matter?

CockroachDB matters in the auth world because ZITADEL, a well-known open-source identity platform, uses it as its primary database. When teams evaluate ZITADEL, they encounter CockroachDB as a dependency. Understanding CRDB explains why ZITADEL's deployment is heavier than it first appears -- and why volta-auth-proxy chose plain PostgreSQL instead.

---

## What CockroachDB does well

| Strength | Detail |
|----------|--------|
| **Survives failures** | Kill a node, the cluster keeps running. No manual failover. |
| **Horizontal scaling** | Add nodes to handle more data and queries. No sharding logic in your app. |
| **Geo-distribution** | Replicate data across regions. Users hit the nearest node. |
| **SQL compatible** | Standard SQL, PostgreSQL wire protocol. Familiar tools work. |
| **Serializable isolation** | Strongest consistency model by default. No dirty reads, no phantom reads. |
| **No master node** | Every node can serve reads and writes. No single point of failure. |

For companies running global services with millions of users across continents, CockroachDB solves real problems.

---

## Why ZITADEL uses it

ZITADEL is designed as a cloud-native identity platform that can scale to millions of users. Its architecture assumes:

- Multiple instances across regions
- High availability (no downtime for auth)
- Eventually consistent or strongly consistent reads depending on configuration
- Event sourcing for the identity data model

CockroachDB fits this architecture because it provides distributed consensus and multi-region replication out of the box. ZITADEL's event store pattern maps naturally to CockroachDB's serializable transactions.

```
  ZITADEL architecture:
  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
  │ ZITADEL      │  │ ZITADEL      │  │ ZITADEL      │
  │ (US-East)    │  │ (EU-West)    │  │ (AP-Tokyo)   │
  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘
         │                 │                 │
  ┌──────▼─────────────────▼─────────────────▼───────┐
  │            CockroachDB Cluster                    │
  │  ┌─────┐   ┌─────┐   ┌─────┐   ┌─────┐          │
  │  │Node1│   │Node2│   │Node3│   │Node4│  ...      │
  │  └─────┘   └─────┘   └─────┘   └─────┘          │
  │  (data replicated across nodes automatically)     │
  └──────────────────────────────────────────────────┘
```

This is powerful. It is also complex.

---

## Why volta uses plain PostgreSQL instead

### 1. volta does not need distributed consensus

volta-auth-proxy is designed for small-to-medium SaaS applications. A single PostgreSQL instance handles thousands of concurrent sessions without breaking a sweat. The typical volta deployment looks like:

```
  volta architecture:
  ┌──────────────┐
  │ volta-auth-  │
  │ proxy        │
  └──────┬───────┘
         │
  ┌──────▼───────┐
  │ PostgreSQL   │
  │ (single      │
  │  instance)   │
  └──────────────┘
```

Two components. That is the entire deployment. No cluster management, no node discovery, no distributed consensus.

### 2. Operational simplicity

CockroachDB is impressive technology, but running it means:

| Task | PostgreSQL | CockroachDB |
|------|-----------|-------------|
| Install | `apt install postgresql` or Docker one-liner | Multi-node cluster setup |
| Backup | `pg_dump` | Distributed backup coordination |
| Monitoring | pgAdmin, standard tools | CockroachDB-specific metrics |
| Upgrade | Standard package upgrade | Rolling upgrade across nodes |
| Troubleshooting | 30+ years of community knowledge | Younger community, less tribal knowledge |
| Team knowledge | Almost every backend developer knows Postgres | CRDB expertise is rare |

For a team of 2-5 developers building a SaaS, PostgreSQL means "a database everyone already knows." CockroachDB means "a database someone needs to learn."

### 3. Cost

A single PostgreSQL instance on a small VPS costs $5-20/month. A CockroachDB cluster (minimum 3 nodes for proper replication) costs significantly more, both in compute and in operational attention.

### 4. volta's scaling strategy

When volta needs more performance, the path is:

1. **Vertical scaling:** Bigger Postgres instance. Handles most SaaS use cases up to 100k+ users.
2. **Read replicas:** PostgreSQL native streaming replication for read scaling.
3. **Redis sessions:** volta already supports Redis for session storage (`SESSION_STORE=redis`), offloading the hottest queries.

This is boring technology. Boring is good. Boring means predictable, well-understood, and debuggable at 3 AM.

---

## When CockroachDB makes sense

- You are building a globally distributed service across multiple regions
- You need zero-downtime deployments with automatic failover
- Your data volume exceeds what a single PostgreSQL instance can handle
- You have an ops team experienced with distributed systems

---

## When PostgreSQL makes more sense

- Your service runs in a single region (most SaaS applications)
- Your team is small and needs to minimize operational complexity
- You want the largest ecosystem of tools, extensions, and community support
- You value simplicity over theoretical scalability you may never need

---

## Further reading

- [database.md](database.md) -- How volta uses PostgreSQL.
- [keycloak.md](keycloak.md) -- Another auth system and its database choices.
- [self-hosting.md](self-hosting.md) -- Why simpler infrastructure matters for self-hosting.
