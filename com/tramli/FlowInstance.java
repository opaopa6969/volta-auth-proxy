package com.tramli;

import java.time.Instant;

/**
 * Runtime state of a flow execution.
 */
public final class FlowInstance<S extends Enum<S> & FlowState> {
    private final String id;
    private final String sessionId;
    private final FlowDefinition<S> definition;
    private final FlowContext context;
    private S currentState;
    private int guardFailureCount;
    private int version;
    private final Instant createdAt;
    private final Instant expiresAt;
    private String exitState;
    private FlowInstance<?> activeSubFlow;
    private String lastError;

    public FlowInstance(String id, String sessionId, FlowDefinition<S> definition,
                        FlowContext context, S currentState, Instant expiresAt) {
        this(id, sessionId, definition, context, currentState, Instant.now(), expiresAt, 0, 0, null);
    }

    FlowInstance(String id, String sessionId, FlowDefinition<S> definition,
                 FlowContext context, S currentState, Instant createdAt,
                 Instant expiresAt, int guardFailureCount, int version, String exitState) {
        this.id = id;
        this.sessionId = sessionId;
        this.definition = definition;
        this.context = context;
        this.currentState = currentState;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.guardFailureCount = guardFailureCount;
        this.version = version;
        this.exitState = exitState;
    }

    /**
     * Restore a FlowInstance from persisted state.
     * Used by FlowStore implementations to reconstruct instances loaded from storage.
     *
     * @param createdAt the original creation timestamp (not Instant.now())
     * @param exitState null if the flow is still active
     */
    public static <S extends Enum<S> & FlowState> FlowInstance<S> restore(
            String id, String sessionId, FlowDefinition<S> definition,
            FlowContext context, S currentState, Instant createdAt,
            Instant expiresAt, int guardFailureCount, int version,
            String exitState) {
        return new FlowInstance<>(id, sessionId, definition, context,
                currentState, createdAt, expiresAt, guardFailureCount, version, exitState);
    }

    public String id() { return id; }
    public String sessionId() { return sessionId; }
    public FlowDefinition<S> definition() { return definition; }
    public FlowContext context() { return context; }
    public S currentState() { return currentState; }
    public int guardFailureCount() { return guardFailureCount; }
    public int version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }
    public String exitState() { return exitState; }
    public boolean isCompleted() { return exitState != null; }

    /** Active sub-flow instance, or null if not in a sub-flow. */
    public FlowInstance<?> activeSubFlow() { return activeSubFlow; }

    /** Last error message (set when a processor throws and error transition fires). */
    public String lastError() { return lastError; }

    /** State path from root to deepest active sub-flow. E.g. ["PAYMENT", "CONFIRM"]. */
    public java.util.List<String> statePath() {
        var path = new java.util.ArrayList<String>();
        path.add(currentState.name());
        if (activeSubFlow != null) {
            path.addAll(activeSubFlow.statePath());
        }
        return path;
    }

    /** State path as a slash-separated string. E.g. "PAYMENT/CONFIRM". */
    public String statePathString() {
        return String.join("/", statePath());
    }

    /**
     * Data types currently available in context (based on data-flow graph for current state).
     */
    public java.util.Set<Class<?>> availableData() {
        return definition.dataFlowGraph() != null
                ? definition.dataFlowGraph().availableAt(currentState)
                : java.util.Set.of();
    }

    /**
     * Data types that the next transition requires but are not yet in context.
     */
    public java.util.Set<Class<?>> missingFor() {
        if (definition.dataFlowGraph() == null) return java.util.Set.of();
        var available = definition.dataFlowGraph().availableAt(currentState);
        var missing = new java.util.LinkedHashSet<Class<?>>();
        for (var t : definition.transitionsFrom(currentState)) {
            if (t.guard() != null) for (var r : t.guard().requires()) { if (!context.has(r)) missing.add(r); }
            if (t.processor() != null) for (var r : t.processor().requires()) { if (!context.has(r)) missing.add(r); }
        }
        return missing;
    }

    /**
     * Types required by the next external transition (including in active sub-flows).
     * Empty if not waiting at an external transition.
     */
    public java.util.Set<Class<?>> waitingFor() {
        if (activeSubFlow != null) return activeSubFlow.waitingFor();
        var ext = definition.externalFrom(currentState);
        if (ext.isEmpty()) return java.util.Set.of();
        var guard = ext.get().guard();
        return guard != null ? guard.requires() : java.util.Set.of();
    }

    /**
     * Return a copy with the given version. For FlowStore implementations
     * that need to update the version after save (e.g., optimistic locking).
     */
    public FlowInstance<S> withVersion(int newVersion) {
        var copy = new FlowInstance<>(id, sessionId, definition, context,
                currentState, createdAt, expiresAt, guardFailureCount, newVersion, exitState);
        copy.activeSubFlow = this.activeSubFlow;
        return copy;
    }

    void transitionTo(S newState) { this.currentState = newState; }
    void incrementGuardFailure() { this.guardFailureCount++; }
    void complete(String exitState) { this.exitState = exitState; }
    void setVersion(int version) { this.version = version; }
    void setActiveSubFlow(FlowInstance<?> sub) { this.activeSubFlow = sub; }
    void setLastError(String error) { this.lastError = error; }
}
