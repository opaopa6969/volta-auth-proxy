package org.unlaxer.infra.volta.viz;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.unlaxer.infra.volta.*;
import org.unlaxer.tramli.FlowDefinition;
import org.unlaxer.tramli.FlowState;
import org.unlaxer.tramli.MermaidGenerator;
import org.unlaxer.tramli.RenderableGraph;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

/**
 * Visualization endpoints for tramli-viz integration.
 * <ul>
 *   <li>GET /api/v1/admin/flows/{flowId}/transitions — replay API (ADMIN role required)</li>
 *   <li>GET /viz/flows — static graph endpoint (public, for embedding)</li>
 * </ul>
 */
public final class VizRouter {

    private final DataSource dataSource;
    private final AuthService authService;
    private final PolicyEngine policy;
    private final ObjectMapper objectMapper;
    private final List<FlowDefinition<?>> flowDefinitions;

    public VizRouter(DataSource dataSource, AuthService authService, PolicyEngine policy,
                     ObjectMapper objectMapper, List<FlowDefinition<?>> flowDefinitions) {
        this.dataSource = dataSource;
        this.authService = authService;
        this.policy = policy;
        this.objectMapper = objectMapper;
        this.flowDefinitions = flowDefinitions;
    }

    public void register(Javalin app) {
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
