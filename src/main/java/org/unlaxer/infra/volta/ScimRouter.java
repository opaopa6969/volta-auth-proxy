package org.unlaxer.infra.volta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ScimRouter {
    private final SqlStore store;
    private final AppConfig config;
    private final ObjectMapper objectMapper;

    public ScimRouter(SqlStore store, AppConfig config, ObjectMapper objectMapper) {
        this.store = store;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    public void register(Javalin app) {
        app.before("/scim/v2/*", ctx -> {
            String auth = ctx.header("Authorization");
            if (auth == null || !auth.startsWith("Bearer volta-service:")) {
                ctx.header("WWW-Authenticate", "Bearer realm=\"volta-scim\"");
                ctx.status(401);
                ctx.skipRemainingHandlers();
                return;
            }
            String provided = auth.substring("Bearer volta-service:".length()).trim();
            if (config.serviceToken().isBlank() || !SecurityUtils.constantTimeEquals(config.serviceToken(), provided)) {
                ctx.status(403);
                ctx.skipRemainingHandlers();
            }
        });

        app.get("/scim/v2/Users", ctx -> {
            UUID tenantId = UUID.fromString(ctx.queryParam("tenantId"));
            List<Map<String, Object>> resources = store.listScimUsers(tenantId).stream()
                    .map(u -> Map.of(
                            "id", u.id().toString(),
                            "userName", u.email(),
                            "active", u.active(),
                            "name", Map.of("formatted", u.displayName() == null ? "" : u.displayName())
                    )).toList();
            ctx.json(Map.of("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:ListResponse"), "Resources", resources));
        });

        app.post("/scim/v2/Users", ctx -> {
            JsonNode body = objectMapper.readTree(ctx.body());
            UUID tenantId = UUID.fromString(body.path("tenantId").asText());
            String email = body.path("userName").asText();
            String displayName = body.path("name").path("formatted").asText(email);
            UUID userId = store.createScimUser(tenantId, email, displayName);
            ctx.status(201).json(Map.of("id", userId.toString(), "userName", email, "active", true));
        });

        app.get("/scim/v2/Users/{id}", ctx -> {
            UUID tenantId = UUID.fromString(ctx.queryParam("tenantId"));
            UUID userId = UUID.fromString(ctx.pathParam("id"));
            SqlStore.BasicUserRecord u = store.findScimUser(tenantId, userId)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "SCIM user not found"));
            ctx.json(Map.of("id", u.id().toString(), "userName", u.email(), "active", u.active()));
        });

        app.put("/scim/v2/Users/{id}", ctx -> {
            JsonNode body = objectMapper.readTree(ctx.body());
            UUID userId = UUID.fromString(ctx.pathParam("id"));
            String email = body.path("userName").asText();
            String displayName = body.path("name").path("formatted").asText(email);
            boolean active = body.path("active").asBoolean(true);
            int updated = store.updateScimUser(userId, email, displayName, active);
            if (updated == 0) throw new ApiException(404, "NOT_FOUND", "SCIM user not found");
            ctx.json(Map.of("id", userId.toString(), "userName", email, "active", active));
        });

        app.patch("/scim/v2/Users/{id}", ctx -> {
            JsonNode body = objectMapper.readTree(ctx.body());
            UUID userId = UUID.fromString(ctx.pathParam("id"));
            String email = body.path("userName").asText("patched-" + userId + "@example.local");
            String displayName = body.path("displayName").asText("patched");
            boolean active = body.path("active").asBoolean(true);
            int updated = store.updateScimUser(userId, email, displayName, active);
            if (updated == 0) throw new ApiException(404, "NOT_FOUND", "SCIM user not found");
            ctx.json(Map.of("id", userId.toString(), "active", active));
        });

        app.delete("/scim/v2/Users/{id}", ctx -> {
            UUID tenantId = UUID.fromString(ctx.queryParam("tenantId"));
            UUID userId = UUID.fromString(ctx.pathParam("id"));
            int updated = store.deactivateScimMembership(tenantId, userId);
            if (updated == 0) throw new ApiException(404, "NOT_FOUND", "SCIM user not found");
            ctx.status(204);
        });

        app.get("/scim/v2/Groups", ctx -> ctx.json(Map.of("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:ListResponse"), "Resources", List.of())));
        app.post("/scim/v2/Groups", ctx -> ctx.status(201).json(Map.of("id", SecurityUtils.newUuid().toString(), "displayName", "group")));
    }
}
