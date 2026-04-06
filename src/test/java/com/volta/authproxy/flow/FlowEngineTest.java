package com.volta.authproxy.flow;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlowEngineTest {

    @Test
    void oidcHappyPath_noMfa() {
        FlowTestHarness.forFlow(OidcFlowDefinitions.create("no_mfa"))
                .start()
                // INIT -> auto -> REDIRECTED (auto chain stops at REDIRECTED, needs external)
                .expectState(OidcFlowState.REDIRECTED)
                .thenResume()
                // REDIRECTED -> external -> CALLBACK_RECEIVED -> auto -> TOKEN_EXCHANGED -> auto -> USER_RESOLVED -> branch -> COMPLETE
                .assertFlowCompleted("COMPLETE");
    }

    @Test
    void oidcHappyPath_mfaRequired() {
        FlowTestHarness.forFlow(OidcFlowDefinitions.create("mfa_required"))
                .start()
                .expectState(OidcFlowState.REDIRECTED)
                .thenResume()
                .assertFlowCompleted("COMPLETE_MFA_PENDING");
    }

    @Test
    void passkeyHappyPath() {
        FlowTestHarness.forFlow(PasskeyFlowDefinitions.create())
                .start()
                .expectState(PasskeyFlowState.CHALLENGE_ISSUED)
                .thenResume()
                .assertFlowCompleted("COMPLETE");
    }

    @Test
    void mfaHappyPath() {
        FlowTestHarness.forFlow(MfaFlowDefinitions.create())
                .start()
                // MFA starts at CHALLENGE_SHOWN, no auto from there — needs external
                .expectState(MfaFlowState.CHALLENGE_SHOWN)
                .thenResume()
                .assertFlowCompleted("VERIFIED");
    }

    @Test
    void inviteHappyPath_emailMatch() {
        FlowTestHarness.forFlow(InviteFlowDefinitions.create("email_match"))
                .start()
                // CONSENT_SHOWN -> branch(email_match) -> ACCEPTED -> auto -> COMPLETE
                .assertFlowCompleted("COMPLETE");
    }

    @Test
    void inviteEmailMismatch_thenResume() {
        FlowTestHarness.forFlow(InviteFlowDefinitions.create("email_mismatch"))
                .start()
                // CONSENT_SHOWN -> branch(email_mismatch) -> ACCOUNT_SWITCHING
                .expectState(InviteFlowState.ACCOUNT_SWITCHING)
                .thenResume()
                // ACCOUNT_SWITCHING -> external(ResumeGuard) -> ACCEPTED -> auto -> COMPLETE
                .assertFlowCompleted("COMPLETE");
    }

    @Test
    void guardRejection_incrementsFailureCount() {
        // Build a passkey-like flow with rejecting guard
        var def = FlowDefinition.builder("reject-test", PasskeyFlowState.class)
                .ttl(java.time.Duration.ofMinutes(5))
                .maxGuardRetries(3)
                .from(PasskeyFlowState.INIT).auto(PasskeyFlowState.CHALLENGE_ISSUED,
                        StubProcessors.named("ChallengeProcessor"))
                .from(PasskeyFlowState.CHALLENGE_ISSUED).external(PasskeyFlowState.ASSERTION_RECEIVED,
                        StubProcessors.rejectingGuard("RejectGuard"))
                .from(PasskeyFlowState.ASSERTION_RECEIVED).auto(PasskeyFlowState.USER_RESOLVED,
                        StubProcessors.named("VerifyProcessor"))
                .from(PasskeyFlowState.USER_RESOLVED).auto(PasskeyFlowState.COMPLETE,
                        StubProcessors.named("SessionProcessor"))
                .onAnyError(PasskeyFlowState.TERMINAL_ERROR)
                .build();

        var harness = FlowTestHarness.forFlow(def)
                .start()
                .expectState(PasskeyFlowState.CHALLENGE_ISSUED);

        // First rejection
        harness.thenResume().expectState(PasskeyFlowState.CHALLENGE_ISSUED);
        assertEquals(1, harness.instance().guardFailureCount());

        // Second rejection
        harness.thenResume().expectState(PasskeyFlowState.CHALLENGE_ISSUED);
        assertEquals(2, harness.instance().guardFailureCount());

        // Third rejection -> TERMINAL_ERROR
        harness.thenResume();
        assertTrue(harness.instance().isCompleted());
        assertEquals("TERMINAL_ERROR", harness.instance().exitState());
    }

    @Test
    void transitionLog_recordsAllTransitions() {
        var harness = FlowTestHarness.forFlow(PasskeyFlowDefinitions.create())
                .start()
                .expectState(PasskeyFlowState.CHALLENGE_ISSUED);

        // Should have: INIT -> CHALLENGE_ISSUED
        assertEquals(1, harness.store().transitionLog().size());

        harness.thenResume();
        // CHALLENGE_ISSUED -> ASSERTION_RECEIVED -> USER_RESOLVED -> COMPLETE
        assertTrue(harness.store().transitionLog().size() >= 4);
    }
}
