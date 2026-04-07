package org.unlaxer.infra.volta.flow;
import com.tramli.FlowState;

public enum OidcFlowState implements FlowState {
    INIT(false, true),
    REDIRECTED(false, false),
    CALLBACK_RECEIVED(false, false),
    TOKEN_EXCHANGED(false, false),
    USER_RESOLVED(false, false),
    RISK_CHECKED(false, false),
    COMPLETE(true, false),
    COMPLETE_MFA_PENDING(true, false),
    BLOCKED(true, false),
    TERMINAL_ERROR(true, false),
    RETRIABLE_ERROR(false, false);

    private final boolean terminal;
    private final boolean initial;

    OidcFlowState(boolean terminal, boolean initial) {
        this.terminal = terminal;
        this.initial = initial;
    }

    @Override public boolean isTerminal() { return terminal; }
    @Override public boolean isInitial() { return initial; }
}
