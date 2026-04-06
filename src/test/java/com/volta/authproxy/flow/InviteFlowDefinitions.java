package com.volta.authproxy.flow;

import static com.volta.authproxy.flow.InviteFlowState.*;
import static com.volta.authproxy.flow.StubProcessors.*;

import java.time.Duration;

final class InviteFlowDefinitions {

    private InviteFlowDefinitions() {}

    static FlowDefinition<InviteFlowState> create() {
        return create("email_match");
    }

    static FlowDefinition<InviteFlowState> create(String branchLabel) {
        return FlowDefinition.builder("invite", InviteFlowState.class)
                .ttl(Duration.ofDays(7))
                .maxGuardRetries(3)

                .from(CONSENT_SHOWN).branch(fixedBranch("EmailMatchBranch", branchLabel))
                    .to(ACCEPTED, "email_match", named("InviteAcceptProcessor"))
                    .to(ACCOUNT_SWITCHING, "email_mismatch")
                    .endBranch()

                .from(ACCOUNT_SWITCHING).external(ACCEPTED,
                        acceptingGuard("ResumeGuard"),
                        named("InviteAcceptProcessor"))

                .from(ACCEPTED).auto(COMPLETE, named("InviteCompleteProcessor"))

                .onAnyError(TERMINAL_ERROR)
                .onError(ACCOUNT_SWITCHING, EXPIRED)

                .build();
    }
}
