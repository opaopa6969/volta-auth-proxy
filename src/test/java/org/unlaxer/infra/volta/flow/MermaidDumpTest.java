package org.unlaxer.infra.volta.flow;

import com.tramli.*;
import org.junit.jupiter.api.Test;

/**
 * Dumps Mermaid diagrams for all flows to stdout. Run with:
 * mvn test -pl . -Dtest="org.unlaxer.infra.volta.flow.MermaidDumpTest" -q
 */
class MermaidDumpTest {

    @Test
    void dumpAllFlowDiagrams() {
        System.out.println("=== OIDC ===");
        System.out.println(MermaidGenerator.generate(OidcFlowDefinitions.create()));
        System.out.println("=== PASSKEY ===");
        System.out.println(MermaidGenerator.generate(PasskeyFlowDefinitions.create()));
        System.out.println("=== MFA ===");
        System.out.println(MermaidGenerator.generate(MfaFlowDefinitions.create()));
        System.out.println("=== INVITE ===");
        System.out.println(MermaidGenerator.generate(InviteFlowDefinitions.create()));
    }
}
