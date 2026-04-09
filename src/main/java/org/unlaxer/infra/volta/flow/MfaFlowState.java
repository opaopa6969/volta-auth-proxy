package org.unlaxer.infra.volta.flow;
import org.unlaxer.tramli.FlowState;

public enum MfaFlowState implements FlowState {
    CHALLENGE_SHOWN(false, true),
    VERIFIED(true, false),
    TERMINAL_ERROR(true, false),
    EXPIRED(true, false);

    private final boolean terminal;
    private final boolean initial;

    MfaFlowState(boolean terminal, boolean initial) {
        this.terminal = terminal;
        this.initial = initial;
    }

    @Override public boolean isTerminal() { return terminal; }
    @Override public boolean isInitial() { return initial; }
}
