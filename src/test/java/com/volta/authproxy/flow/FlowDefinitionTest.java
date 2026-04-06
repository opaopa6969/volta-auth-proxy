package com.volta.authproxy.flow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlowDefinitionTest {

    @Test
    void oidcFlowDefinitionBuildsSuccessfully() {
        var def = OidcFlowDefinitions.create();
        assertEquals("oidc", def.name());
        assertEquals(OidcFlowState.INIT, def.initialState());
        assertTrue(def.terminalStates().contains(OidcFlowState.COMPLETE));
        assertTrue(def.terminalStates().contains(OidcFlowState.COMPLETE_MFA_PENDING));
        assertTrue(def.terminalStates().contains(OidcFlowState.TERMINAL_ERROR));
    }

    @Test
    void passkeyFlowDefinitionBuildsSuccessfully() {
        var def = PasskeyFlowDefinitions.create();
        assertEquals("passkey", def.name());
        assertEquals(PasskeyFlowState.INIT, def.initialState());
        assertTrue(def.terminalStates().contains(PasskeyFlowState.COMPLETE));
    }

    @Test
    void mfaFlowDefinitionBuildsSuccessfully() {
        var def = MfaFlowDefinitions.create();
        assertEquals("mfa", def.name());
        assertEquals(MfaFlowState.CHALLENGE_SHOWN, def.initialState());
        assertTrue(def.terminalStates().contains(MfaFlowState.VERIFIED));
    }

    @Test
    void inviteFlowDefinitionBuildsSuccessfully() {
        var def = InviteFlowDefinitions.create();
        assertEquals("invite", def.name());
        assertEquals(InviteFlowState.CONSENT_SHOWN, def.initialState());
        assertTrue(def.terminalStates().contains(InviteFlowState.COMPLETE));
    }

    @Test
    void mfaBranchPathAlsoWorks() {
        var def = OidcFlowDefinitions.create("mfa_required");
        assertEquals("oidc", def.name());
    }

    @Test
    void transitionsFromReturnsCorrectList() {
        var def = OidcFlowDefinitions.create();
        var fromInit = def.transitionsFrom(OidcFlowState.INIT);
        assertEquals(1, fromInit.size());
        assertEquals(OidcFlowState.REDIRECTED, fromInit.getFirst().to());
    }

    @Test
    void externalFromReturnsGuardedTransition() {
        var def = OidcFlowDefinitions.create();
        var ext = def.externalFrom(OidcFlowState.REDIRECTED);
        assertTrue(ext.isPresent());
        assertEquals(OidcFlowState.CALLBACK_RECEIVED, ext.get().to());
        assertNotNull(ext.get().guard());
    }

    @Test
    void terminalStateHasNoTransitions() {
        var def = OidcFlowDefinitions.create();
        assertTrue(def.transitionsFrom(OidcFlowState.COMPLETE).isEmpty());
    }
}
