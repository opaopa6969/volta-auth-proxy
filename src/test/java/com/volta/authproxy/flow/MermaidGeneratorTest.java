package com.volta.authproxy.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MermaidGeneratorTest {

    @Test
    void oidcDiagramContainsAllStates() {
        var def = OidcFlowDefinitions.create();
        String mermaid = MermaidGenerator.generate(def);

        assertTrue(mermaid.startsWith("stateDiagram-v2"));
        assertTrue(mermaid.contains("[*] --> INIT"));
        assertTrue(mermaid.contains("INIT --> REDIRECTED"));
        assertTrue(mermaid.contains("REDIRECTED --> CALLBACK_RECEIVED"));
        assertTrue(mermaid.contains("CALLBACK_RECEIVED --> TOKEN_EXCHANGED"));
        assertTrue(mermaid.contains("TOKEN_EXCHANGED --> USER_RESOLVED"));
        assertTrue(mermaid.contains("USER_RESOLVED --> RISK_CHECKED"));
        assertTrue(mermaid.contains("RISK_CHECKED --> COMPLETE"));
        assertTrue(mermaid.contains("COMPLETE --> [*]"));
        assertTrue(mermaid.contains("TERMINAL_ERROR --> [*]"));
    }

    @Test
    void passkeyDiagramContainsAllStates() {
        String mermaid = MermaidGenerator.generate(PasskeyFlowDefinitions.create());
        assertTrue(mermaid.contains("INIT --> CHALLENGE_ISSUED"));
        assertTrue(mermaid.contains("CHALLENGE_ISSUED --> ASSERTION_RECEIVED"));
        assertTrue(mermaid.contains("ASSERTION_RECEIVED --> USER_RESOLVED"));
        assertTrue(mermaid.contains("USER_RESOLVED --> COMPLETE"));
    }

    @Test
    void mfaDiagramContainsAllStates() {
        String mermaid = MermaidGenerator.generate(MfaFlowDefinitions.create());
        assertTrue(mermaid.contains("CHALLENGE_SHOWN --> VERIFIED"));
        assertTrue(mermaid.contains("VERIFIED --> [*]"));
    }

    @Test
    void inviteDiagramContainsAllStates() {
        String mermaid = MermaidGenerator.generate(InviteFlowDefinitions.create());
        assertTrue(mermaid.contains("CONSENT_SHOWN --> ACCEPTED"));
        assertTrue(mermaid.contains("CONSENT_SHOWN --> ACCOUNT_SWITCHING"));
        assertTrue(mermaid.contains("ACCEPTED --> COMPLETE"));
    }

    @Test
    void writeToFileCreatesFile(@TempDir Path tempDir) throws IOException {
        var def = OidcFlowDefinitions.create();
        String content = MermaidGenerator.writeToFile(def, tempDir);

        Path file = tempDir.resolve("flow-oidc.mmd");
        assertTrue(Files.exists(file));
        assertEquals(content, Files.readString(file));
    }

    @Test
    void diagramIncludesTransitionLabels() {
        String mermaid = MermaidGenerator.generate(OidcFlowDefinitions.create());
        // Auto transitions should show processor name
        assertTrue(mermaid.contains("OidcInitProcessor"));
        // External transitions should show guard name
        assertTrue(mermaid.contains("[OidcCallbackGuard]"));
        // Branch should show branch name
        assertTrue(mermaid.contains("RiskAndMfaBranch"));
    }
}
