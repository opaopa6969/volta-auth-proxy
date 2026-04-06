package com.volta.authproxy.flow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * PostgreSQL implementation of FlowStore.
 * Uses JSONB for context, SELECT FOR UPDATE for locking, optimistic locking via version column.
 */
public final class SqlFlowStore implements FlowStore {
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final FlowDataRegistry registry;

    public SqlFlowStore(DataSource dataSource, ObjectMapper objectMapper, FlowDataRegistry registry) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    @Override
    public void create(FlowInstance<?> flow) {
        String sql = """
                INSERT INTO auth_flows (id, session_id, flow_type, current_state, context,
                    guard_failure_count, version, expires_at)
                VALUES (?::uuid, ?::uuid, ?, ?, ?::jsonb, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, flow.id());
            ps.setString(2, flow.sessionId());
            ps.setString(3, flow.definition().name());
            ps.setString(4, flow.currentState().name());
            ps.setString(5, serializeContext(flow.context()));
            ps.setInt(6, flow.guardFailureCount());
            ps.setInt(7, flow.version());
            ps.setTimestamp(8, Timestamp.from(flow.expiresAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new FlowException("DB_ERROR", "Failed to create flow: " + e.getMessage(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S extends Enum<S> & FlowState> Optional<FlowInstance<S>> loadForUpdate(
            String flowId, FlowDefinition<S> definition) {
        String sql = """
                SET LOCAL lock_timeout = '5s';
                SELECT id, session_id, current_state, context, guard_failure_count, version,
                    created_at, expires_at, exit_state
                FROM auth_flows
                WHERE id = ?::uuid AND exit_state IS NULL
                FOR UPDATE
                """;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, flowId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.commit();
                        return Optional.empty();
                    }
                    String stateName = rs.getString("current_state");
                    S state = Enum.valueOf(definition.stateClass(), stateName);

                    FlowContext ctx = new FlowContext(flowId);
                    String contextJson = rs.getString("context");
                    if (contextJson != null && !contextJson.equals("{}")) {
                        Map<String, Object> data = objectMapper.readValue(contextJson,
                                new TypeReference<>() {});
                        registry.deserializeInto(ctx, data);
                    }

                    Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
                    String sessionId = rs.getString("session_id");

                    var flow = new FlowInstance<>(flowId, sessionId, definition, ctx, state, expiresAt);
                    flow.setVersion(rs.getInt("version"));
                    // Restore guard failure count
                    int guardFailures = rs.getInt("guard_failure_count");
                    for (int i = 0; i < guardFailures; i++) {
                        flow.incrementGuardFailure();
                    }

                    conn.commit();
                    return Optional.of(flow);
                }
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (FlowException e) {
            throw e;
        } catch (Exception e) {
            throw new FlowException("DB_ERROR", "Failed to load flow: " + e.getMessage(), e);
        }
    }

    @Override
    public void save(FlowInstance<?> flow) {
        String sql = """
                UPDATE auth_flows
                SET current_state = ?, context = ?::jsonb, guard_failure_count = ?,
                    version = version + 1, updated_at = now(),
                    completed_at = CASE WHEN ? THEN now() ELSE NULL END,
                    exit_state = ?
                WHERE id = ?::uuid AND version = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, flow.currentState().name());
            ps.setString(2, serializeContext(flow.context()));
            ps.setInt(3, flow.guardFailureCount());
            ps.setBoolean(4, flow.isCompleted());
            ps.setString(5, flow.exitState());
            ps.setString(6, flow.id());
            ps.setInt(7, flow.version());
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new FlowException("CONCURRENT_MODIFICATION",
                        "Flow " + flow.id() + " was modified concurrently (version " + flow.version() + ")");
            }
            flow.setVersion(flow.version() + 1);
        } catch (FlowException e) {
            throw e;
        } catch (SQLException e) {
            throw new FlowException("DB_ERROR", "Failed to save flow: " + e.getMessage(), e);
        }
    }

    @Override
    public void recordTransition(String flowId, FlowState from, FlowState to,
                                 String trigger, FlowContext ctx) {
        String sql = """
                INSERT INTO auth_flow_transitions (flow_id, from_state, to_state, trigger, context_snapshot)
                VALUES (?::uuid, ?, ?, ?, ?::jsonb)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, flowId);
            ps.setString(2, from != null ? from.name() : null);
            ps.setString(3, to.name());
            ps.setString(4, trigger);
            // TODO: Phase N — redact @Sensitive fields in snapshot
            ps.setString(5, serializeContext(ctx));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new FlowException("DB_ERROR", "Failed to record transition: " + e.getMessage(), e);
        }
    }

    private String serializeContext(FlowContext ctx) {
        try {
            return objectMapper.writeValueAsString(registry.serialize(ctx));
        } catch (Exception e) {
            throw new FlowException("SERIALIZE_ERROR", "Failed to serialize flow context", e);
        }
    }
}
