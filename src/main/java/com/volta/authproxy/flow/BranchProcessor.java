package com.volta.authproxy.flow;

import java.util.Set;

/**
 * Decides which branch to take based on FlowContext state.
 * Returns a branch label that maps to a target state in FlowDefinition.
 */
public interface BranchProcessor {
    String name();
    Set<Class<?>> requires();
    String decide(FlowContext ctx);
}
