# Audit Log

[日本語版はこちら](audit-log.ja.md)

---

## What is it?

An audit log is a chronological record of who did what, when, and from where. Every significant action in a system -- login, logout, role change, member removal -- is recorded with enough detail to reconstruct what happened after the fact. Audit logs are immutable: once written, they are never edited or deleted.

Think of it like a security camera recording at a bank. The camera does not prevent robberies, but it records everything that happens. After an incident, you rewind the tape to see exactly who entered, what they did, and when they left. Nobody can erase the tape. The recording exists independently of the actions it recorded.

In volta-auth-proxy, every auth event is recorded in the `audit_logs` table. The `AuditService.java` class writes structured log entries with actor, target, timestamp, IP address, and request ID. These logs are scoped to tenants and retained for 365 days by default.

---

## Why does it matter?

Without audit logs:

- **No forensics**: After a security incident, you cannot answer "who accessed what?"
- **No accountability**: Users can deny actions ("I did not delete that member")
- **No compliance**: Regulations like SOC 2, GDPR, and HIPAA require audit trails
- **No debugging**: When something goes wrong, you cannot trace the sequence of events
- **No anomaly detection**: You cannot spot unusual patterns (login from 3 countries in 1 hour)

---

## How does it work?

### Anatomy of an audit log entry

```
  ┌────────────────────────────────────────────────────┐
  │ Audit Log Entry                                    │
  │                                                    │
  │ timestamp:    2026-03-31T14:32:07.123Z             │
  │ event_type:   MEMBER_ROLE_CHANGED                  │
  │ actor_id:     550e8400-e29b-41d4-...  (who)        │
  │ actor_ip:     192.168.1.100           (from where) │
  │ tenant_id:    7c9e6679-7425-40de-...  (which org)  │
  │ target_type:  MEMBER                  (what kind)  │
  │ target_id:    a8098c1a-f86e-11da-...  (which one)  │
  │ detail:       { "old_role": "MEMBER",              │
  │                 "new_role": "ADMIN" }  (specifics) │
  │ request_id:   b3e2a1f4-9d7c-4e8a-...  (trace)     │
  └────────────────────────────────────────────────────┘
```

### What gets logged

| Event | When it happens | Detail |
|-------|----------------|--------|
| LOGIN_SUCCESS | User completes OIDC login | Provider, email |
| LOGIN_FAILURE | OIDC callback fails | Reason (state mismatch, nonce invalid, etc.) |
| LOGOUT | User logs out | - |
| TENANT_JOINED | User accepts invitation | Tenant name, role |
| TENANT_SWITCHED | User switches tenant | From/to tenant |
| INVITATION_CREATED | Admin creates invite | Email, role, max_uses |
| INVITATION_ACCEPTED | User accepts invite | Invitation code |
| INVITATION_CANCELLED | Admin cancels invite | Invitation code |
| MEMBER_ROLE_CHANGED | Admin changes role | Old/new role |
| MEMBER_REMOVED | Admin removes member | Member email |
| SESSION_REVOKED | User revokes session | Session ID |
| ALL_SESSIONS_REVOKED | User revokes all | Count |
| KEY_ROTATED | Owner rotates key | Kid |
| KEY_REVOKED | Owner revokes key | Kid |

### Immutability

Audit logs are append-only. The database table has no UPDATE or DELETE operations -- only INSERT. This guarantees that logged events cannot be tampered with after the fact.

```
  ┌──────────────────────────────────────┐
  │ audit_logs table                     │
  │                                      │
  │ INSERT: ✓ Always allowed             │
  │ SELECT: ✓ For authorized users       │
  │ UPDATE: ✗ NEVER                      │
  │ DELETE: ✗ NEVER (retention policy     │
  │            handles old entries)       │
  └──────────────────────────────────────┘
```

---

## How does volta-auth-proxy use it?

### AuditService.java

```java
// AuditService.java
public void log(Context ctx, String eventType, AuthPrincipal actor,
                String targetType, String targetId, Map<String, Object> detail) {
    UUID requestId = ctx.attribute("requestId");
    String detailJson = objectMapper.writeValueAsString(detail);
    store.insertAuditLog(
        eventType,
        actor.userId(),
        clientIp(ctx),          // X-Forwarded-For → first IP
        actor.tenantId(),
        targetType,
        targetId,
        detailJson,
        requestId
    );
    sink.publish(sinkEvent);    // AuditSink for external consumers
}
```

### DSL-defined audit events

The [state machine](state-machine.md) DSL defines audit actions as part of transitions:

```yaml
# auth-machine.yaml
callback_success:
  actions:
    - { type: side_effect, action: upsert_user }
    - { type: side_effect, action: create_session }
    - { type: audit, event: LOGIN_SUCCESS }     # ← audit log entry

logout_browser:
  actions:
    - { type: side_effect, action: invalidate_session }
    - { type: audit, event: LOGOUT }            # ← audit log entry
```

### Tenant-scoped audit access

Audit logs are scoped to tenants. ADMIN+ users can view their tenant's logs via the admin UI:

```yaml
# auth-machine.yaml
show_audit_logs:
  trigger: "GET /admin/audit"
  guard: "membership.role in ['ADMIN', 'OWNER']"
  next: AUTHENTICATED
```

### AuditSink for external consumers

`AuditSink.java` publishes audit events to external systems (e.g., SIEM, logging services) in addition to the database:

```java
// AuditSink.java publishes structured events
// External systems can consume for:
// - Security monitoring
// - Compliance reporting
// - Anomaly detection
```

### Retention policy

```yaml
# dsl/policy.yaml
audit:
  retention:
    default_days: 365
    configurable: true
    env_var: AUDIT_RETENTION_DAYS
```

Old audit entries are purged after the retention period. This balances compliance requirements with storage costs.

### Request ID tracing

Every audit log entry includes a `request_id`. This UUID is generated at the start of each HTTP request and flows through the entire request lifecycle. If a single request triggers multiple audit events (rare but possible), they share the same request_id, making it easy to correlate.

---

## Common mistakes and attacks

### Mistake 1: Logging too little

If you only log "login" and "logout", you cannot investigate a role escalation attack. volta logs 14 event types covering all security-relevant actions.

### Mistake 2: Logging PII unnecessarily

Audit logs should contain enough to identify WHO did WHAT, but not sensitive data like passwords or full request bodies. volta logs user IDs and emails but never passwords or tokens.

### Mistake 3: Mutable audit logs

If audit logs can be edited, an attacker who gains access can cover their tracks. volta's audit_logs table is append-only with no UPDATE or DELETE access.

### Mistake 4: No tenant isolation in audit access

If ADMIN of Tenant A can see Tenant B's audit logs, that is a data leak. volta filters all audit queries by `tenant_id` from the JWT.

### Attack: Audit log flooding

An attacker makes thousands of requests to generate noise in the audit log, hoping to hide their real attack in the flood. volta's [rate limiting](enforcement.md) prevents this, and the `request_id` field helps correlate related events.

---

## Further reading

- [dsl.md](dsl.md) -- Audit events defined in the DSL.
- [session.md](session.md) -- Session events logged in audit.
- [rbac.md](rbac.md) -- Role requirements for viewing audit logs.
- [tenant.md](tenant.md) -- Tenant scoping of audit logs.
- [internal-api.md](internal-api.md) -- API operations that generate audit entries.
- [invitation-flow.md](invitation-flow.md) -- Invitation events logged in audit.
