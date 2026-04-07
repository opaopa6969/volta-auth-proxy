package org.unlaxer.infra.volta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;

import java.net.URI;
import java.net.http.HttpClient;
import java.text.ParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Microsoft / Azure AD OIDC provider (OpenID Connect + PKCE).
 *
 * <p>Supports both multi-tenant ({@code MICROSOFT_TENANT_ID=common}) and
 * single-tenant configurations. The issuer URL varies per user tenant, so
 * validation checks the issuer prefix rather than an exact value.
 *
 * <p>Required ENV: {@code MICROSOFT_CLIENT_ID}, {@code MICROSOFT_CLIENT_SECRET}
 * <br>Optional ENV: {@code MICROSOFT_TENANT_ID} (default: {@code common})
 */
public final class MicrosoftIdp extends BaseIdpProvider {

    private static final String AUTH_TEMPLATE  = "https://login.microsoftonline.com/%s/oauth2/v2.0/authorize";
    private static final String TOKEN_TEMPLATE = "https://login.microsoftonline.com/%s/oauth2/v2.0/token";
    private static final String JWKS_TEMPLATE  = "https://login.microsoftonline.com/%s/discovery/v2.0/keys";
    private static final String ISSUER_PREFIX  = "https://login.microsoftonline.com/";

    @Override public String id()    { return "MICROSOFT"; }
    @Override public String label() { return "Microsoft"; }

    @Override
    public boolean isEnabled(AppConfig config) {
        return config.isMicrosoftEnabled();
    }

    @Override
    public String buildAuthorizationUrl(String state, String nonce, String verifier, AppConfig config) {
        String tid = config.microsoftTenantId();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id",     config.microsoftClientId());
        params.put("redirect_uri",  callbackUrl(config));
        params.put("scope",         "openid email profile");
        params.put("state",         state);
        params.put("nonce",         nonce);
        params.put("code_challenge",        SecurityUtils.pkceChallenge(verifier));
        params.put("code_challenge_method", "S256");
        params.put("prompt",        "select_account");
        return AUTH_TEMPLATE.formatted(tid) + "?" + buildQueryString(params);
    }

    @Override
    public OidcIdentity exchange(String code, OidcFlowRecord flow,
            AppConfig config, HttpClient http, ObjectMapper mapper) {
        String tid      = config.microsoftTenantId();
        String tokenUrl = TOKEN_TEMPLATE.formatted(tid);

        String body = "code="          + enc(code)
                + "&client_id="     + enc(config.microsoftClientId())
                + "&client_secret=" + enc(config.microsoftClientSecret())
                + "&redirect_uri="  + enc(callbackUrl(config))
                + "&grant_type=authorization_code"
                + "&code_verifier=" + enc(flow.codeVerifier());

        JsonNode json  = postForm(http, mapper, tokenUrl, body);
        String idToken = json.path("id_token").asText();
        URI jwksUri    = URI.create(JWKS_TEMPLATE.formatted(tid));

        JWTClaimsSet cls = verifyIdTokenByIssuerPrefix(
                idToken, flow.nonce(), jwksUri, config.microsoftClientId(), ISSUER_PREFIX);

        try {
            String email = cls.getStringClaim("email");
            if (email == null || email.isBlank()) {
                email = cls.getStringClaim("preferred_username");
            }
            return new OidcIdentity(
                    "microsoft:" + cls.getSubject(),
                    email,
                    cls.getStringClaim("name"),
                    true,
                    flow.returnTo(),
                    flow.inviteCode(),
                    id()
            );
        } catch (ParseException e) {
            throw new IllegalArgumentException("Failed to read Microsoft token claims", e);
        }
    }

    private static String callbackUrl(AppConfig config) {
        return config.baseUrl() + "/callback";
    }
}
