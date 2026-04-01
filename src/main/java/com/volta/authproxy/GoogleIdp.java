package com.volta.authproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Google OIDC provider (OpenID Connect + PKCE).
 *
 * <p>Required ENV: {@code GOOGLE_CLIENT_ID}, {@code GOOGLE_CLIENT_SECRET}, {@code GOOGLE_REDIRECT_URI}
 */
public final class GoogleIdp extends BaseIdpProvider {

    private static final String AUTH_URL  = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final URI    JWKS_URI  = URI.create("https://www.googleapis.com/oauth2/v3/certs");
    private static final String ISSUER_1  = "https://accounts.google.com";
    private static final String ISSUER_2  = "accounts.google.com";

    @Override public String id()    { return "GOOGLE"; }
    @Override public String label() { return "Google"; }

    @Override
    public boolean isEnabled(AppConfig config) {
        return config.isGoogleEnabled();
    }

    @Override
    public String buildAuthorizationUrl(String state, String nonce, String verifier, AppConfig config) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id",     config.googleClientId());
        params.put("redirect_uri",  config.googleRedirectUri());
        params.put("scope",         "openid email profile");
        params.put("state",         state);
        params.put("nonce",         nonce);
        params.put("code_challenge",        SecurityUtils.pkceChallenge(verifier));
        params.put("code_challenge_method", "S256");
        params.put("prompt",        "select_account");
        return AUTH_URL + "?" + buildQueryString(params);
    }

    @Override
    public OidcIdentity exchange(String code, OidcFlowRecord flow,
            AppConfig config, HttpClient http, ObjectMapper mapper) {
        String body = "code=" + enc(code)
                + "&client_id="     + enc(config.googleClientId())
                + "&client_secret=" + enc(config.googleClientSecret())
                + "&redirect_uri="  + enc(config.googleRedirectUri())
                + "&grant_type=authorization_code"
                + "&code_verifier=" + enc(flow.codeVerifier());

        JsonNode json    = postForm(http, mapper, TOKEN_URL, body);
        String idToken   = json.path("id_token").asText();
        JWTClaimsSet cls = verifyIdToken(idToken, flow.nonce(), JWKS_URI,
                config.googleClientId(), ISSUER_1, ISSUER_2);

        return new OidcIdentity(
                "google:" + cls.getSubject(),
                (String) cls.getClaim("email"),
                (String) cls.getClaim("name"),
                Boolean.TRUE.equals(cls.getClaim("email_verified")),
                flow.returnTo(),
                flow.inviteCode(),
                id()
        );
    }
}
