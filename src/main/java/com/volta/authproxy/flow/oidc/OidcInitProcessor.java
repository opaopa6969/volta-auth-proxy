package com.volta.authproxy.flow.oidc;

import com.volta.authproxy.IdpProvider;
import com.volta.authproxy.AppConfig;
import com.volta.authproxy.OidcService;
import com.volta.authproxy.SecurityUtils;
import com.volta.authproxy.flow.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static com.volta.authproxy.flow.oidc.OidcFlowData.*;

/**
 * INIT → REDIRECTED: Generates authorization URL and stores OIDC params in context.
 * Thin wrapper — delegates to OidcService for provider resolution.
 */
public final class OidcInitProcessor implements StateProcessor {
    private final OidcService oidcService;
    private final OidcStateCodec stateCodec;
    private final AppConfig config;

    public OidcInitProcessor(OidcService oidcService, OidcStateCodec stateCodec, AppConfig config) {
        this.oidcService = oidcService;
        this.stateCodec = stateCodec;
        this.config = config;
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

        ctx.put(OidcRedirect.class, new OidcRedirect(
                authUrl, nonce, verifier, idp.id(),
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
