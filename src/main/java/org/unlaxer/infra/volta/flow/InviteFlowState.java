package org.unlaxer.infra.volta.flow;
import com.tramli.FlowState;

public enum InviteFlowState implements FlowState {
    CONSENT_SHOWN(false, true),
    ACCOUNT_SWITCHING(false, false),
    ACCEPTED(false, false),
    COMPLETE(true, false),
    TERMINAL_ERROR(true, false),
    EXPIRED(true, false);

    private final boolean terminal;
    private final boolean initial;

    InviteFlowState(boolean terminal, boolean initial) {
        this.terminal = terminal;
        this.initial = initial;
    }

    @Override public boolean isTerminal() { return terminal; }
    @Override public boolean isInitial() { return initial; }
}
