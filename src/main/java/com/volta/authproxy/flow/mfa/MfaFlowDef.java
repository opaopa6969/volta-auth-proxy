package com.volta.authproxy.flow.mfa;

import com.volta.authproxy.*;
import com.volta.authproxy.flow.*;

import java.time.Duration;

import static com.volta.authproxy.flow.MfaFlowState.*;
import static com.volta.authproxy.flow.mfa.MfaFlowData.*;

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
