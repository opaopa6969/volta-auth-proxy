package com.volta.authproxy;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the OAuth2/OIDC login flow across multiple identity providers.
 *
 * <p>This class is intentionally provider-agnostic. All provider-specific
 * logic lives in {@link IdpProvider} implementations ({@link GoogleIdp},
 * {@link GitHubIdp}, {@link MicrosoftIdp}, …).
 *
 * <p>To add a new provider, implement {@link IdpProvider}, then register it
 * in {@link #buildRegistry}.
 */
public final class OidcService {

    private final AppConfig config;
    private final SqlStore store;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Map<String, IdpProvider> registry;

    public OidcService(AppConfig config, SqlStore store) {
        this.config   = config;
        this.store    = store;
        this.http     = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.mapper   = new ObjectMapper();
        this.registry = buildRegistry();
    }

    // -------------------------------------------------------------------------
    // Provider registry
    // -------------------------------------------------------------------------

    /**
     * Register all known providers here.
     * Only enabled providers (credentials present) are active at runtime.
     */
    private static Map<String, IdpProvider> buildRegistry() {
        Map<String, IdpProvider> map = new LinkedHashMap<>();
        for (IdpProvider p : List.of(new GoogleIdp(), new GitHubIdp(), new MicrosoftIdp())) {
            map.put(p.id(), p);
        }
        return Collections.unmodifiableMap(map);
    }

    /** Returns all providers that are enabled in the current config. */
    public List<IdpProvider> enabledProviders() {
        List<IdpProvider> result = new ArrayList<>();
        for (IdpProvider p : registry.values()) {
            if (p.isEnabled(config)) result.add(p);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Authorization URL
    // -------------------------------------------------------------------------

    /**
     * Create the authorization redirect URL for the given provider.
     *
     * @param returnTo    URL to return to after login (may be null)
     * @param inviteCode  invitation code (may be null)
     * @param providerId  provider ID e.g. "GOOGLE" — falls back to first enabled if null
     */
    public String createAuthorizationUrl(String returnTo, String inviteCode, String providerId) {
        IdpProvider idp = resolveProvider(providerId);

        String state    = SecurityUtils.randomUrlSafe(32);
        String nonce    = idp.requiresNonce()  ? SecurityUtils.randomUrlSafe(32) : null;
        String verifier = idp.requiresPkce()   ? SecurityUtils.randomUrlSafe(32) : null;

        store.saveOidcFlow(new OidcFlowRecord(
                state, nonce, verifier, returnTo, inviteCode,
                Instant.now().plus(Duration.ofMinutes(10)),
                idp.id()
        ));

        return idp.buildAuthorizationUrl(state, nonce, verifier, config);
    }

    /** Backwards-compatible overload — falls back to first enabled provider. */
    public String createAuthorizationUrl(String returnTo, String inviteCode) {
        return createAuthorizationUrl(returnTo, inviteCode, null);
    }

    // -------------------------------------------------------------------------
    // Callback: exchange code → identity
    // -------------------------------------------------------------------------

    public OidcIdentity exchangeAndValidate(String code, String state) {
        OidcFlowRecord flow = store.consumeOidcFlow(state)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired state"));
        if (flow.expiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Login session expired");
        }
        IdpProvider idp = registry.get(flow.provider());
        if (idp == null) {
            throw new IllegalArgumentException("Unknown provider in flow: " + flow.provider());
        }
        return idp.exchange(code, flow, config, http, mapper);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private IdpProvider resolveProvider(String id) {
        if (id != null) {
            IdpProvider p = registry.get(id.toUpperCase(java.util.Locale.ROOT));
            if (p != null && p.isEnabled(config)) return p;
        }
        // First enabled provider as default
        for (IdpProvider p : registry.values()) {
            if (p.isEnabled(config)) return p;
        }
        throw new IllegalStateException("No IdP configured. Set GOOGLE_CLIENT_ID, GITHUB_CLIENT_ID, or MICROSOFT_CLIENT_ID.");
    }
}
