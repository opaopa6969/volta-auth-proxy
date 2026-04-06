package com.volta.authproxy.flow;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DSL for testing flow definitions.
 *
 * Usage:
 *   FlowTestHarness.forFlow(definition)
 *       .startWith(TestRequest.class, new TestRequest("GOOGLE", "/"))
 *       .expectState(REDIRECTED)
 *       .thenResume()
 *       .expectState(CALLBACK_RECEIVED)
 *       .assertFlowCompleted("COMPLETE");
 */
public final class FlowTestHarness<S extends Enum<S> & FlowState> {
    private final FlowDefinition<S> definition;
    private final InMemoryFlowStore store;
    private final FlowEngine engine;
    private FlowInstance<S> flow;

    private FlowTestHarness(FlowDefinition<S> definition) {
        this.definition = definition;
        this.store = new InMemoryFlowStore();
        this.engine = new FlowEngine(store);
    }

    public static <S extends Enum<S> & FlowState> FlowTestHarness<S> forFlow(FlowDefinition<S> definition) {
        return new FlowTestHarness<>(definition);
    }

    public FlowTestHarness<S> startWith(Map<Class<?>, Object> initialData) {
        flow = engine.startFlow(definition, null, initialData);
        return this;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> FlowTestHarness<S> startWith(Class<T> key, T value) {
        flow = engine.startFlow(definition, null, Map.of((Class) key, value));
        return this;
    }

    public FlowTestHarness<S> start() {
        flow = engine.startFlow(definition, null, Map.of());
        return this;
    }

    public FlowTestHarness<S> expectState(S expected) {
        assertEquals(expected, flow.currentState(),
                "Expected state " + expected.name() + " but was " + flow.currentState().name());
        return this;
    }

    public FlowTestHarness<S> thenResume() {
        flow = engine.resumeAndExecute(flow.id(), definition);
        return this;
    }

    public FlowTestHarness<S> assertFlowCompleted(String expectedExitState) {
        assertTrue(flow.isCompleted(), "Flow should be completed but is in state " + flow.currentState().name());
        assertEquals(expectedExitState, flow.exitState());
        return this;
    }

    public FlowTestHarness<S> assertNotCompleted() {
        assertFalse(flow.isCompleted(), "Flow should not be completed but has exit state " + flow.exitState());
        return this;
    }

    /** Force the flow to a specific state (for testing invalid transitions). */
    public FlowTestHarness<S> forceState(S state) {
        if (flow == null) {
            start();
        }
        flow.transitionTo(state);
        return this;
    }

    /** Assert that transitioning from current state via external is rejected or doesn't exist. */
    public FlowTestHarness<S> assertNoExternalTransition() {
        assertTrue(definition.externalFrom(flow.currentState()).isEmpty(),
                "Expected no external transition from " + flow.currentState().name());
        return this;
    }

    public FlowInstance<S> instance() { return flow; }
    public InMemoryFlowStore store() { return store; }
}
