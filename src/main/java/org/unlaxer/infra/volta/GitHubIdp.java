package org.unlaxer.infra.volta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GitHub OAuth2 provider.
 *
 * <p>GitHub is OAuth2 only (no OIDC id_token, no PKCE, no nonce).
 * User identity is fetched from the GitHub REST API after token exchange.
 *
 * <p>Required ENV: {@code GITHUB_CLIENT_ID}, {@code GITHUB_CLIENT_SECRET}
 */
public final class GitHubIdp extends BaseIdpProvider {

    private static final String AUTH_URL      = "https://github.com/login/oauth/authorize";
    private static final String TOKEN_URL     = "https://github.com/login/oauth/access_token";
    private static final String USER_API      = "https://api.github.com/user";
    private static final String EMAIL_API     = "https://api.github.com/user/emails";

    @Override public String id()    { return "GITHUB"; }
    @Override public String label() { return "GitHub"; }

    @Override
    public boolean isEnabled(AppConfig config) {
        return config.isGithubEnabled();
    }

    /** GitHub does not issue an id_token, so no nonce is needed. */
    @Override public boolean requiresNonce() { return false; }

    /** GitHub does not support PKCE. */
    @Override public boolean requiresPkce()  { return false; }

    @Override
    public String buildAuthorizationUrl(String state, String nonce, String verifier, AppConfig config) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id",     config.githubClientId());
        params.put("scope",         "read:user user:email");
        params.put("state",         state);
        return AUTH_URL + "?" + buildQueryString(params);
    }

    @Override
    public OidcIdentity exchange(String code, OidcFlowRecord flow,
            AppConfig config, HttpClient http, ObjectMapper mapper) {
        // 1. Exchange code for access_token
        String tokenBody = "code="          + enc(code)
                + "&client_id="     + enc(config.githubClientId())
                + "&client_secret=" + enc(config.githubClientSecret());
        JsonNode tokenJson  = postForm(http, mapper, TOKEN_URL, tokenBody, "application/json");
        String accessToken  = tokenJson.path("access_token").asText();
        if (accessToken.isBlank()) {
            throw new IllegalArgumentException("GitHub token exchange failed: "
                    + tokenJson.path("error_description").asText("unknown"));
        }

        // 2. Fetch user profile
        JsonNode user   = getJson(http, mapper, USER_API, accessToken);
        String githubId = user.path("id").asText();
        String login    = user.path("login").asText();
        String name     = user.path("name").asText(login);

        // 3. Resolve email — profile field may be null if set to private
        String email = user.path("email").asText(null);
        if (email == null || email.isBlank()) {
            email = fetchPrimaryEmail(http, mapper, accessToken);
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException(
                    "GitHub account has no verified email. "
                    + "Please set a public email in GitHub profile settings.");
        }

        return new OidcIdentity(
                "github:" + githubId,
                email,
                name,
                true, // GitHub only exposes verified emails via the API
                flow.returnTo(),
                flow.inviteCode(),
                id()
        );
    }

    private String fetchPrimaryEmail(HttpClient http, ObjectMapper mapper, String accessToken) {
        try {
            JsonNode emails = getJson(http, mapper, EMAIL_API, accessToken);
            for (JsonNode e : emails) {
                if (e.path("primary").asBoolean() && e.path("verified").asBoolean()) {
                    return e.path("email").asText(null);
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return null;
    }
}
