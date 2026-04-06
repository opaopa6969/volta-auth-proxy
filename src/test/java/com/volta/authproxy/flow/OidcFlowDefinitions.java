package com.volta.authproxy.flow;

import static com.volta.authproxy.flow.OidcFlowState.*;
import static com.volta.authproxy.flow.StubProcessors.*;

import java.time.Duration;
import java.util.Set;

/**
 * OIDC flow definition for tests (uses stubs).
 * Updated to match real OidcFlowDef with RISK_CHECKED + BLOCKED.
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

                .from(USER_RESOLVED).auto(RISK_CHECKED, named("RiskCheckProcessor",
                        Set.of(TestUser.class), Set.of(TestSession.class)))

                .from(RISK_CHECKED).branch(fixedBranch("RiskAndMfaBranch", defaultBranchLabel,
                        Set.of(TestUser.class, TestSession.class)))
                    .to(COMPLETE, "no_mfa", named("SessionIssueProcessor",
                            Set.of(TestUser.class), Set.of(TestSession.class)))
                    .to(COMPLETE_MFA_PENDING, "mfa_required", named("SessionIssueProcessor",
                            Set.of(TestUser.class), Set.of(TestSession.class)))
                    .to(BLOCKED, "blocked")
                    .endBranch()

                .from(RETRIABLE_ERROR).auto(INIT, named("RetryProcessor"))

                .onAnyError(TERMINAL_ERROR)
                .onError(CALLBACK_RECEIVED, RETRIABLE_ERROR)

                .build();
    }
}
