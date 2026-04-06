package com.volta.authproxy.flow.mfa;

import com.volta.authproxy.*;
import com.volta.authproxy.flow.*;
import org.junit.jupiter.api.Test;

import static com.volta.authproxy.flow.MfaFlowState.*;
import static org.junit.jupiter.api.Assertions.*;

class MfaFlowDefTest {

    @Test
    void definitionBuildsSuccessfully() {
        var def = createTestDefinition();
        assertEquals("mfa", def.name());
        assertEquals(CHALLENGE_SHOWN, def.initialState());
        assertTrue(def.terminalStates().contains(VERIFIED));
        assertTrue(def.terminalStates().contains(TERMINAL_ERROR));
        assertTrue(def.terminalStates().contains(EXPIRED));
    }

    @Test
    void hasCorrectTransitionStructure() {
        var def = createTestDefinition();

        // CHALLENGE_SHOWN -> VERIFIED (external with guard + processor)
        var ext = def.externalFrom(CHALLENGE_SHOWN);
        assertTrue(ext.isPresent());
        assertEquals(VERIFIED, ext.get().to());
        assertNotNull(ext.get().guard());
        assertEquals("MfaCodeGuard", ext.get().guard().name());
        assertNotNull(ext.get().processor());
        assertEquals("MfaVerifyProcessor", ext.get().processor().name());
    }

    @Test
    void mermaidDiagram() {
        var def = createTestDefinition();
        String mermaid = MermaidGenerator.generate(def);
        assertTrue(mermaid.contains("CHALLENGE_SHOWN --> VERIFIED"));
        assertTrue(mermaid.contains("[MfaCodeGuard]"));
        assertTrue(mermaid.contains("VERIFIED --> [*]"));
    }

    private FlowDefinition<MfaFlowState> createTestDefinition() {
        return MfaFlowDef.create(null,
                new AuthService(AppConfig.fromEnv(), null, null, null),
                new KeyCipher("test-key-for-unit-tests!!"));
    }
}
