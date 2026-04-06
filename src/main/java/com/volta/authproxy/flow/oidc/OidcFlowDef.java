package com.volta.authproxy.flow.oidc;

import com.volta.authproxy.*;
import com.volta.authproxy.flow.*;

import java.time.Duration;
import java.util.Set;

import static com.volta.authproxy.flow.OidcFlowState.*;
import static com.volta.authproxy.flow.oidc.OidcFlowData.*;

/**
 * Real OIDC flow definition with production processors.
 * This is the single source of truth for the OIDC state machine.
 */
public final class OidcFlowDef {

    private OidcFlowDef() {}

    public static FlowDefinition<OidcFlowState> create(
            OidcService oidcService,
            OidcStateCodec stateCodec,
            AuthService authService,
            AppRegistry appRegistry,
            SqlStore store,
            AppConfig config) {

        return FlowDefinition.builder("oidc", OidcFlowState.class)
                .ttl(Duration.ofMinutes(5))
                .maxGuardRetries(3)
                .initiallyAvailable(OidcRequest.class)

                .from(INIT).auto(REDIRECTED,
                        new OidcInitProcessor(oidcService, stateCodec, config))

                .from(REDIRECTED).external(CALLBACK_RECEIVED,
                        new OidcCallbackGuard())

                .from(CALLBACK_RECEIVED).auto(TOKEN_EXCHANGED,
                        new OidcTokenExchangeProcessor(config, oidcService))

                .from(TOKEN_EXCHANGED).auto(USER_RESOLVED,
                        new UserResolveProcessor(store))

                .from(USER_RESOLVED).auto(RISK_CHECKED,
                        new RiskCheckProcessor(store))

                .from(RISK_CHECKED).branch(new RiskAndMfaBranch())
                    .to(COMPLETE, RiskAndMfaBranch.NO_MFA,
                            new SessionIssueProcessor(authService, appRegistry, store, config))
                    .to(COMPLETE_MFA_PENDING, RiskAndMfaBranch.MFA_REQUIRED,
                            new SessionIssueProcessor(authService, appRegistry, store, config))
                    .to(BLOCKED, RiskAndMfaBranch.BLOCKED)
                    .endBranch()

                .from(RETRIABLE_ERROR).auto(INIT, new RetryProcessor())

                .onAnyError(TERMINAL_ERROR)
                .onError(CALLBACK_RECEIVED, RETRIABLE_ERROR)

                .build();
    }

    /** Simple processor that clears error state for retry. */
    private static final class RetryProcessor implements StateProcessor {
        @Override public String name() { return "RetryProcessor"; }
        @Override public Set<Class<?>> requires() { return Set.of(); }
        @Override public Set<Class<?>> produces() { return Set.of(); }
        @Override public void process(FlowContext ctx) {
            // No-op — retry starts a fresh INIT cycle
        }
    }
}
