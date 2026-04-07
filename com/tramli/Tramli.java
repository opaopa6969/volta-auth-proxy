package com.tramli;

/**
 * Entry point for the tramli flow engine.
 *
 * <pre>
 * var flow = Tramli.define("order", OrderState.class)
 *     .from(CREATED).auto(PAYMENT_PENDING, paymentInit)
 *     .from(PAYMENT_PENDING).external(CONFIRMED, paymentGuard)
 *     .build();
 * </pre>
 */
public final class Tramli {

    private Tramli() {}

    /**
     * Start defining a new flow.
     *
     * @param name       flow name (used in logging, Mermaid, etc.)
     * @param stateClass the enum class implementing {@link FlowState}
     */
    public static <S extends Enum<S> & FlowState> FlowDefinition.Builder<S> define(
            String name, Class<S> stateClass) {
        return FlowDefinition.builder(name, stateClass);
    }

    /**
     * Create a new FlowEngine with the given store.
     */
    public static FlowEngine engine(FlowStore store) {
        return new FlowEngine(store);
    }
}
