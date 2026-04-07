package org.unlaxer.infra.volta.flow.invite;

import org.unlaxer.infra.volta.*;
import com.tramli.*;
import org.unlaxer.infra.volta.flow.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.unlaxer.infra.volta.flow.InviteFlowState.*;
import static org.junit.jupiter.api.Assertions.*;

class InviteFlowDefTest {

    @Test
    void definitionBuildsSuccessfully() {
        var def = createTestDefinition();
        assertEquals("invite", def.name());
        assertEquals(CONSENT_SHOWN, def.initialState());
        assertTrue(def.terminalStates().contains(COMPLETE));
        assertTrue(def.terminalStates().contains(TERMINAL_ERROR));
        assertTrue(def.terminalStates().contains(EXPIRED));
    }

    @Test
    void hasCorrectBranchStructure() {
        var def = createTestDefinition();

        // CONSENT_SHOWN → branch (email match or mismatch)
        var fromConsent = def.transitionsFrom(CONSENT_SHOWN);
        assertTrue(fromConsent.size() >= 2);
        assertTrue(fromConsent.stream().anyMatch(t -> t.to() == ACCEPTED));
        assertTrue(fromConsent.stream().anyMatch(t -> t.to() == ACCOUNT_SWITCHING));
    }

    @Test
    void accountSwitchingHasExternalTransition() {
        var def = createTestDefinition();
        var ext = def.externalFrom(ACCOUNT_SWITCHING);
        assertTrue(ext.isPresent());
        assertEquals(ACCEPTED, ext.get().to());
        assertEquals("ResumeGuard", ext.get().guard().name());
    }

    @Test
    void acceptedAutoCompetes() {
        var def = createTestDefinition();
        var fromAccepted = def.transitionsFrom(ACCEPTED);
        assertEquals(1, fromAccepted.size());
        assertTrue(fromAccepted.getFirst().isAuto());
        assertEquals(COMPLETE, fromAccepted.getFirst().to());
    }

    @Test
    void mermaidDiagram() {
        var def = createTestDefinition();
        String mermaid = MermaidGenerator.generate(def);
        assertTrue(mermaid.contains("CONSENT_SHOWN --> ACCEPTED"));
        assertTrue(mermaid.contains("CONSENT_SHOWN --> ACCOUNT_SWITCHING"));
        assertTrue(mermaid.contains("ACCOUNT_SWITCHING --> ACCEPTED"));
        assertTrue(mermaid.contains("ACCEPTED --> COMPLETE"));
        assertTrue(mermaid.contains("[ResumeGuard]"));
    }

    @Test
    void errorTransitions() {
        var def = createTestDefinition();
        assertEquals(EXPIRED, def.errorTransitions().get(ACCOUNT_SWITCHING));
    }

    private FlowDefinition<InviteFlowState> createTestDefinition() {
        return InviteFlowDef.create(
                new AuthService(AppConfig.fromEnv(), null, null, null),
                null, AppConfig.fromEnv());
    }
}
