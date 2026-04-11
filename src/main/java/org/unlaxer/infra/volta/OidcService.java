package org.unlaxer.infra.volta;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates the OAuth2/OIDC login flow across multiple identity providers.
 *
 * <p>This class is intentionally provider-agnostic. All provider-specific
 * logic lives in {@link IdpProvider} implementations ({@link GoogleIdp},
 * {@link GitHubIdp}, {@link MicrosoftIdp}, …).
 *
 * <p>To add a new provider, implement {@link IdpProvider}, register it in
 * {@link #ALL_PROVIDERS}, and add an entry to {@code volta-config.yaml}.
 *
 * <p>The enabled provider list can be reloaded at runtime without restart via
 * {@link #reload(VoltaConfig)} — safe to call from a SIGHUP handler.
 */
public final class OidcService {

    private final AppConfig config;
    private final SqlStore store;
    private final KeyCipher keyCipher;
    private final HttpClient http;
    private final ObjectMapper mapper;

    /** All providers known to this build. Never changes at runtime. */
    private static final Map<String, IdpProvider> ALL_PROVIDERS;
    static {
        Map<String, IdpProvider> m = new LinkedHashMap<>();
        for (IdpProvider p : List.of(new GoogleIdp(), new GitHubIdp(), new MicrosoftIdp(), new AppleIdp(), new LinkedInIdp())) {
            m.put(p.id(), p);
        }
        ALL_PROVIDERS = Collections.unmodifiableMap(m);
    }

    /** Hot-swappable list of enabled providers (order = login-page order). */
    private final AtomicReference<List<IdpProvider>> enabledRef;

    public OidcService(AppConfig config, SqlStore store, VoltaConfig voltaConfig) {
        this(config, store, voltaConfig, null);
    }

    public OidcService(AppConfig config, SqlStore store, VoltaConfig voltaConfig, KeyCipher keyCipher) {
        this.config     = config;
        this.store      = store;
        this.keyCipher  = keyCipher;
        this.http       = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.mapper     = new ObjectMapper();
        this.enabledRef = new AtomicReference<>(computeEnabled(voltaConfig));
    }

    // -------------------------------------------------------------------------
    // Hot reload
    // -------------------------------------------------------------------------

    /**
     * Replace the enabled provider list atomically.
     * Safe to call from any thread (e.g. a SIGHUP signal handler).
     */
    public void reload(VoltaConfig voltaConfig) {
        enabledRef.set(computeEnabled(voltaConfig));
    }

    // -------------------------------------------------------------------------
    // Provider registry
    // -------------------------------------------------------------------------

    /** Returns all providers that are currently enabled. */
    public List<IdpProvider> enabledProviders() {
        return enabledRef.get();
    }

    /**
     * Compute the enabled provider list from config.
     *
     * <p>If {@code volta-config.yaml} contains an {@code idp:} section, use it
     * for ordering and enablement. Otherwise fall back to ENV-var detection.
     */
    private List<IdpProvider> computeEnabled(VoltaConfig voltaConfig) {
        if (voltaConfig.hasIdpSection()) {
            List<IdpProvider> result = new ArrayList<>();
            for (VoltaConfig.IdpEntry entry : voltaConfig.idp()) {
                IdpProvider p = ALL_PROVIDERS.get(entry.id());
                if (p != null && entry.isEnabled()) result.add(p);
            }
            return List.copyOf(result);
        }
        // Fallback: ENV-based detection (backward compatible, no volta-config.yaml)
        List<IdpProvider> result = new ArrayList<>();
        for (IdpProvider p : ALL_PROVIDERS.values()) {
            if (p.isEnabled(config)) result.add(p);
        }
        return List.copyOf(result);
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
        String nonce    = idp.requiresNonce() ? SecurityUtils.randomUrlSafe(32) : null;
        String verifier = idp.requiresPkce()  ? SecurityUtils.randomUrlSafe(32) : null;

        String encryptedVerifier = (verifier != null && keyCipher != null)
                ? keyCipher.encrypt(verifier) : verifier;
        store.saveOidcFlow(new OidcFlowRecord(
                state, nonce, encryptedVerifier, returnTo, inviteCode,
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
        // Decrypt code_verifier if it was encrypted
        String codeVerifier = flow.codeVerifier();
        if (codeVerifier != null && keyCipher != null) {
            codeVerifier = keyCipher.decrypt(codeVerifier);
        }
        OidcFlowRecord decryptedFlow = new OidcFlowRecord(
                flow.state(), flow.nonce(), codeVerifier, flow.returnTo(),
                flow.inviteCode(), flow.expiresAt(), flow.provider());
        // Look up from ALL_PROVIDERS (not just enabled) to complete in-flight flows
        // that were started before a reload removed that provider.
        IdpProvider idp = ALL_PROVIDERS.get(flow.provider());
        if (idp == null) {
            throw new IllegalArgumentException("Unknown provider in flow: " + flow.provider());
        }
        return idp.exchange(code, decryptedFlow, config, http, mapper);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private IdpProvider resolveProvider(String id) {
        List<IdpProvider> enabled = enabledRef.get();
        if (id != null) {
            String upper = id.toUpperCase(java.util.Locale.ROOT);
            for (IdpProvider p : enabled) {
                if (p.id().equals(upper)) return p;
            }
        }
        if (!enabled.isEmpty()) return enabled.getFirst();
        throw new IllegalStateException(
                "No IdP configured. Add an idp: section to volta-config.yaml "
                + "or set GOOGLE_CLIENT_ID / GITHUB_CLIENT_ID / MICROSOFT_CLIENT_ID.");
    }
}
