# Kafka

[日本語版はこちら](kafka.ja.md)

---

## What is it?

Apache Kafka is a distributed event streaming platform originally developed at LinkedIn and open-sourced in 2011. It lets applications publish and subscribe to streams of events (messages) in a fault-tolerant, high-throughput way. Kafka is designed to handle millions of events per second and retain them for configurable periods -- from hours to forever.

Think of Kafka like a newspaper printing press combined with a library. The press (producers) prints newspapers (events) continuously. The library (Kafka brokers) stores every edition in order. Readers (consumers) can come at any time and read from wherever they left off -- even if they were gone for days, they can catch up by reading all the editions they missed. Multiple readers can read the same newspaper independently, at their own pace.

This is fundamentally different from a traditional message queue (like RabbitMQ), where messages are consumed once and disappear. In Kafka, events are stored durably and can be read by multiple consumers independently.

---

## Why does it matter?

For volta-auth-proxy, Kafka matters as an **event streaming backbone** for audit logs and system integration. When authentication events happen (login, logout, failure, permission change), multiple systems may need to know:

- The audit log store ([Elasticsearch](elasticsearch.md)) needs to index the event
- The alerting system needs to check for anomalies
- The billing system needs to count active users
- The analytics dashboard needs to update real-time metrics
- A compliance system needs to archive the event

Without Kafka, volta would need to send the event to each system directly. If any system is down, the event is lost. With Kafka, volta publishes the event once, and each system consumes it independently, at its own pace, with guaranteed delivery.

---

## How does it work?

### Core concepts

| Concept | Description |
|---------|-------------|
| **Producer** | Application that publishes events to Kafka (e.g., volta-auth-proxy) |
| **Consumer** | Application that reads events from Kafka (e.g., Elasticsearch sink) |
| **Topic** | A named stream of events (e.g., `volta.audit.events`) |
| **Partition** | A topic is split into partitions for parallelism. Events within a partition are ordered. |
| **Offset** | A sequential ID for each event within a partition. Consumers track their offset. |
| **Broker** | A Kafka server that stores events. Multiple brokers form a cluster. |
| **Consumer Group** | A group of consumers that divide partitions among themselves for load balancing. |

### Event flow

```
  volta-auth-proxy (Producer)
       │
       │  Publish event:
       │  { "type": "login.success",
       │    "user": "alice@acme.com",
       │    "tenant": "acme",
       │    "timestamp": "2024-01-15T10:30:00Z" }
       │
       ▼
  ┌──────────────────────────────────┐
  │           Kafka Cluster           │
  │                                   │
  │  Topic: volta.audit.events        │
  │  ┌─────────┐ ┌─────────┐        │
  │  │ Part. 0  │ │ Part. 1  │       │
  │  │ offset 0 │ │ offset 0 │       │
  │  │ offset 1 │ │ offset 1 │       │
  │  │ offset 2 │ │ ...      │       │
  │  └─────────┘ └─────────┘        │
  └──────────────────────────────────┘
       │              │
       ▼              ▼
  Consumer Group A   Consumer Group B
  (Elasticsearch)    (Alerting System)
```

### Why Kafka over direct integration?

```
  Without Kafka:                    With Kafka:
  volta ──► Elasticsearch           volta ──► Kafka ──► Elasticsearch
  volta ──► Alerting System                        ──► Alerting
  volta ──► Analytics                              ──► Analytics
  volta ──► Compliance                             ──► Compliance

  Problem: If Elasticsearch is down,   Kafka stores events durably.
  volta blocks or loses the event.     Consumers catch up when ready.
```

### Kafka vs alternatives

| Feature | Kafka | RabbitMQ | Redis Streams | Amazon SQS |
|---------|-------|----------|---------------|------------|
| Durability | Disk-based, configurable retention | Memory + disk | Memory + optional persistence | Cloud-managed |
| Throughput | Very high (millions/sec) | High (100K/sec) | Very high | High |
| Message replay | Yes (consumers track offsets) | No (consumed once) | Yes (limited) | No |
| Ordering | Per-partition | Per-queue | Per-stream | Best-effort |
| Multiple consumers | Yes (independent groups) | Yes (but message consumed once per queue) | Yes | No (unless SNS fan-out) |
| Complexity | High (cluster, ZooKeeper/KRaft) | Moderate | Low | Low (managed) |

---

## How does volta-auth-proxy use it?

Kafka is an **optional external audit log sink** for volta-auth-proxy, alongside [Elasticsearch](elasticsearch.md). volta publishes authentication events to a Kafka topic, and downstream consumers process them independently.

### Architecture

```
  volta-auth-proxy
       │
       │  Audit events
       │
       ├──► PostgreSQL (always -- primary storage)
       │
       └──► Kafka topic: volta.audit.events (optional)
                │
                ├──► Consumer: Elasticsearch indexer
                ├──► Consumer: Alerting / anomaly detection
                ├──► Consumer: Analytics pipeline
                └──► Consumer: Compliance archive (S3, etc.)
```

### Event schema

```json
{
  "event_id": "550e8400-e29b-41d4-a716-446655440000",
  "event_type": "auth.login.success",
  "timestamp": "2024-01-15T10:30:00.000Z",
  "tenant_id": "acme",
  "user_id": "usr_abc123",
  "email": "alice@acme.com",
  "ip": "203.0.113.42",
  "user_agent": "Mozilla/5.0 ...",
  "metadata": {
    "provider": "google",
    "session_id": "ses_xyz789"
  }
}
```

### Partitioning strategy

volta partitions events by `tenant_id`. This ensures all events for a single tenant are ordered (within a partition) and can be consumed efficiently by tenant-specific processors:

```
  Partition 0: [acme events in order]
  Partition 1: [widgets-inc events in order]
  Partition 2: [startup-co events in order]
```

### Why optional, not required?

Same reasoning as [Elasticsearch](elasticsearch.md): volta's philosophy is minimal dependencies. Many deployments only need Postgres for audit logs. Kafka adds:

- **Operational complexity**: A cluster (3+ brokers minimum for production)
- **Resource usage**: Kafka brokers need significant RAM and disk
- **Expertise**: Kafka operations (rebalancing, partition management, consumer lag) require specialized knowledge

Kafka makes sense when:

- Multiple downstream systems need to consume audit events
- You need guaranteed delivery even when downstream systems are temporarily offline
- You already run Kafka for other services
- Event volume exceeds what Postgres can handle for real-time queries

---

## Common mistakes and attacks

### Mistake 1: Under-partitioning topics

Too few partitions limits consumer parallelism. If you have one partition, only one consumer in a group can read at a time. Start with at least as many partitions as you expect consumer instances.

### Mistake 2: Not monitoring consumer lag

Consumer lag is the difference between the latest event offset and the consumer's current offset. If lag grows, the consumer is falling behind. For audit logs, growing lag means your Elasticsearch or alerting system is missing recent events. Set up lag monitoring and alerts.

### Mistake 3: Setting retention too short

If Kafka retention is set to 24 hours and a downstream consumer is down for 48 hours, those events are lost. For audit logs, set retention to at least 7 days, or longer if compliance requires it.

### Mistake 4: Not securing Kafka

Kafka has optional authentication (SASL) and encryption (TLS), but they are disabled by default. If Kafka is reachable on the network, anyone can produce fake events or consume sensitive audit data. Enable SASL + TLS in production.

### Attack: Audit log injection

If an attacker can produce messages to the Kafka audit topic, they can inject fake events to:
- Create cover for their actions (flood with noise)
- Frame another user (inject fake "login" events)
- Trigger false alerts (inject mass "login_failed" events)

Restrict Kafka producer ACLs so that only volta-auth-proxy can write to the audit topic.

---

## Further reading

- [Apache Kafka documentation](https://kafka.apache.org/documentation/) -- Official reference.
- [Kafka: The Definitive Guide](https://www.confluent.io/resources/kafka-the-definitive-guide-v2/) -- Comprehensive book by Confluent.
- [elasticsearch.md](elasticsearch.md) -- Alternative/complementary audit log sink.
- [docker.md](docker.md) -- Running Kafka alongside volta.
