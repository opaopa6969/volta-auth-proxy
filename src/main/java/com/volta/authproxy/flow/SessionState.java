package com.volta.authproxy.flow;

public enum SessionState implements FlowState {
    AUTHENTICATING(false, true),
    AUTHENTICATED_MFA_PENDING(false, false),
    FULLY_AUTHENTICATED(false, false),
    EXPIRED(true, false),
    REVOKED(true, false);

    private final boolean terminal;
    private final boolean initial;

    SessionState(boolean terminal, boolean initial) {
        this.terminal = terminal;
        this.initial = initial;
    }

    @Override public boolean isTerminal() { return terminal; }
    @Override public boolean isInitial() { return initial; }
}
