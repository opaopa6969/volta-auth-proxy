package com.volta.authproxy;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;

/**
 * Plugin interface for OAuth2/OIDC identity providers.
 *
 * <p>To add a new provider:
 * <ol>
 *   <li>Implement this interface (extend {@link BaseIdpProvider} for HTTP/JWT utilities)</li>
 *   <li>Add credentials to {@link AppConfig}</li>
 *   <li>Register in {@link OidcService#buildRegistry}</li>
 *   <li>Add a button to {@code auth/login.jte}</li>
 * </ol>
 *
 * @see GoogleIdp
 * @see GitHubIdp
 * @see MicrosoftIdp
 */
public interface IdpProvider {

    /** Unique uppercase identifier stored in {@code oidc_flows.provider}. e.g. "GOOGLE" */
    String id();

    /** Human-readable label shown on the login button. e.g. "Google" */
    String label();

    /** Returns true when the required credentials are present in config. */
    boolean isEnabled(AppConfig config);

    /**
     * Whether this provider uses a nonce embedded in the id_token (OIDC only).
     * GitHub OAuth2 does not issue an id_token, so returns false.
     */
    default boolean requiresNonce() { return true; }

    /**
     * Whether this provider supports PKCE (code_challenge / code_verifier).
     * GitHub OAuth2 does not support PKCE, so returns false.
     */
    default boolean requiresPkce() { return true; }

    /**
     * Build the authorization redirect URL.
     *
     * @param state    random CSRF state token
     * @param nonce    nonce for id_token validation (null when {@link #requiresNonce()} is false)
     * @param verifier PKCE code verifier (null when {@link #requiresPkce()} is false)
     * @param config   application configuration
     * @return complete authorization URL including all query parameters
     */
    String buildAuthorizationUrl(String state, String nonce, String verifier, AppConfig config);

    /**
     * Exchange an authorization code for a verified identity.
     *
     * @param code   authorization code received from the IdP callback
     * @param flow   the saved OIDC flow (contains nonce, codeVerifier, returnTo, inviteCode)
     * @param config application configuration
     * @param http   shared HTTP client
     * @param mapper shared JSON mapper
     * @return verified {@link OidcIdentity}
     * @throws IllegalArgumentException if the code or token is invalid
     */
    OidcIdentity exchange(String code, OidcFlowRecord flow, AppConfig config,
                          HttpClient http, ObjectMapper mapper);
}
