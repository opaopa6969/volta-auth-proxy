package com.volta.authproxy.flow;

import java.util.Map;
import java.util.Set;

/**
 * Guards an External transition. Pure function — must not mutate FlowContext.
 * Accepted data is merged into context by the engine.
 */
public interface TransitionGuard {
    String name();
    Set<Class<?>> requires();
    Set<Class<?>> produces();
    int maxRetries();
    GuardOutput validate(FlowContext ctx);

    sealed interface GuardOutput permits GuardOutput.Accepted, GuardOutput.Rejected, GuardOutput.Expired {
        record Accepted(Map<Class<?>, Object> data) implements GuardOutput {
            public Accepted() { this(Map.of()); }
        }
        record Rejected(String reason) implements GuardOutput {}
        record Expired() implements GuardOutput {}
    }
}
