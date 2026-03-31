package com.volta.authproxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;

import java.util.Map;
import java.util.UUID;

public final class AuditService {
    private final SqlStore store;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditService(SqlStore store) {
        this.store = store;
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
