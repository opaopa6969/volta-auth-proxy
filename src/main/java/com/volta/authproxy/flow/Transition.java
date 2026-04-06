package com.volta.authproxy.flow;

import java.util.Map;

/**
 * A single transition in a flow definition.
 */
public record Transition<S extends Enum<S> & FlowState>(
        S from,
        S to,
        TransitionType type,
        StateProcessor processor,
        TransitionGuard guard,
        BranchProcessor branch,
        Map<String, S> branchTargets
) {
    public boolean isAuto() { return type == TransitionType.AUTO; }
    public boolean isExternal() { return type == TransitionType.EXTERNAL; }
    public boolean isBranch() { return type == TransitionType.BRANCH; }
}
