package org.unlaxer.infra.volta.viz;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.sse.SseClient;
import io.javalin.websocket.WsContext;
import org.unlaxer.infra.volta.*;
import org.unlaxer.tramli.FlowDefinition;
import org.unlaxer.tramli.FlowState;
import org.unlaxer.tramli.MermaidGenerator;
import org.unlaxer.tramli.RenderableGraph;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Visualization endpoints for tramli-viz integration.
 * <ul>
 *   <li>GET /api/v1/admin/flows/{flowId}/transitions — replay API (ADMIN role required)</li>
 *   <li>GET /viz/flows — static graph endpoint (public, for embedding)</li>
 *   <li>GET /viz/auth/stream — SSE stream of login/logout events (SAAS-016)</li>
 * </ul>
 */
public final class VizRouter {

    private static final System.Logger LOG = System.getLogger("volta.viz");

    private final DataSource dataSource;
    private final AuthService authService;
    private final PolicyEngine policy;
    private final ObjectMapper objectMapper;
    private final List<FlowDefinition<?>> flowDefinitions;

    // SAAS-016: auth event SSE broadcast
    private final JedisPooled authEventJedis;  // nullable — SSE disabled if null
    private final String authEventChannel;
    private final Set<SseClient> sseClients = ConcurrentHashMap.newKeySet();
    private volatile JedisPubSub pubSub;

    // AUTH-VIZ Phase 1: tramli-viz WebSocket bridge
    private final String vizFlowChannel;  // nullable — WS disabled if null
    private final Set<WsContext> wsClients = ConcurrentHashMap.newKeySet();
    private volatile JedisPubSub wsPubSub;

    public VizRouter(DataSource dataSource, AuthService authService, PolicyEngine policy,
                     ObjectMapper objectMapper, List<FlowDefinition<?>> flowDefinitions) {
        this(dataSource, authService, policy, objectMapper, flowDefinitions, null, null, null);
    }

    public VizRouter(DataSource dataSource, AuthService authService, PolicyEngine policy,
                     ObjectMapper objectMapper, List<FlowDefinition<?>> flowDefinitions,
                     JedisPooled authEventJedis, String authEventChannel) {
        this(dataSource, authService, policy, objectMapper, flowDefinitions,
             authEventJedis, authEventChannel, null);
    }

    public VizRouter(DataSource dataSource, AuthService authService, PolicyEngine policy,
                     ObjectMapper objectMapper, List<FlowDefinition<?>> flowDefinitions,
                     JedisPooled authEventJedis, String authEventChannel,
                     String vizFlowChannel) {
        this.dataSource = dataSource;
        this.authService = authService;
        this.policy = policy;
        this.objectMapper = objectMapper;
        this.flowDefinitions = flowDefinitions;
        this.authEventJedis = authEventJedis;
        this.authEventChannel = authEventChannel;
        this.vizFlowChannel = vizFlowChannel;
    }

    public void register(Javalin app) {
        // Phase 3: Replay browse — list flows with tenant / type / time filters.
        // Admin tools pick a flow from this list, then load its transitions via
        // the sibling endpoint below.
        app.get("/api/v1/admin/flows", ctx -> {
            AuthPrincipal principal = ctx.attribute("principal");
            if (principal == null) {
                HttpSupport.jsonError(ctx, 401, "AUTHENTICATION_REQUIRED", "Authentication required");
                return;
            }
            policy.enforceMinRole(principal, "ADMIN");

            String tenantIdParam = ctx.queryParam("tenant_id");
            String flowType      = ctx.queryParam("flow_type");
            String sinceParam    = ctx.queryParam("since");
            int limit = parseLimit(ctx.queryParam("limit"), 50, 200);

            UUID tenantId = null;
            if (tenantIdParam != null && !tenantIdParam.isBlank()) {
                try { tenantId = UUID.fromString(tenantIdParam); }
                catch (IllegalArgumentException e) {
                    HttpSupport.jsonError(ctx, 400, "BAD_REQUEST", "tenant_id must be a UUID");
                    return;
                }
            }
            java.time.Instant since = parseSince(sinceParam);

            List<Map<String, Object>> flows = listFlows(tenantId, flowType, since, limit);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("flows", flows);
            body.put("filters", Map.of(
                    "tenant_id", tenantId == null ? null : tenantId.toString(),
                    "flow_type", flowType,
                    "since",     since == null ? null : since.toString(),
                    "limit",     limit
            ));
            ctx.json(body);
        });

        // Phase 2: Replay API — returns all transitions for a flow
        app.get("/api/v1/admin/flows/{flowId}/transitions", ctx -> {
            AuthPrincipal principal = ctx.attribute("principal");
            if (principal == null) {
                HttpSupport.jsonError(ctx, 401, "AUTHENTICATION_REQUIRED", "Authentication required");
                return;
            }
            policy.enforceMinRole(principal, "ADMIN");

            String flowId = ctx.pathParam("flowId");
            // Validate UUID format
            UUID.fromString(flowId);

            List<Map<String, Object>> transitions = listTransitions(flowId);
            ctx.json(Map.of("flowId", flowId, "transitions", transitions));
        });

        // Phase 3: Static graph endpoint — returns all flow definitions as JSON
        app.get("/viz/flows", ctx -> {
            List<Map<String, Object>> flows = new ArrayList<>();
            for (FlowDefinition<?> def : flowDefinitions) {
                flows.add(renderFlowDefinition(def));
            }
            ctx.json(Map.of("flows", flows));
        });

        // SAAS-016: SSE stream of login/logout auth events for Auth Monitor
        if (authEventJedis != null) {
            app.sse("/viz/auth/stream", client -> {
                sseClients.add(client);
                client.sendEvent("connected", "{\"status\":\"connected\"}");
                client.onClose(() -> sseClients.remove(client));
            });

            // Start Redis subscriber in a virtual thread (blocks until shutdown)
            Thread.ofVirtual()
                    .name("auth-event-sse-sub")
                    .start(this::subscribeToAuthEvents);

            LOG.log(System.Logger.Level.INFO, "Auth event SSE stream enabled on /viz/auth/stream (channel: " + authEventChannel + ")");
        }

        // AUTH-VIZ Phase 1: tramli-viz WebSocket bridge.
        // Relays RedisTelemetrySink events (channel: volta:viz:events) to the
        // tramli-viz VizDashboard protocol. On connect we emit `init-multi`
        // with all flow definitions, then forward each telemetry event as
        // `{ type: 'event', event: {...} }`.
        if (authEventJedis != null && vizFlowChannel != null && !vizFlowChannel.isBlank()) {
            app.ws("/viz/ws", ws -> {
                ws.onConnect(ctx -> {
                    wsClients.add(ctx);
                    try {
                        ctx.send(buildInitMultiMessage());
                    } catch (Exception e) {
                        LOG.log(System.Logger.Level.WARNING, "Failed to send init-multi: " + e.getMessage());
                    }
                });
                ws.onClose(ctx -> wsClients.remove(ctx));
                ws.onError(ctx -> wsClients.remove(ctx));
            });

            Thread.ofVirtual()
                    .name("viz-flow-ws-sub")
                    .start(this::subscribeToFlowEvents);

            LOG.log(System.Logger.Level.INFO, "tramli-viz WebSocket bridge enabled on /viz/ws (channel: " + vizFlowChannel + ")");
        }
    }

    private String buildInitMultiMessage() throws Exception {
        List<Map<String, Object>> flows = new ArrayList<>();
        int layer = 1;
        for (FlowDefinition<?> def : flowDefinitions) {
            flows.add(renderFlowDefinitionInfo(def, layer));
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "init-multi");
        msg.put("flows", flows);
        return objectMapper.writeValueAsString(msg);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> renderFlowDefinitionInfo(FlowDefinition<?> def, int layer) {
        RenderableGraph.StateDiagram diagram = ((FlowDefinition) def).toRenderableStateDiagram();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("flowName", def.name());
        info.put("layer", layer);

        // States: position is assigned here with a simple horizontal layout.
        // tramli-viz can override via its own auto-layout, but we provide
        // reasonable defaults so the first render isn't empty.
        List<Map<String, Object>> states = new ArrayList<>();
        int idx = 0;
        for (String stateName : collectStates(diagram)) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", stateName);
            s.put("initial", stateName.equals(diagram.initialState()));
            s.put("terminal", diagram.terminalStates().contains(stateName));
            s.put("x", idx * 160);
            s.put("y", (layer - 1) * 240);
            states.add(s);
            idx++;
        }
        info.put("states", states);

        List<Map<String, Object>> edges = new ArrayList<>();
        for (RenderableGraph.StateEdge edge : diagram.transitions()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("from", edge.from());
            e.put("to", edge.to());
            e.put("type", "auto");
            e.put("label", edge.label() == null ? "" : edge.label());
            edges.add(e);
        }
        info.put("edges", edges);
        return info;
    }

    private static java.util.LinkedHashSet<String> collectStates(RenderableGraph.StateDiagram d) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (d.initialState() != null) out.add(d.initialState());
        for (RenderableGraph.StateEdge e : d.transitions()) {
            out.add(e.from());
            out.add(e.to());
        }
        out.addAll(d.terminalStates());
        return out;
    }

    private void subscribeToFlowEvents() {
        wsPubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                if (wsClients.isEmpty()) return;
                // Rewrap telemetry payload as tramli-viz ServerMessage.
                String wrapped;
                try {
                    var node = objectMapper.readTree(message);
                    Map<String, Object> env = new LinkedHashMap<>();
                    env.put("type", "event");
                    env.put("event", objectMapper.convertValue(node, Map.class));
                    wrapped = objectMapper.writeValueAsString(env);
                } catch (Exception e) {
                    wrapped = "{\"type\":\"event\",\"event\":" + message + "}";
                }
                for (WsContext client : wsClients) {
                    try {
                        client.send(wrapped);
                    } catch (Exception e) {
                        wsClients.remove(client);
                    }
                }
            }
        };
        try {
            authEventJedis.subscribe(wsPubSub, vizFlowChannel);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Flow-event Redis subscriber disconnected: " + e.getMessage());
        }
    }

    private void subscribeToAuthEvents() {
        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                if (sseClients.isEmpty()) return;
                for (SseClient client : sseClients) {
                    try {
                        client.sendEvent("auth-event", message);
                    } catch (Exception e) {
                        sseClients.remove(client);
                    }
                }
            }
        };
        try {
            // Blocks until pubSub.unsubscribe() is called (or connection drops)
            authEventJedis.subscribe(pubSub, authEventChannel);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Auth event Redis subscriber disconnected: " + e.getMessage());
        }
    }

    /** Call from shutdown hook to cleanly stop the Redis subscribers. */
    public void close() {
        if (pubSub != null) {
            try { pubSub.unsubscribe(); } catch (Exception ignored) {}
        }
        if (wsPubSub != null) {
            try { wsPubSub.unsubscribe(); } catch (Exception ignored) {}
        }
    }

    // ── Query parameter helpers ──────────────────────────────────────────────

    private static int parseLimit(String raw, int defaultLimit, int maxLimit) {
        if (raw == null || raw.isBlank()) return defaultLimit;
        try {
            int v = Integer.parseInt(raw);
            if (v < 1) return 1;
            if (v > maxLimit) return maxLimit;
            return v;
        } catch (NumberFormatException e) {
            return defaultLimit;
        }
    }

    /**
     * Accepts either a relative duration ("1h", "30m", "7d") or an absolute
     * ISO-8601 instant. Returns null when unset/invalid so callers can treat
     * it as "no lower bound".
     */
    private static java.time.Instant parseSince(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            char last = raw.charAt(raw.length() - 1);
            if (last == 's' || last == 'm' || last == 'h' || last == 'd') {
                long n = Long.parseLong(raw.substring(0, raw.length() - 1));
                long seconds = switch (last) {
                    case 's' -> n;
                    case 'm' -> n * 60;
                    case 'h' -> n * 3600;
                    case 'd' -> n * 86400;
                    default  -> 0;
                };
                return java.time.Instant.now().minusSeconds(seconds);
            }
            return java.time.Instant.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    // ── DB helpers ───────────────────────────────────────────────────────────

    private List<Map<String, Object>> listFlows(UUID tenantId, String flowType,
                                                java.time.Instant since, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, flow_type, current_state, exit_state, context,
                       created_at, updated_at, completed_at, expires_at,
                       session_id, journey_id
                FROM auth_flows
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();

        if (tenantId != null) {
            // auth_flows stores tenant_id inside the context JSONB (see
            // OidcFlowData / PasskeyFlowData / InviteFlowData). The key is
            // sometimes a UUID object, sometimes a raw string — text extraction
            // handles both uniformly.
            sql.append(" AND context->>'tenant_id' = ?");
            params.add(tenantId.toString());
        }
        if (flowType != null && !flowType.isBlank()) {
            sql.append(" AND flow_type = ?");
            params.add(flowType);
        }
        if (since != null) {
            sql.append(" AND created_at >= ?");
            params.add(java.sql.Timestamp.from(since));
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        params.add(limit);

        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("flowType", rs.getString("flow_type"));
                    row.put("currentState", rs.getString("current_state"));
                    row.put("exitState", rs.getString("exit_state"));
                    String contextJson = rs.getString("context");
                    if (contextJson != null) {
                        try {
                            var node = objectMapper.readTree(contextJson);
                            var tenantNode = node.get("tenant_id");
                            if (tenantNode != null && !tenantNode.isNull()) {
                                row.put("tenantId", tenantNode.asText());
                            }
                        } catch (Exception ignore) {
                            // skip malformed context
                        }
                    }
                    java.sql.Timestamp created = rs.getTimestamp("created_at");
                    if (created != null) row.put("createdAt", created.toInstant().toString());
                    java.sql.Timestamp completed = rs.getTimestamp("completed_at");
                    if (completed != null) row.put("completedAt", completed.toInstant().toString());
                    String sessionId = rs.getString("session_id");
                    if (sessionId != null) row.put("sessionId", sessionId);
                    String journeyId = rs.getString("journey_id");
                    if (journeyId != null) row.put("journeyId", journeyId);
                    result.add(row);
                }
            }
        } catch (Exception e) {
            throw new ApiException(500, "DB_ERROR", "Failed to list flows: " + e.getMessage());
        }
        return result;
    }

    private List<Map<String, Object>> listTransitions(String flowId) {
        String sql = """
                SELECT id, from_state, to_state, trigger, context_snapshot, error_detail, created_at
                FROM auth_flow_transitions
                WHERE flow_id = ?::uuid
                ORDER BY created_at ASC, id ASC
                """;
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, flowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("fromState", rs.getString("from_state"));
                    row.put("toState", rs.getString("to_state"));
                    row.put("trigger", rs.getString("trigger"));
                    String contextJson = rs.getString("context_snapshot");
                    if (contextJson != null) {
                        row.put("contextSnapshot", objectMapper.readTree(contextJson));
                    }
                    String errorDetail = rs.getString("error_detail");
                    if (errorDetail != null) {
                        row.put("errorDetail", errorDetail);
                    }
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    result.add(row);
                }
            }
        } catch (Exception e) {
            throw new ApiException(500, "DB_ERROR", "Failed to list transitions: " + e.getMessage());
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> renderFlowDefinition(FlowDefinition<?> def) {
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("name", def.name());
        // Mermaid diagram
        flow.put("mermaid", MermaidGenerator.generate((FlowDefinition) def));
        // RenderableGraph for tramli-viz
        RenderableGraph.StateDiagram diagram = ((FlowDefinition) def).toRenderableStateDiagram();
        flow.put("stateDiagram", renderStateDiagram(diagram));
        return flow;
    }

    private Map<String, Object> renderStateDiagram(RenderableGraph.StateDiagram diagram) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("flowName", diagram.flowName());
        result.put("initialState", diagram.initialState());
        List<Map<String, String>> transitions = new ArrayList<>();
        for (RenderableGraph.StateEdge edge : diagram.transitions()) {
            Map<String, String> e = new LinkedHashMap<>();
            e.put("from", edge.from());
            e.put("to", edge.to());
            e.put("label", edge.label());
            transitions.add(e);
        }
        result.put("transitions", transitions);
        result.put("terminalStates", new ArrayList<>(diagram.terminalStates()));
        List<Map<String, Object>> subFlows = new ArrayList<>();
        for (RenderableGraph.SubFlowBlock sub : diagram.subFlows()) {
            Map<String, Object> subMap = new LinkedHashMap<>();
            subMap.put("parentState", sub.parentState());
            subMap.put("inner", renderStateDiagram(sub.inner()));
            subFlows.add(subMap);
        }
        result.put("subFlows", subFlows);
        return result;
    }
}
