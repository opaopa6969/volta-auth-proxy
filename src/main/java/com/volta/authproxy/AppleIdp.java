package com.volta.authproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Apple Sign In provider (OIDC).
 *
 * <p>Required ENV: {@code APPLE_CLIENT_ID}, {@code APPLE_TEAM_ID}, {@code APPLE_KEY_ID}, {@code APPLE_PRIVATE_KEY}
 *
 * <p>Note: Apple returns user info (name, email) only on the FIRST authorization.
 * Subsequent logins only return the sub claim in the id_token.
 */
public final class AppleIdp extends BaseIdpProvider {

    private static final String AUTH_URL  = "https://appleid.apple.com/auth/authorize";
    private static final String TOKEN_URL = "https://appleid.apple.com/auth/token";
    private static final URI    JWKS_URI  = URI.create("https://appleid.apple.com/auth/keys");
    private static final String ISSUER    = "https://appleid.apple.com";

    @Override public String id()    { return "APPLE"; }
    @Override public String label() { return "Apple"; }

    @Override
    public boolean isEnabled(AppConfig config) {
        return config.appleClientId() != null && !config.appleClientId().isBlank();
    }

    @Override
    public String buildAuthorizationUrl(String state, String nonce, String verifier, AppConfig config) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code id_token");
        params.put("response_mode", "form_post");
        params.put("client_id",     config.appleClientId());
        params.put("redirect_uri",  config.baseUrl() + "/callback");
        params.put("scope",         "name email");
        params.put("state",         state);
        params.put("nonce",         nonce);
        return AUTH_URL + "?" + buildQueryString(params);
    }

    @Override public boolean requiresPkce() { return false; }

    @Override
    public OidcIdentity exchange(String code, OidcFlowRecord flow,
            AppConfig config, HttpClient http, ObjectMapper mapper) {
        // Apple requires a client_secret JWT signed with your private key
        // For now, use the standard code exchange with client_secret
        String body = "code=" + enc(code)
                + "&client_id="     + enc(config.appleClientId())
                + "&client_secret=" + enc(config.appleClientSecret())
                + "&redirect_uri="  + enc(config.baseUrl() + "/callback")
                + "&grant_type=authorization_code";

        JsonNode json  = postForm(http, mapper, TOKEN_URL, body);
        String idToken = json.path("id_token").asText();
        JWTClaimsSet cls = verifyIdToken(idToken, flow.nonce(), JWKS_URI,
                config.appleClientId(), ISSUER);

        return new OidcIdentity(
                "apple:" + cls.getSubject(),
                (String) cls.getClaim("email"),
                null,  // Apple only returns name on first auth (via form_post user object)
                Boolean.TRUE.equals(cls.getClaim("email_verified")),
                flow.returnTo(),
                flow.inviteCode(),
                id()
        );
    }
}
