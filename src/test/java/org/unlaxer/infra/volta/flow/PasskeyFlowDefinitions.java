package org.unlaxer.infra.volta.flow;
import org.unlaxer.tramli.FlowDefinition;

import static org.unlaxer.infra.volta.flow.PasskeyFlowState.*;
import static org.unlaxer.infra.volta.flow.StubProcessors.*;

import java.time.Duration;

final class PasskeyFlowDefinitions {

    private PasskeyFlowDefinitions() {}

    static FlowDefinition<PasskeyFlowState> create() {
        return FlowDefinition.builder("passkey", PasskeyFlowState.class)
                .ttl(Duration.ofMinutes(5))
                .maxGuardRetries(3)

                .from(INIT).auto(CHALLENGE_ISSUED, named("PasskeyChallengeProcessor"))
                .from(CHALLENGE_ISSUED).external(ASSERTION_RECEIVED, acceptingGuard("PasskeyAssertionGuard"))
                .from(ASSERTION_RECEIVED).auto(USER_RESOLVED, named("PasskeyVerifyProcessor"))
                .from(USER_RESOLVED).auto(COMPLETE, named("SessionIssueProcessor"))

                .onAnyError(TERMINAL_ERROR)
                .build();
    }
}
