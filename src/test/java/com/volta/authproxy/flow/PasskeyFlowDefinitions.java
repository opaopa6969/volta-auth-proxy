package com.volta.authproxy.flow;

import static com.volta.authproxy.flow.PasskeyFlowState.*;
import static com.volta.authproxy.flow.StubProcessors.*;

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
