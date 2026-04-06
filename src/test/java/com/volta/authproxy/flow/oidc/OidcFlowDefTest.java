package com.volta.authproxy.flow.oidc;

import com.volta.authproxy.*;
import com.volta.authproxy.flow.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.volta.authproxy.flow.OidcFlowState.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the real OidcFlowDef builds successfully and has correct structure.
 * Passes real instances (with null deps) — build() only validates structure, not runtime behavior.
 */
class OidcFlowDefTest {

    @Test
    void definitionBuildsSuccessfully() {
        var def = createTestDefinition();

        assertEquals("oidc", def.name());
        assertEquals(INIT, def.initialState());
        assertTrue(def.terminalStates().contains(COMPLETE));
        assertTrue(def.terminalStates().contains(COMPLETE_MFA_PENDING));
        assertTrue(def.terminalStates().contains(TERMINAL_ERROR));
    }

    @Test
    void hasCorrectTransitionStructure() {
        var def = createTestDefinition();

        // INIT -> REDIRECTED (auto)
        var fromInit = def.transitionsFrom(INIT);
        assertEquals(1, fromInit.size());
        assertEquals(REDIRECTED, fromInit.getFirst().to());
        assertTrue(fromInit.getFirst().isAuto());

        // REDIRECTED -> CALLBACK_RECEIVED (external with guard)
        var ext = def.externalFrom(REDIRECTED);
        assertTrue(ext.isPresent());
        assertEquals(CALLBACK_RECEIVED, ext.get().to());
        assertNotNull(ext.get().guard());
        assertEquals("OidcCallbackGuard", ext.get().guard().name());

        // CALLBACK_RECEIVED -> TOKEN_EXCHANGED (auto)
        var fromCallback = def.transitionsFrom(CALLBACK_RECEIVED);
        assertEquals(1, fromCallback.size());
        assertTrue(fromCallback.getFirst().isAuto());

        // USER_RESOLVED -> RISK_CHECKED (auto)
        var fromUserResolved = def.transitionsFrom(USER_RESOLVED);
        assertEquals(1, fromUserResolved.size());
        assertEquals(RISK_CHECKED, fromUserResolved.getFirst().to());

        // RISK_CHECKED -> COMPLETE or COMPLETE_MFA_PENDING or BLOCKED (branch)
        var fromRiskChecked = def.transitionsFrom(RISK_CHECKED);
        assertTrue(fromRiskChecked.size() >= 3);
        assertTrue(fromRiskChecked.stream().anyMatch(t -> t.to() == COMPLETE));
        assertTrue(fromRiskChecked.stream().anyMatch(t -> t.to() == COMPLETE_MFA_PENDING));
        assertTrue(fromRiskChecked.stream().anyMatch(t -> t.to() == BLOCKED));
    }

    @Test
    void errorTransitionsExist() {
        var def = createTestDefinition();
        assertEquals(RETRIABLE_ERROR, def.errorTransitions().get(CALLBACK_RECEIVED));
        assertEquals(TERMINAL_ERROR, def.errorTransitions().get(INIT));
    }

    @Test
    void mermaidDiagramGenerable() {
        var def = createTestDefinition();
        String mermaid = MermaidGenerator.generate(def);
        assertTrue(mermaid.contains("INIT --> REDIRECTED"));
        assertTrue(mermaid.contains("REDIRECTED --> CALLBACK_RECEIVED"));
        assertTrue(mermaid.contains("[OidcCallbackGuard]"));
        assertTrue(mermaid.contains("RiskAndMfaBranch"));
    }

    /**
     * Build() validates structure (requires/produces chain, reachability, etc.)
     * but does NOT invoke processor methods. So null deps in services are safe here.
     */
    private FlowDefinition<OidcFlowState> createTestDefinition() {
        // All these null deps are fine — OidcFlowDef.create() only instantiates processors,
        // it doesn't call process(). The build() validation checks structure only.
        var oidcService = new OidcService(AppConfig.fromEnv(), null,
                new VoltaConfig(1, List.of()));
        var stateCodec = new OidcStateCodec("test-key-for-unit-tests!!");
        var authService = new AuthService(AppConfig.fromEnv(), null, null, null);
        var appRegistry = new AppRegistry(List.of());
        var config = AppConfig.fromEnv();

        var fraudAlert = new FraudAlertClient(config, new com.fasterxml.jackson.databind.ObjectMapper());

        return OidcFlowDef.create(oidcService, stateCodec, authService,
                appRegistry, null, config, fraudAlert);
    }
}
