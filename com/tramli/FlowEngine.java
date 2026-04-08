package com.tramli;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generic engine that drives all flow state machines.
 *
 * <h3>Exceptions</h3>
 * <ul>
 *   <li>{@code FLOW_NOT_FOUND} — {@link #resumeAndExecute} with unknown or completed flowId</li>
 *   <li>{@code INVALID_TRANSITION} — {@link #resumeAndExecute} when no external transition exists</li>
 *   <li>{@code MAX_CHAIN_DEPTH} — auto-chain exceeded 10 steps</li>
 *   <li>{@code EXPIRED} — flow TTL exceeded at {@link #resumeAndExecute} entry</li>
 * </ul>
 * Processor and branch exceptions are caught and routed to error transitions.
 * Context is restored to its pre-execution state before error routing.
 */
public final class FlowEngine {
    /** Default max auto-chain depth. Override via constructor. */
    public static final int DEFAULT_MAX_CHAIN_DEPTH = 10;

    private final FlowStore store;
    private final boolean strictMode;
    private final int maxChainDepth;
    private java.util.function.Consumer<LogEntry.Transition> transitionLogger;
    private java.util.function.Consumer<LogEntry.State> stateLogger;
    private java.util.function.Consumer<LogEntry.Error> errorLogger;
    private java.util.function.Consumer<LogEntry.GuardResult> guardLogger;

    public FlowEngine(FlowStore store) {
        this(store, false, DEFAULT_MAX_CHAIN_DEPTH);
    }

    public FlowEngine(FlowStore store, boolean strictMode) {
        this(store, strictMode, DEFAULT_MAX_CHAIN_DEPTH);
    }

    public FlowEngine(FlowStore store, boolean strictMode, int maxChainDepth) {
        this.store = store;
        this.strictMode = strictMode;
        this.maxChainDepth = maxChainDepth;
    }

    /** Set transition logger. Called on each state transition. */
    public void setTransitionLogger(java.util.function.Consumer<LogEntry.Transition> logger) {
        this.transitionLogger = logger;
    }

    /** Set state logger. Called on each context.put(). Opt-in for debugging. */
    public void setStateLogger(java.util.function.Consumer<LogEntry.State> logger) {
        this.stateLogger = logger;
    }

    /** Set error logger. Called when a processor throws or error transition fires. */
    public void setErrorLogger(java.util.function.Consumer<LogEntry.Error> logger) {
        this.errorLogger = logger;
    }

    /** Set guard logger. Called when a guard returns Accepted/Rejected/Expired. */
    public void setGuardLogger(java.util.function.Consumer<LogEntry.GuardResult> logger) {
        this.guardLogger = logger;
    }

    /** Remove all loggers. */
    public void removeAllLoggers() {
        this.transitionLogger = null;
        this.stateLogger = null;
        this.errorLogger = null;
        this.guardLogger = null;
    }

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

    public <S extends Enum<S> & FlowState> FlowInstance<S> resumeAndExecute(
            String flowId, FlowDefinition<S> definition) {
        return resumeAndExecute(flowId, definition, Map.of());
    }

    public <S extends Enum<S> & FlowState> FlowInstance<S> resumeAndExecute(
            String flowId, FlowDefinition<S> definition, Map<Class<?>, Object> externalData) {

        var flowOpt = store.loadForUpdate(flowId, definition);
        if (flowOpt.isEmpty()) {
            throw new FlowException("FLOW_NOT_FOUND", "Flow " + flowId + " not found or already completed");
        }
        var flow = flowOpt.get();

        for (var entry : externalData.entrySet()) {
            putRaw(flow.context(), entry.getKey(), entry.getValue());
        }

        if (Instant.now().isAfter(flow.expiresAt())) {
            flow.complete("EXPIRED");
            store.save(flow);
            return flow;
        }

        // If actively in a sub-flow, delegate resume to it
        if (flow.activeSubFlow() != null) {
            return resumeSubFlow(flow, definition, externalData);
        }

        S currentState = flow.currentState();
        var externalOpt = definition.externalFrom(currentState);
        if (externalOpt.isEmpty()) {
            throw FlowException.invalidTransition(currentState, currentState);
        }

        Transition<S> transition = externalOpt.get();

        // Per-state timeout check
        if (transition.timeout() != null && flow.stateEnteredAt() != null) {
            Instant deadline = flow.stateEnteredAt().plus(transition.timeout());
            if (Instant.now().isAfter(deadline)) {
                flow.complete("EXPIRED");
                store.save(flow);
                return flow;
            }
        }

        TransitionGuard guard = transition.guard();

        if (guard != null) {
            TransitionGuard.GuardOutput output = guard.validate(flow.context());
            if (guardLogger != null) {
                String result = switch (output) {
                    case TransitionGuard.GuardOutput.Accepted a -> "accepted";
                    case TransitionGuard.GuardOutput.Rejected r -> "rejected";
                    case TransitionGuard.GuardOutput.Expired e -> "expired";
                };
                String reason = output instanceof TransitionGuard.GuardOutput.Rejected r ? r.reason() : null;
                guardLogger.accept(new LogEntry.GuardResult(flow.id(), currentState.name(), guard.name(), result, reason));
            }
            switch (output) {
                case TransitionGuard.GuardOutput.Accepted accepted -> {
                    Map<Class<?>, Object> backup = flow.context().snapshot();
                    for (var entry : accepted.data().entrySet()) {
                        putRaw(flow.context(), entry.getKey(), entry.getValue());
                    }
                    try {
                        if (transition.processor() != null) {
                            transition.processor().process(flow.context());
                        }
                        S from = flow.currentState();
                        flow.transitionTo(transition.to());
                        store.recordTransition(flow.id(), from, transition.to(), guard.name(), flow.context());
                    } catch (Exception e) {
                        flow.context().restoreFrom(backup);
                        handleError(flow, currentState, e);
                        store.save(flow);
                        return flow;
                    }
                }
                case TransitionGuard.GuardOutput.Rejected rejected -> {
                    flow.incrementGuardFailure();
                    if (flow.guardFailureCount() >= definition.maxGuardRetries()) {
                        handleError(flow, currentState);
                    }
                    store.save(flow);
                    return flow;
                }
                case TransitionGuard.GuardOutput.Expired ignored -> {
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

    private <S extends Enum<S> & FlowState> void executeAutoChain(FlowInstance<S> flow) {
        int depth = 0;
        while (depth < maxChainDepth) {
            S current = flow.currentState();
            if (current.isTerminal()) { flow.complete(current.name()); break; }

            int advanced = dispatchStep(flow, current);
            if (advanced < 0) return; // error handled
            if (advanced == 0) break; // no transition (external or end)
            depth += advanced;
        }
        if (depth >= maxChainDepth) throw FlowException.maxChainDepth();
    }

    /** Dispatch one auto-chain step. Returns steps advanced (1), 0 to stop, -1 on error. */
    private <S extends Enum<S> & FlowState> int dispatchStep(FlowInstance<S> flow, S current) {
        List<Transition<S>> transitions = flow.definition().transitionsFrom(current);

        // 1. SubFlow
        Transition<S> subFlowT = transitions.stream().filter(Transition::isSubFlow).findFirst().orElse(null);
        if (subFlowT != null) {
            int advanced = executeSubFlow(flow, subFlowT, 0);
            return advanced == 0 ? 0 : advanced;
        }

        // 2. Auto / Branch
        Transition<S> t = transitions.stream().filter(tr -> tr.isAuto() || tr.isBranch()).findFirst().orElse(null);
        if (t == null) return 0;

        Map<Class<?>, Object> backup = flow.context().snapshot();
        try {
            if (t.isAuto()) {
                return dispatchAuto(flow, t);
            } else {
                return dispatchBranch(flow, t, transitions);
            }
        } catch (Exception e) {
            flow.context().restoreFrom(backup);
            handleError(flow, flow.currentState(), e);
            return -1;
        }
    }

    private <S extends Enum<S> & FlowState> int dispatchAuto(FlowInstance<S> flow, Transition<S> t) {
        if (t.processor() != null) {
            t.processor().process(flow.context());
            verifyProduces(t.processor(), flow.context());
        }
        S from = flow.currentState();
        flow.transitionTo(t.to());
        store.recordTransition(flow.id(), from, t.to(),
                t.processor() != null ? t.processor().name() : "auto", flow.context());
        logTransition(flow.id(), from, t.to(), t.processor() != null ? t.processor().name() : "auto");
        return 1;
    }

    private <S extends Enum<S> & FlowState> int dispatchBranch(
            FlowInstance<S> flow, Transition<S> t, List<Transition<S>> transitions) {
        BranchProcessor branch = t.branch();
        String label = branch.decide(flow.context());
        S target = t.branchTargets().get(label);
        if (target == null) {
            throw new FlowException("UNKNOWN_BRANCH",
                    "Branch '" + branch.name() + "' returned unknown label: " + label);
        }
        Transition<S> specific = transitions.stream()
                .filter(tr -> tr.isBranch() && tr.to() == target).findFirst().orElse(t);
        if (specific.processor() != null) specific.processor().process(flow.context());
        S from = flow.currentState();
        flow.transitionTo(target);
        store.recordTransition(flow.id(), from, target, branch.name() + ":" + label, flow.context());
        logTransition(flow.id(), from, target, branch.name() + ":" + label);
        return 1;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <S extends Enum<S> & FlowState> FlowInstance<S> resumeSubFlow(
            FlowInstance<S> parentFlow, FlowDefinition<S> parentDef, Map<Class<?>, Object> externalData) {

        FlowInstance subFlow = parentFlow.activeSubFlow();
        FlowDefinition subDef = subFlow.definition();

        for (var entry : externalData.entrySet()) {
            putRaw(parentFlow.context(), entry.getKey(), entry.getValue());
        }

        Enum subCurrent = (Enum) subFlow.currentState();
        var externalOpt = subDef.externalFrom((Enum & FlowState) subCurrent);
        if (externalOpt.isEmpty()) {
            throw new FlowException("INVALID_TRANSITION",
                    "No external transition from sub-flow state " + subCurrent.name());
        }

        Transition transition = (Transition) externalOpt.get();
        TransitionGuard guard = transition.guard();
        FlowState subTo = (FlowState) transition.to();

        if (guard != null) {
            var output = guard.validate(parentFlow.context());
            if (output instanceof TransitionGuard.GuardOutput.Accepted accepted) {
                for (var entry : accepted.data().entrySet()) {
                    putRaw(parentFlow.context(), entry.getKey(), entry.getValue());
                }
                subFlow.transitionTo(transition.to());
                store.recordTransition(parentFlow.id(), (FlowState) subCurrent, subTo,
                        guard.name(), parentFlow.context());
            } else if (output instanceof TransitionGuard.GuardOutput.Rejected) {
                subFlow.incrementGuardFailure();
                if (subFlow.guardFailureCount() >= subDef.maxGuardRetries()) {
                    subFlow.complete("ERROR");
                }
                store.save(parentFlow);
                return parentFlow;
            } else {
                parentFlow.complete("EXPIRED");
                store.save(parentFlow);
                return parentFlow;
            }
        } else {
            subFlow.transitionTo(transition.to());
        }

        executeAutoChain(subFlow);

        if (subFlow.isCompleted()) {
            parentFlow.setActiveSubFlow(null);
            Transition subFlowT = parentDef.transitionsFrom(parentFlow.currentState()).stream()
                    .filter(Transition::isSubFlow).findFirst().orElse(null);
            if (subFlowT != null) {
                S target = (S) subFlowT.exitMappings().get(subFlow.exitState());
                if (target != null) {
                    S from = parentFlow.currentState();
                    parentFlow.transitionTo(target);
                    store.recordTransition(parentFlow.id(), from, target,
                            "subFlow:" + subDef.name() + "/" + subFlow.exitState(), parentFlow.context());
                    executeAutoChain(parentFlow);
                }
            }
        }

        store.save(parentFlow);
        return parentFlow;
    }

    @SuppressWarnings("unchecked")
    private <S extends Enum<S> & FlowState> int executeSubFlow(
            FlowInstance<S> parentFlow, Transition<S> subFlowTransition, int currentDepth) {

        FlowDefinition<?> subDef = subFlowTransition.subFlowDefinition();
        Map<String, S> exitMappings = subFlowTransition.exitMappings();

        // Create sub-flow instance sharing the parent's context
        var subInitial = subDef.initialState();
        var subFlow = new FlowInstance(parentFlow.id(), parentFlow.sessionId(),
                subDef, parentFlow.context(), subInitial, parentFlow.expiresAt());
        parentFlow.setActiveSubFlow(subFlow);

        // Execute sub-flow auto-chain (recursive)
        executeAutoChain((FlowInstance) subFlow);

        // If sub-flow completed (reached terminal), map exit to parent state
        if (subFlow.isCompleted()) {
            parentFlow.setActiveSubFlow(null);
            S parentTarget = exitMappings.get(subFlow.exitState());
            if (parentTarget != null) {
                S from = parentFlow.currentState();
                parentFlow.transitionTo(parentTarget);
                store.recordTransition(parentFlow.id(), from, parentTarget,
                        "subFlow:" + subDef.name() + "/" + subFlow.exitState(), parentFlow.context());
                return 1;
            }
            // Error bubbling: if no exit mapping found (e.g. sub-flow error),
            // fall back to parent's error transitions
            handleError(parentFlow, parentFlow.currentState());
            return 1;
        }
        // Sub-flow stopped at external — parent also stops
        return 0;
    }

    private <S extends Enum<S> & FlowState> void handleError(FlowInstance<S> flow, S fromState) {
        handleError(flow, fromState, null);
    }

    private <S extends Enum<S> & FlowState> void handleError(FlowInstance<S> flow, S fromState, Exception cause) {
        if (cause != null) {
            flow.setLastError(cause.getClass().getSimpleName() + ": " + cause.getMessage());
            if (cause instanceof FlowException fe) {
                var available = flow.context().snapshot().keySet();
                fe.withContextSnapshot(available, java.util.Set.of());
            }
        }

        // 1. Try exception-typed routes first (onStepError)
        if (cause != null) {
            var routes = flow.definition().exceptionRoutes().get(fromState);
            if (routes != null) {
                for (var route : routes) {
                    if (route.exceptionType().isInstance(cause)) {
                        S from = flow.currentState();
                        flow.transitionTo(route.target());
                        store.recordTransition(flow.id(), from, route.target(),
                                "error:" + cause.getClass().getSimpleName(), flow.context());
                        logTransition(flow.id(), from, route.target(), "error:" + cause.getClass().getSimpleName());
                        logError(flow.id(), fromState, route.target(), "error:" + cause.getClass().getSimpleName(), cause);
                        if (route.target().isTerminal()) flow.complete(route.target().name());
                        return;
                    }
                }
            }
        }

        // 2. Fall back to state-based error transition (onError)
        S errorTarget = flow.definition().errorTransitions().get(fromState);
        if (errorTarget != null) {
            S from = flow.currentState();
            flow.transitionTo(errorTarget);
            store.recordTransition(flow.id(), from, errorTarget, "error", flow.context());
            logTransition(flow.id(), from, errorTarget, "error");
            logError(flow.id(), fromState, errorTarget, "error", cause);
            if (errorTarget.isTerminal()) flow.complete(errorTarget.name());
        } else {
            flow.complete("TERMINAL_ERROR");
        }
    }

    private void verifyProduces(StateProcessor processor, FlowContext ctx) {
        if (!strictMode || processor == null) return;
        for (Class<?> prod : processor.produces()) {
            if (!ctx.has(prod)) {
                throw new FlowException("PRODUCES_VIOLATION",
                        "Processor '" + processor.name() + "' declares produces " +
                                prod.getSimpleName() + " but did not put it in context (strictMode)");
            }
        }
    }

    private void logTransition(String flowId, FlowState from, FlowState to, String trigger) {
        if (transitionLogger != null) {
            transitionLogger.accept(new LogEntry.Transition(flowId,
                    from != null ? from.name() : null, to.name(), trigger));
        }
    }

    private void logError(String flowId, FlowState from, FlowState to, String trigger, Throwable cause) {
        if (errorLogger != null) {
            errorLogger.accept(new LogEntry.Error(flowId,
                    from != null ? from.name() : null, to != null ? to.name() : null, trigger, cause));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void putRaw(FlowContext ctx, Class key, Object value) {
        ctx.put(key, value);
    }
}
