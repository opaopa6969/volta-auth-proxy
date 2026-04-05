package com.volta.authproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LinkedIn OAuth2 provider (OpenID Connect).
 *
 * <p>Required ENV: {@code LINKEDIN_CLIENT_ID}, {@code LINKEDIN_CLIENT_SECRET}
 */
public final class LinkedInIdp extends BaseIdpProvider {

    private static final String AUTH_URL  = "https://www.linkedin.com/oauth/v2/authorization";
    private static final String TOKEN_URL = "https://www.linkedin.com/oauth/v2/accessToken";
    private static final String USERINFO  = "https://api.linkedin.com/v2/userinfo";

    @Override public String id()    { return "LINKEDIN"; }
    @Override public String label() { return "LinkedIn"; }

    @Override public boolean requiresNonce() { return false; }
    @Override public boolean requiresPkce()  { return false; }

    @Override
    public boolean isEnabled(AppConfig config) {
        return config.linkedinClientId() != null && !config.linkedinClientId().isBlank();
    }

    @Override
    public String buildAuthorizationUrl(String state, String nonce, String verifier, AppConfig config) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id",     config.linkedinClientId());
        params.put("redirect_uri",  config.baseUrl() + "/callback");
        params.put("scope",         "openid profile email");
        params.put("state",         state);
        return AUTH_URL + "?" + buildQueryString(params);
    }

    @Override
    public OidcIdentity exchange(String code, OidcFlowRecord flow,
            AppConfig config, HttpClient http, ObjectMapper mapper) {
        String body = "code=" + enc(code)
                + "&client_id="     + enc(config.linkedinClientId())
                + "&client_secret=" + enc(config.linkedinClientSecret())
                + "&redirect_uri="  + enc(config.baseUrl() + "/callback")
                + "&grant_type=authorization_code";

        JsonNode tokenJson = postForm(http, mapper, TOKEN_URL, body);
        String accessToken = tokenJson.path("access_token").asText();

        // Fetch user info
        try {
            HttpRequest req = HttpRequest.newBuilder(java.net.URI.create(USERINFO))
                    .header("Authorization", "Bearer " + accessToken)
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode userInfo = mapper.readTree(resp.body());

            return new OidcIdentity(
                    "linkedin:" + userInfo.path("sub").asText(),
                    userInfo.path("email").asText(null),
                    userInfo.path("name").asText(null),
                    userInfo.path("email_verified").asBoolean(false),
                    flow.returnTo(),
                    flow.inviteCode(),
                    id()
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("LinkedIn userinfo failed: " + e.getMessage(), e);
        }
    }
}
