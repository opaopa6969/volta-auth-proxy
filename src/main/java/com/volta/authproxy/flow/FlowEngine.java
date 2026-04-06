package com.volta.authproxy.flow;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generic engine that drives all Flow SMs. ~120 lines of business-logic-free orchestration.
 * Handles auto-chaining, guard validation, branch dispatch, and transition logging.
 */
public final class FlowEngine {
    private static final int MAX_CHAIN_DEPTH = 10;

    private final FlowStore store;

    public FlowEngine(FlowStore store) {
        this.store = store;
    }

    /**
     * Start a new flow. Executes auto-chain from initial state.
     */
    public <S extends Enum<S> & FlowState> FlowInstance<S> startFlow(
            FlowDefinition<S> definition, String sessionId, Map<Class<?>, Object> initialData) {

        String flowId = UUID.randomUUID().toString();
        FlowContext ctx = new FlowContext(flowId);
        for (var entry : initialData.entrySet()) {
            putRaw(ctx, entry.getKey(), entry.getValue());
        }

        S initial = definition.initialState();
        Instant expiresAt = Instant.now().plus(definition.ttl());
        var flow = new FlowInstance<>(flowId, sessionId, definition, ctx, initial, expiresAt);

        store.create(flow);
        executeAutoChain(flow);
        store.save(flow);

        return flow;
    }

    /**
     * Resume a flow with an external event (HTTP request arriving).
     * Validates guard, then executes auto-chain.
     */
    public <S extends Enum<S> & FlowState> FlowInstance<S> resumeAndExecute(
            String flowId, FlowDefinition<S> definition) {
        return resumeAndExecute(flowId, definition, Map.of());
    }

    /**
     * Resume a flow with external data (e.g. HTTP callback params).
     * External data is merged into FlowContext before guard execution.
     */
    public <S extends Enum<S> & FlowState> FlowInstance<S> resumeAndExecute(
            String flowId, FlowDefinition<S> definition, Map<Class<?>, Object> externalData) {

        var flowOpt = store.loadForUpdate(flowId, definition);
        if (flowOpt.isEmpty()) {
            throw new FlowException("FLOW_NOT_FOUND", "Flow " + flowId + " not found or already completed");
        }
        var flow = flowOpt.get();

        // Merge external data into context
        for (var entry : externalData.entrySet()) {
            putRaw(flow.context(), entry.getKey(), entry.getValue());
        }

        // Check expiry
        if (Instant.now().isAfter(flow.expiresAt())) {
            flow.complete("EXPIRED");
            store.save(flow);
            return flow;
        }

        S currentState = flow.currentState();
        var externalOpt = definition.externalFrom(currentState);
        if (externalOpt.isEmpty()) {
            throw FlowException.invalidTransition(currentState, currentState);
        }

        Transition<S> transition = externalOpt.get();
        TransitionGuard guard = transition.guard();

        if (guard != null) {
            TransitionGuard.GuardOutput output = guard.validate(flow.context());

            switch (output) {
                case TransitionGuard.GuardOutput.Accepted accepted -> {
                    // Merge guard data into context
                    for (var entry : accepted.data().entrySet()) {
                        putRaw(flow.context(), entry.getKey(), entry.getValue());
                    }
                    S from = flow.currentState();
                    flow.transitionTo(transition.to());
                    store.recordTransition(flow.id(), from, transition.to(),
                            guard.name(), flow.context());

                    // Execute processor if present
                    if (transition.processor() != null) {
                        transition.processor().process(flow.context());
                    }
                }
                case TransitionGuard.GuardOutput.Rejected rejected -> {
                    flow.incrementGuardFailure();
                    if (flow.guardFailureCount() >= definition.maxGuardRetries()) {
                        handleError(flow, currentState, "Guard rejected " + definition.maxGuardRetries() + " times: " + rejected.reason());
                    }
                    store.save(flow);
                    return flow;
                }
                case TransitionGuard.GuardOutput.Expired expired -> {
                    flow.complete("EXPIRED");
                    store.save(flow);
                    return flow;
                }
            }
        } else {
            S from = flow.currentState();
            flow.transitionTo(transition.to());
            store.recordTransition(flow.id(), from, transition.to(), "external", flow.context());
        }

        executeAutoChain(flow);
        store.save(flow);
        return flow;
    }

    /**
     * Execute all reachable Auto and Branch transitions from current state.
     * Stops at External transition or terminal state. Max depth = 10.
     */
    private <S extends Enum<S> & FlowState> void executeAutoChain(FlowInstance<S> flow) {
        int depth = 0;
        while (depth < MAX_CHAIN_DEPTH) {
            S current = flow.currentState();
            if (current.isTerminal()) {
                flow.complete(current.name());
                break;
            }

            List<Transition<S>> transitions = flow.definition().transitionsFrom(current);
            Transition<S> autoOrBranch = transitions.stream()
                    .filter(t -> t.isAuto() || t.isBranch())
                    .findFirst()
                    .orElse(null);

            if (autoOrBranch == null) break; // Waiting for external

            if (autoOrBranch.isAuto()) {
                if (autoOrBranch.processor() != null) {
                    autoOrBranch.processor().process(flow.context());
                }
                S from = flow.currentState();
                flow.transitionTo(autoOrBranch.to());
                store.recordTransition(flow.id(), from, autoOrBranch.to(),
                        autoOrBranch.processor() != null ? autoOrBranch.processor().name() : "auto",
                        flow.context());
            } else {
                // Branch
                BranchProcessor branch = autoOrBranch.branch();
                String label = branch.decide(flow.context());
                S target = autoOrBranch.branchTargets().get(label);
                if (target == null) {
                    throw new FlowException("UNKNOWN_BRANCH",
                            "Branch '" + branch.name() + "' returned unknown label: " + label);
                }
                // Find the specific transition for this branch target to get its processor
                Transition<S> specificTransition = transitions.stream()
                        .filter(t -> t.isBranch() && t.to() == target)
                        .findFirst()
                        .orElse(autoOrBranch);
                if (specificTransition.processor() != null) {
                    specificTransition.processor().process(flow.context());
                }
                S from = flow.currentState();
                flow.transitionTo(target);
                store.recordTransition(flow.id(), from, target,
                        branch.name() + ":" + label, flow.context());
            }
            depth++;
        }
        if (depth >= MAX_CHAIN_DEPTH) {
            throw FlowException.maxChainDepth();
        }
    }

    private <S extends Enum<S> & FlowState> void handleError(FlowInstance<S> flow, S fromState, String detail) {
        S errorTarget = flow.definition().errorTransitions().get(fromState);
        if (errorTarget != null) {
            S from = flow.currentState();
            flow.transitionTo(errorTarget);
            store.recordTransition(flow.id(), from, errorTarget, "error", flow.context());
            if (errorTarget.isTerminal()) {
                flow.complete(errorTarget.name());
            }
        } else {
            flow.complete("TERMINAL_ERROR");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void putRaw(FlowContext ctx, Class key, Object value) {
        ctx.put(key, value);
    }
}
