# Elasticsearch

[日本語版はこちら](elasticsearch.ja.md)

---

## What is it?

Elasticsearch is a distributed search and analytics engine built on Apache Lucene. It stores data as JSON documents and lets you search, filter, and aggregate that data in near real-time. Originally designed for full-text search (think: searching through millions of documents for a keyword), Elasticsearch has become the go-to solution for log storage, metrics analysis, and application search.

Think of Elasticsearch like a librarian with a photographic memory. You hand the librarian millions of books (documents), and they build a mental index of every word in every book. When you ask "find all books mentioning 'authentication failure' between January and March," the librarian answers in milliseconds. A regular database (Postgres) is like a librarian who has to read through every book each time you ask -- still works, but much slower for these kinds of queries.

Elasticsearch is often deployed as part of the "ELK Stack" (Elasticsearch + Logstash + Kibana) or the newer "Elastic Stack." Logstash collects and transforms data, Elasticsearch stores and indexes it, and Kibana provides a web dashboard for visualization.

---

## Why does it matter?

For a SaaS identity gateway like volta-auth-proxy, audit logs are critical. You need to answer questions like:

- "Show me all failed login attempts for tenant acme.com in the last 24 hours"
- "Which IP addresses generated the most authentication requests this week?"
- "When did user alice@acme.com last log in, and from where?"
- "Are there any unusual patterns in authentication traffic?"

These are search and aggregation queries -- exactly what Elasticsearch excels at. While Postgres can store audit logs, it struggles with:

1. **Full-text search** across log messages
2. **Time-series aggregations** (requests per minute, error rate trends)
3. **High write throughput** (thousands of log entries per second)
4. **Long-term retention** without impacting database performance

---

## How does it work?

### Core concepts

| Concept | Postgres equivalent | Description |
|---------|-------------------|-------------|
| **Index** | Table | A collection of documents (e.g., `volta-audit-2024-01`) |
| **Document** | Row | A single JSON record |
| **Field** | Column | A key in the JSON document |
| **Mapping** | Schema | Defines field types (text, keyword, date, etc.) |
| **Shard** | Partition | A horizontal slice of an index (for distribution) |
| **Replica** | Read replica | A copy of a shard (for redundancy and read throughput) |

### How indexing works

When you send a document to Elasticsearch, it:

1. Parses the JSON
2. Analyzes text fields (tokenizing, lowercasing, stemming)
3. Builds an **inverted index** (word -> list of documents containing that word)
4. Stores the original document

```
  Document: { "event": "login_failed", "user": "alice@acme.com",
              "ip": "1.2.3.4", "timestamp": "2024-01-15T10:30:00Z" }

  Inverted Index:
    "login_failed"    → [doc1, doc47, doc892]
    "alice@acme.com"  → [doc1, doc23]
    "1.2.3.4"         → [doc1, doc15, doc16]
```

### Search query example

```json
{
  "query": {
    "bool": {
      "must": [
        { "match": { "event": "login_failed" } },
        { "term": { "tenant_id": "acme" } }
      ],
      "filter": [
        { "range": { "timestamp": { "gte": "now-24h" } } }
      ]
    }
  },
  "aggs": {
    "by_ip": {
      "terms": { "field": "ip.keyword", "size": 10 }
    }
  }
}
```

This query: "Find all failed logins for tenant acme in the last 24 hours, and show me the top 10 IP addresses."

### Elasticsearch vs alternatives for audit logs

| Feature | Elasticsearch | PostgreSQL | [Kafka](kafka.md) + ClickHouse | Loki |
|---------|--------------|-----------|-------------------------------|------|
| Full-text search | Excellent | Basic (ts_vector) | Limited | Basic (LogQL) |
| Aggregations | Excellent | Good (but slower) | Excellent | Basic |
| Write throughput | Very high | Moderate | Very high | High |
| Storage efficiency | Moderate | Good | Excellent | Excellent |
| Query language | Query DSL / KQL | SQL | SQL (ClickHouse) | LogQL |
| Visualization | Kibana | pgAdmin / Grafana | Grafana | Grafana |
| Operational complexity | High (cluster mgmt) | Low | High | Low |
| Cost (at scale) | High (RAM-hungry) | Low | Moderate | Low |

---

## How does volta-auth-proxy use it?

Elasticsearch is an **optional external audit log sink** for volta-auth-proxy. By default, volta stores audit events in its Postgres database. For organizations that need advanced search, long-term retention, or integration with existing ELK infrastructure, volta can also send audit events to Elasticsearch.

### Architecture

```
  volta-auth-proxy
       │
       │  Audit events (login, logout, failures, etc.)
       │
       ├──► PostgreSQL (always -- primary storage)
       │
       └──► Elasticsearch (optional -- external sink)
                │
                ▼
            Kibana (visualization/dashboards)
```

### What gets sent to Elasticsearch

| Event type | Fields |
|-----------|--------|
| `auth.login.success` | timestamp, user_id, tenant_id, email, ip, user_agent |
| `auth.login.failed` | timestamp, email_attempted, tenant_id, ip, user_agent, reason |
| `auth.logout` | timestamp, user_id, tenant_id, ip |
| `auth.session.expired` | timestamp, user_id, tenant_id, session_id |
| `auth.token.issued` | timestamp, user_id, tenant_id, token_type |
| `admin.user.created` | timestamp, admin_id, tenant_id, target_user_id |
| `admin.tenant.updated` | timestamp, admin_id, tenant_id, changes |

### Why optional, not required?

volta's philosophy is minimal dependencies. Many deployments need only Postgres for audit logs -- querying them with SQL is sufficient. Elasticsearch adds:

- **Operational complexity**: A cluster to manage, monitor, and back up
- **Resource usage**: Elasticsearch is RAM-hungry (plan 1GB+ RAM per node)
- **Cost**: Elastic Cloud pricing or self-hosted infrastructure costs

For small-to-medium deployments, Postgres is enough. Elasticsearch makes sense when:

- You have thousands of auth events per minute
- You need full-text search across audit logs
- You already run an ELK stack for other services
- Compliance requires long-term log retention with fast search

---

## Common mistakes and attacks

### Mistake 1: Not securing Elasticsearch

Elasticsearch has no authentication by default in the OSS version. If exposed to the internet, anyone can read your audit logs (which contain email addresses, IP addresses, and tenant information). Always run behind a firewall, use Elastic's security features, or use a VPN.

### Mistake 2: Not setting up index lifecycle management

Audit log indices grow forever. Without Index Lifecycle Management (ILM), you will run out of disk space. Configure ILM to automatically roll over indices (e.g., daily) and delete old ones (e.g., after 90 days).

### Mistake 3: Over-indexing

Indexing every field as both `text` (full-text searchable) and `keyword` (exact match) doubles storage. For audit logs, most fields should be `keyword` (exact match is sufficient). Only index as `text` fields you actually need full-text search on.

### Mistake 4: Using Elasticsearch as the primary data store

Elasticsearch is a search engine, not a database. It can lose data during node failures if replicas are not configured. Always keep Postgres as the source of truth and use Elasticsearch as a secondary sink.

### Attack: Audit log tampering

If an attacker gains write access to Elasticsearch, they can modify or delete audit logs to cover their tracks. Use write-once indices (or Elasticsearch's data stream immutability features) and forward logs to a separate, hardened cluster for forensic purposes.

---

## Further reading

- [Elasticsearch documentation](https://www.elastic.co/guide/en/elasticsearch/reference/current/index.html) -- Official reference.
- [Kibana documentation](https://www.elastic.co/guide/en/kibana/current/index.html) -- Visualization tool.
- [Elastic Security](https://www.elastic.co/guide/en/elasticsearch/reference/current/security-minimal-setup.html) -- Setting up authentication.
- [kafka.md](kafka.md) -- Alternative/complementary audit log sink.
- [docker.md](docker.md) -- How to run Elasticsearch with volta.
