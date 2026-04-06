package com.volta.authproxy.flow;

public enum PasskeyFlowState implements FlowState {
    INIT(false, true),
    CHALLENGE_ISSUED(false, false),
    ASSERTION_RECEIVED(false, false),
    USER_RESOLVED(false, false),
    COMPLETE(true, false),
    TERMINAL_ERROR(true, false);

    private final boolean terminal;
    private final boolean initial;

    PasskeyFlowState(boolean terminal, boolean initial) {
        this.terminal = terminal;
        this.initial = initial;
    }

    @Override public boolean isTerminal() { return terminal; }
    @Override public boolean isInitial() { return initial; }
}
