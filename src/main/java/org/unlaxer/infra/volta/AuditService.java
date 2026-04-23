package org.unlaxer.infra.volta;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import redis.clients.jedis.JedisPooled;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AuditService {
    private final SqlStore store;
    private final AuditSink sink;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Optional: publish login/logout events to Redis for real-time Auth Monitor
    private final JedisPooled authEventJedis;
    private final String authEventChannel;

    private static final Set<String> AUTH_EVENT_TYPES = Set.of(
            "LOGIN_SUCCESS", "LOGOUT", "SESSION_EXPIRED"
    );

    // SAAS-008: map audit events to billing metrics. Unmapped events are
    // ignored so we don't count every internal bookkeeping action.
    private static final Map<String, String> USAGE_METRICS = Map.of(
            "LOGIN_SUCCESS",      "auth.login",
            "LOGIN_FAILED",       "auth.login_failed",
            "LOGOUT",             "auth.logout",
            "SESSION_EXPIRED",    "auth.session_expired",
            "MFA_CHALLENGE",      "auth.mfa_challenge",
            "MFA_VERIFIED",       "auth.mfa_verified",
            "INVITE_CREATED",     "tenant.invite_sent",
            "MEMBER_ADDED",       "tenant.member_added",
            "WEBHOOK_DELIVERED",  "webhook.delivered",
            "WEBHOOK_FAILED",     "webhook.failed"
    );

    public AuditService(SqlStore store, AuditSink sink) {
        this(store, sink, null, null);
    }

    public AuditService(SqlStore store, AuditSink sink, JedisPooled authEventJedis, String authEventChannel) {
        this.store = store;
        this.sink = sink;
        this.authEventJedis = authEventJedis;
        this.authEventChannel = authEventChannel;
    }

    public void log(Context ctx, String eventType, AuthPrincipal actor, String targetType, String targetId, Map<String, Object> detail) {
        try {
            UUID requestId = ctx.attribute("requestId");
            String detailJson = objectMapper.writeValueAsString(detail == null ? Map.of() : detail);
            store.insertAuditLog(
                    eventType,
                    actor == null ? null : actor.userId(),
                    HttpSupport.clientIp(ctx),
                    actor == null ? null : actor.tenantId(),
                    targetType,
                    targetId,
                    detailJson,
                    requestId == null ? UUID.randomUUID() : requestId
            );
            Map<String, Object> sinkEvent = new LinkedHashMap<>();
            sinkEvent.put("event_type", eventType);
            sinkEvent.put("actor_id", actor == null ? null : actor.userId());
            sinkEvent.put("tenant_id", actor == null ? null : actor.tenantId());
            sinkEvent.put("target_type", targetType);
            sinkEvent.put("target_id", targetId);
            sinkEvent.put("request_id", requestId == null ? null : requestId.toString());
            sinkEvent.put("detail", detail == null ? Map.of() : detail);
            sink.publish(sinkEvent);

            // Publish auth events to Redis for real-time Auth Monitor
            if (authEventJedis != null && AUTH_EVENT_TYPES.contains(eventType)) {
                publishAuthEvent(eventType, actor, targetId, requestId);
            }

            // SAAS-008: opportunistically record billing usage for events we
            // care about. We only record when we have a tenant to scope to
            // (anonymous / login-failure events without a principal yield no
            // row). recordUsage swallows its own DB failures.
            String metric = USAGE_METRICS.get(eventType);
            if (metric != null && actor != null && actor.tenantId() != null) {
                store.recordUsage(actor.tenantId(), metric, 1L, null);
            }
        } catch (Exception ignored) {
        }
    }

    private void publishAuthEvent(String eventType, AuthPrincipal actor, String sessionId, UUID requestId) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", eventType);
            event.put("userId", actor != null ? actor.userId().toString() : null);
            event.put("email", actor != null ? actor.email() : null);
            event.put("sessionId", sessionId);
            event.put("requestId", requestId != null ? requestId.toString() : null);
            event.put("ts", System.currentTimeMillis());
            authEventJedis.publish(authEventChannel, objectMapper.writeValueAsString(event));
        } catch (Exception ignored) {
            // Fire-and-forget: never let viz failure break auth flow
        }
    }
}
