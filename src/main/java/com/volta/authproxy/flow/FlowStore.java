package com.volta.authproxy.flow;

import java.util.Optional;

/**
 * Persistence interface for flow instances.
 * Implementations use JSONB for context, SELECT FOR UPDATE for locking.
 */
public interface FlowStore {
    /** Save a new flow instance. */
    void create(FlowInstance<?> flow);

    /**
     * Load and lock a flow instance (SELECT ... FOR UPDATE).
     * Returns empty if flow not found or already completed.
     */
    <S extends Enum<S> & FlowState> Optional<FlowInstance<S>> loadForUpdate(
            String flowId, FlowDefinition<S> definition);

    /**
     * Save updated state. Uses optimistic locking (version check).
     * @throws FlowException if version mismatch (concurrent modification)
     */
    void save(FlowInstance<?> flow);

    /** Record a transition in auth_flow_transitions. */
    void recordTransition(String flowId, FlowState from, FlowState to,
                          String trigger, FlowContext ctx);
}
