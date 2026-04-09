package org.unlaxer.infra.volta.flow.passkey;

import org.unlaxer.infra.volta.*;
import org.unlaxer.tramli.*;
import org.unlaxer.infra.volta.flow.*;

import java.time.Duration;

import static org.unlaxer.infra.volta.flow.PasskeyFlowState.*;
import static org.unlaxer.infra.volta.flow.passkey.PasskeyFlowData.*;

/**
 * Passkey login flow definition.
 * INIT → CHALLENGE_ISSUED → [assertion] → ASSERTION_RECEIVED → USER_RESOLVED → COMPLETE
 * Passkey = MFA equivalent, so no MFA branch.
 */
public final class PasskeyFlowDef {

    private PasskeyFlowDef() {}

    public static FlowDefinition<PasskeyFlowState> create(
            AppConfig config,
            AuthService authService,
            AppRegistry appRegistry,
            SqlStore store) {

        return FlowDefinition.builder("passkey", PasskeyFlowState.class)
                .ttl(Duration.ofMinutes(5))
                .maxGuardRetries(3)
                .initiallyAvailable(PasskeyRequest.class)

                .from(INIT).auto(CHALLENGE_ISSUED,
                        new PasskeyChallengeProcessor(config))

                .from(CHALLENGE_ISSUED).external(ASSERTION_RECEIVED,
                        new PasskeyAssertionGuard())

                .from(ASSERTION_RECEIVED).auto(USER_RESOLVED,
                        new PasskeyVerifyProcessor(config, store))

                .from(USER_RESOLVED).auto(COMPLETE,
                        new PasskeySessionProcessor(authService, appRegistry, store, config))

                .onAnyError(TERMINAL_ERROR)

                .build();
    }
}
