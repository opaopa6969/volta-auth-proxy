package org.unlaxer.infra.volta;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class AuditService {
    private final SqlStore store;
    private final AuditSink sink;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditService(SqlStore store, AuditSink sink) {
        this.store = store;
        this.sink = sink;
    }

    public void log(Context ctx, String eventType, AuthPrincipal actor, String targetType, String targetId, Map<String, Object> detail) {
        try {
            UUID requestId = ctx.attribute("requestId");
            String detailJson = objectMapper.writeValueAsString(detail == null ? Map.of() : detail);
            store.insertAuditLog(
                    eventType,
                    actor == null ? null : actor.userId(),
                    clientIp(ctx),
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
        } catch (Exception ignored) {
        }
    }

    private static String clientIp(Context ctx) {
        String forwarded = ctx.header("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return ctx.ip();
    }
}
