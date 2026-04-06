package com.volta.authproxy.flow;

import static com.volta.authproxy.flow.OidcFlowState.*;
import static com.volta.authproxy.flow.StubProcessors.*;

import java.time.Duration;
import java.util.Set;

/**
 * OIDC flow definition for tests (uses stubs — real processors come in Phase 2).
 */
final class OidcFlowDefinitions {

    private OidcFlowDefinitions() {}

    static FlowDefinition<OidcFlowState> create() {
        return create("no_mfa");
    }

    static FlowDefinition<OidcFlowState> create(String defaultBranchLabel) {
        return FlowDefinition.builder("oidc", OidcFlowState.class)
                .ttl(Duration.ofMinutes(5))
                .maxGuardRetries(3)

                .from(INIT).auto(REDIRECTED, named("OidcInitProcessor",
                        Set.of(), Set.of(TestRedirect.class)))

                .from(REDIRECTED).external(CALLBACK_RECEIVED,
                        acceptingGuard("OidcCallbackGuard",
                                Set.of(TestRedirect.class), Set.of(TestCallback.class)))

                .from(CALLBACK_RECEIVED).auto(TOKEN_EXCHANGED, named("OidcTokenExchangeProcessor",
                        Set.of(TestCallback.class), Set.of(TestToken.class)))

                .from(TOKEN_EXCHANGED).auto(USER_RESOLVED, named("UserResolveProcessor",
                        Set.of(TestToken.class), Set.of(TestUser.class)))

                .from(USER_RESOLVED).branch(fixedBranch("MfaCheckBranch", defaultBranchLabel,
                        Set.of(TestUser.class)))
                    .to(COMPLETE, "no_mfa", named("SessionIssueProcessor",
                            Set.of(TestUser.class), Set.of(TestSession.class)))
                    .to(COMPLETE_MFA_PENDING, "mfa_required", named("SessionIssueProcessor",
                            Set.of(TestUser.class), Set.of(TestSession.class)))
                    .endBranch()

                .from(RETRIABLE_ERROR).auto(INIT, named("RetryProcessor"))

                .onAnyError(TERMINAL_ERROR)
                .onError(CALLBACK_RECEIVED, RETRIABLE_ERROR) // IdP timeout → retry

                .build();
    }
}
