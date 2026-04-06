package com.volta.authproxy.flow;

import com.volta.authproxy.*;
import com.volta.authproxy.flow.invite.InviteFlowDef;
import com.volta.authproxy.flow.mfa.MfaFlowDef;
import com.volta.authproxy.flow.oidc.OidcFlowDef;
import com.volta.authproxy.flow.passkey.PasskeyFlowDef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Generates Mermaid diagrams for all flows to docs/diagrams/.
 * Run manually or in CI to keep diagrams up to date.
 */
class MermaidDiagramGenerationTest {

    private static final Path DIAGRAMS_DIR = Path.of("docs/diagrams");

    @Test
    void generateAllDiagrams() throws IOException {
        Files.createDirectories(DIAGRAMS_DIR);

        var config = AppConfig.fromEnv();
        var authService = new AuthService(config, null, null, null);

        // OIDC
        var oidcDef = OidcFlowDef.create(
                new OidcService(config, null, new VoltaConfig(1, List.of())),
                new OidcStateCodec("test"), authService,
                new AppRegistry(List.of()), null, config);
        String oidc = MermaidGenerator.writeToFile(oidcDef, DIAGRAMS_DIR);
        assertTrue(oidc.contains("INIT --> REDIRECTED"));

        // Passkey
        var passkeyDef = PasskeyFlowDef.create(config, authService, new AppRegistry(List.of()), null);
        String passkey = MermaidGenerator.writeToFile(passkeyDef, DIAGRAMS_DIR);
        assertTrue(passkey.contains("CHALLENGE_ISSUED"));

        // MFA
        var mfaDef = MfaFlowDef.create(null, authService, new KeyCipher("test-key!!"));
        String mfa = MermaidGenerator.writeToFile(mfaDef, DIAGRAMS_DIR);
        assertTrue(mfa.contains("CHALLENGE_SHOWN"));

        // Invite
        var inviteDef = InviteFlowDef.create(authService, null, config);
        String invite = MermaidGenerator.writeToFile(inviteDef, DIAGRAMS_DIR);
        assertTrue(invite.contains("CONSENT_SHOWN"));

        // Verify files exist
        assertTrue(Files.exists(DIAGRAMS_DIR.resolve("flow-oidc.mmd")));
        assertTrue(Files.exists(DIAGRAMS_DIR.resolve("flow-passkey.mmd")));
        assertTrue(Files.exists(DIAGRAMS_DIR.resolve("flow-mfa.mmd")));
        assertTrue(Files.exists(DIAGRAMS_DIR.resolve("flow-invite.mmd")));
    }
}
