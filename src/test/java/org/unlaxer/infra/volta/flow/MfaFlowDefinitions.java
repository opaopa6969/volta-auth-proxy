package org.unlaxer.infra.volta.flow;
import com.tramli.FlowDefinition;

import static org.unlaxer.infra.volta.flow.MfaFlowState.*;
import static org.unlaxer.infra.volta.flow.StubProcessors.*;

import java.time.Duration;

final class MfaFlowDefinitions {

    private MfaFlowDefinitions() {}

    static FlowDefinition<MfaFlowState> create() {
        return FlowDefinition.builder("mfa", MfaFlowState.class)
                .ttl(Duration.ofMinutes(5))
                .maxGuardRetries(3)

                .from(CHALLENGE_SHOWN).external(VERIFIED,
                        acceptingGuard("MfaCodeGuard"),
                        named("MfaVerifyProcessor"))

                .onAnyError(TERMINAL_ERROR)
                .onError(CHALLENGE_SHOWN, EXPIRED) // TTL override

                .build();
    }
}
