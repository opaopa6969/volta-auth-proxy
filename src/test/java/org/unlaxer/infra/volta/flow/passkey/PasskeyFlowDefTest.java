package org.unlaxer.infra.volta.flow.passkey;

import org.unlaxer.infra.volta.*;
import org.unlaxer.tramli.*;
import org.unlaxer.infra.volta.flow.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.unlaxer.infra.volta.flow.PasskeyFlowState.*;
import static org.junit.jupiter.api.Assertions.*;

class PasskeyFlowDefTest {

    @Test
    void definitionBuildsSuccessfully() {
        var def = createTestDefinition();
        assertEquals("passkey", def.name());
        assertEquals(INIT, def.initialState());
        assertTrue(def.terminalStates().contains(COMPLETE));
        assertTrue(def.terminalStates().contains(TERMINAL_ERROR));
    }

    @Test
    void hasCorrectTransitionStructure() {
        var def = createTestDefinition();

        // INIT -> CHALLENGE_ISSUED (auto)
        var fromInit = def.transitionsFrom(INIT);
        assertEquals(1, fromInit.size());
        assertTrue(fromInit.getFirst().isAuto());
        assertEquals(CHALLENGE_ISSUED, fromInit.getFirst().to());

        // CHALLENGE_ISSUED -> ASSERTION_RECEIVED (external)
        var ext = def.externalFrom(CHALLENGE_ISSUED);
        assertTrue(ext.isPresent());
        assertEquals("PasskeyAssertionGuard", ext.get().guard().name());

        // ASSERTION_RECEIVED -> USER_RESOLVED (auto)
        var fromAssertion = def.transitionsFrom(ASSERTION_RECEIVED);
        assertEquals(1, fromAssertion.size());
        assertTrue(fromAssertion.getFirst().isAuto());

        // USER_RESOLVED -> COMPLETE (auto)
        var fromResolved = def.transitionsFrom(USER_RESOLVED);
        assertEquals(1, fromResolved.size());
        assertEquals(COMPLETE, fromResolved.getFirst().to());
    }

    @Test
    void mermaidDiagram() {
        var def = createTestDefinition();
        String mermaid = MermaidGenerator.generate(def);
        assertTrue(mermaid.contains("INIT --> CHALLENGE_ISSUED"));
        assertTrue(mermaid.contains("CHALLENGE_ISSUED --> ASSERTION_RECEIVED"));
        assertTrue(mermaid.contains("[PasskeyAssertionGuard]"));
        assertTrue(mermaid.contains("USER_RESOLVED --> COMPLETE"));
    }

    private FlowDefinition<PasskeyFlowState> createTestDefinition() {
        return PasskeyFlowDef.create(
                AppConfig.fromEnv(),
                new AuthService(AppConfig.fromEnv(), null, null, null),
                new AppRegistry(List.of()),
                null);
    }
}
