package org.unlaxer.infra.volta.flow.oidc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.unlaxer.infra.volta.*;
import org.unlaxer.tramli.*;
import org.unlaxer.infra.volta.flow.*;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;

import static org.unlaxer.infra.volta.flow.oidc.OidcFlowData.*;

/**
 * CALLBACK_RECEIVED → TOKEN_EXCHANGED: Exchanges authorization code for tokens.
 * Thin wrapper — delegates to IdpProvider.exchange().
 */
public final class OidcTokenExchangeProcessor implements StateProcessor {
    private final AppConfig config;
    private final OidcService oidcService;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public OidcTokenExchangeProcessor(AppConfig config, OidcService oidcService) {
        this.config = config;
        this.oidcService = oidcService;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.mapper = new ObjectMapper();
    }

    @Override public String name() { return "OidcTokenExchangeProcessor"; }
    @Override public Set<Class<?>> requires() { return Set.of(OidcCallback.class, OidcRedirect.class); }
    @Override public Set<Class<?>> produces() { return Set.of(OidcTokens.class); }

    @Override
    public void process(FlowContext ctx) {
        OidcCallback callback = ctx.get(OidcCallback.class);
        OidcRedirect redirect = ctx.get(OidcRedirect.class);

        // Reconstruct OidcFlowRecord for the IdP exchange
        OidcFlowRecord flowRecord = new OidcFlowRecord(
                callback.state(),
                redirect.nonce(),
                redirect.codeVerifier(),
                null,  // returnTo — tracked in OidcRequest
                null,  // inviteCode — tracked in OidcRequest
                redirect.expiresAt(),
                redirect.providerId()
        );

        // Look up provider and exchange code for identity
        IdpProvider idp = resolveProvider(redirect.providerId());
        OidcIdentity identity = idp.exchange(callback.code(), flowRecord, config, http, mapper);

        // Get returnTo and inviteCode from original request
        OidcRequest request = ctx.get(OidcRequest.class);

        ctx.put(OidcTokens.class, new OidcTokens(
                identity.sub(),
                identity.email(),
                identity.displayName(),
                identity.emailVerified(),
                request.returnTo(),
                request.inviteCode(),
                identity.provider()
        ));
    }

    private IdpProvider resolveProvider(String providerId) {
        for (IdpProvider p : oidcService.enabledProviders()) {
            if (p.id().equalsIgnoreCase(providerId)) return p;
        }
        throw new FlowException("UNKNOWN_PROVIDER", "Provider not found: " + providerId);
    }
}
