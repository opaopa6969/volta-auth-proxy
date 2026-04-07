package org.unlaxer.infra.volta.flow.mfa;

import org.unlaxer.infra.volta.*;
import com.tramli.*;
import org.unlaxer.infra.volta.flow.*;

import java.time.Duration;

import static org.unlaxer.infra.volta.flow.MfaFlowState.*;
import static org.unlaxer.infra.volta.flow.mfa.MfaFlowData.*;

/**
 * MFA verification flow definition (sequential, not call/return).
 * CHALLENGE_SHOWN → [code] → VERIFIED
 * Started after OIDC completes with MFA_PENDING.
 */
public final class MfaFlowDef {

    private MfaFlowDef() {}

    public static FlowDefinition<MfaFlowState> create(
            SqlStore store,
            AuthService authService,
            KeyCipher secretCipher) {

        return FlowDefinition.builder("mfa", MfaFlowState.class)
                .ttl(Duration.ofMinutes(5))
                .maxGuardRetries(5)
                .initiallyAvailable(MfaSessionContext.class)

                .from(CHALLENGE_SHOWN).external(VERIFIED,
                        new MfaCodeGuard(),
                        new MfaVerifyProcessor(store, authService, secretCipher))

                .onAnyError(TERMINAL_ERROR)

                .build();
    }
}
