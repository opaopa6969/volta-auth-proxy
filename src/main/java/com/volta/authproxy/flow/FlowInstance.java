package com.volta.authproxy.flow;

import java.time.Instant;
import java.util.UUID;

/**
 * Runtime state of a flow execution. Corresponds to one row in auth_flows.
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

    public FlowInstance(String id, String sessionId, FlowDefinition<S> definition,
                        FlowContext context, S currentState, Instant expiresAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.definition = definition;
        this.context = context;
        this.currentState = currentState;
        this.guardFailureCount = 0;
        this.version = 0;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
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

    void transitionTo(S newState) {
        this.currentState = newState;
    }

    void incrementGuardFailure() {
        this.guardFailureCount++;
    }

    void complete(String exitState) {
        this.exitState = exitState;
    }

    void setVersion(int version) {
        this.version = version;
    }
}
