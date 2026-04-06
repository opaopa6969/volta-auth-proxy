package com.volta.authproxy.flow;

import java.util.*;

/**
 * In-memory FlowStore for testing. No DB required.
 */
final class InMemoryFlowStore implements FlowStore {
    private final Map<String, FlowInstance<?>> flows = new LinkedHashMap<>();
    private final List<TransitionRecord> transitionLog = new ArrayList<>();

    record TransitionRecord(String flowId, String from, String to, String trigger) {}

    @Override
    public void create(FlowInstance<?> flow) {
        flows.put(flow.id(), flow);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S extends Enum<S> & FlowState> Optional<FlowInstance<S>> loadForUpdate(
            String flowId, FlowDefinition<S> definition) {
        FlowInstance<?> flow = flows.get(flowId);
        if (flow == null || flow.isCompleted()) return Optional.empty();
        return Optional.of((FlowInstance<S>) flow);
    }

    @Override
    public void save(FlowInstance<?> flow) {
        flows.put(flow.id(), flow);
    }

    @Override
    public void recordTransition(String flowId, FlowState from, FlowState to,
                                 String trigger, FlowContext ctx) {
        transitionLog.add(new TransitionRecord(flowId,
                from != null ? from.name() : null, to.name(), trigger));
    }

    public List<TransitionRecord> transitionLog() {
        return Collections.unmodifiableList(transitionLog);
    }

    public FlowInstance<?> getFlow(String id) {
        return flows.get(id);
    }
}
