package org.unlaxer.infra.volta.flow.oidc;

import org.unlaxer.infra.volta.IdpProvider;
import org.unlaxer.infra.volta.AppConfig;
import org.unlaxer.infra.volta.KeyCipher;
import org.unlaxer.infra.volta.OidcService;
import org.unlaxer.infra.volta.SecurityUtils;
import org.unlaxer.tramli.*;
import org.unlaxer.infra.volta.flow.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.unlaxer.infra.volta.flow.oidc.OidcFlowData.*;

/**
 * INIT → REDIRECTED: Generates authorization URL and stores OIDC params in context.
 * Thin wrapper — delegates to OidcService for provider resolution.
 */
public final class OidcInitProcessor implements StateProcessor {
    private final OidcService oidcService;
    private final OidcStateCodec stateCodec;
    private final AppConfig config;
    private final KeyCipher keyCipher;

    public OidcInitProcessor(OidcService oidcService, OidcStateCodec stateCodec,
                             AppConfig config, KeyCipher keyCipher) {
        this.oidcService = oidcService;
        this.stateCodec = stateCodec;
        this.config = config;
        this.keyCipher = keyCipher;
    }

    @Override public String name() { return "OidcInitProcessor"; }
    @Override public Set<Class<?>> requires() { return Set.of(OidcRequest.class); }
    @Override public Set<Class<?>> produces() { return Set.of(OidcRedirect.class); }

    @Override
    public void process(FlowContext ctx) {
        OidcRequest request = ctx.get(OidcRequest.class);

        // Resolve provider
        IdpProvider idp = resolveProvider(request.provider());

        // Generate OIDC params
        String nonce = idp.requiresNonce() ? SecurityUtils.randomUrlSafe(32) : null;
        String verifier = idp.requiresPkce() ? SecurityUtils.randomUrlSafe(32) : null;

        // Encode flow_id into OIDC state parameter (HMAC signed)
        String csrfNonce = SecurityUtils.randomUrlSafe(16);
        String state = stateCodec.encode(ctx.flowId(), csrfNonce);

        // Build authorization URL
        String authUrl = idp.buildAuthorizationUrl(state, nonce, verifier, config);

        // Encrypt PKCE verifier before storing in flow context
        String encryptedVerifier = (verifier != null && keyCipher != null)
                ? keyCipher.encrypt(verifier) : verifier;

        ctx.put(OidcRedirect.class, new OidcRedirect(
                authUrl, nonce, encryptedVerifier, idp.id(),
                Instant.now().plus(Duration.ofMinutes(10))
        ));
    }

    private IdpProvider resolveProvider(String providerId) {
        for (IdpProvider p : oidcService.enabledProviders()) {
            if (providerId != null && p.id().equalsIgnoreCase(providerId)) return p;
        }
        var enabled = oidcService.enabledProviders();
        if (!enabled.isEmpty()) return enabled.getFirst();
        throw new FlowException("NO_IDP", "No identity provider configured");
    }
}
