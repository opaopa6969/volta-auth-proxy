package com.volta.authproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.JWTClaimsSetVerifier;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class OidcService {

    // --- Google ---
    private static final String GOOGLE_AUTH     = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN    = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_ISSUER_1 = "https://accounts.google.com";
    private static final String GOOGLE_ISSUER_2 = "accounts.google.com";
    private static final URI    GOOGLE_JWKS     = URI.create("https://www.googleapis.com/oauth2/v3/certs");

    // --- GitHub ---
    private static final String GITHUB_AUTH      = "https://github.com/login/oauth/authorize";
    private static final String GITHUB_TOKEN     = "https://github.com/login/oauth/access_token";
    private static final String GITHUB_USER_API  = "https://api.github.com/user";
    private static final String GITHUB_EMAIL_API = "https://api.github.com/user/emails";

    // --- Microsoft ---
    private static final String MICROSOFT_AUTH_TEMPLATE  = "https://login.microsoftonline.com/%s/oauth2/v2.0/authorize";
    private static final String MICROSOFT_TOKEN_TEMPLATE = "https://login.microsoftonline.com/%s/oauth2/v2.0/token";
    private static final String MICROSOFT_JWKS_TEMPLATE  = "https://login.microsoftonline.com/%s/discovery/v2.0/keys";
    private static final String MICROSOFT_ISSUER_PREFIX  = "https://login.microsoftonline.com/";

    private final AppConfig config;
    private final SqlStore store;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public OidcService(AppConfig config, SqlStore store) {
        this.config = config;
        this.store = store;
    }

    // -------------------------------------------------------------------------
    // Authorization URL
    // -------------------------------------------------------------------------

    public String createAuthorizationUrl(String returnTo, String inviteCode, String provider) {
        String state    = SecurityUtils.randomUrlSafe(32);
        String nonce    = null;
        String verifier = null;

        Map<String, String> params = new HashMap<>();
        params.put("response_type", "code");
        params.put("state", state);

        String authUrl;
        switch (provider) {
            case "GITHUB" -> {
                authUrl = GITHUB_AUTH;
                params.put("client_id", config.githubClientId());
                params.put("scope", "read:user user:email");
                // GitHub does not support PKCE or nonce
            }
            case "MICROSOFT" -> {
                authUrl = MICROSOFT_AUTH_TEMPLATE.formatted(config.microsoftTenantId());
                nonce    = SecurityUtils.randomUrlSafe(32);
                verifier = SecurityUtils.randomUrlSafe(32);
                params.put("client_id", config.microsoftClientId());
                params.put("redirect_uri", callbackUrl());
                params.put("scope", "openid email profile");
                params.put("nonce", nonce);
                params.put("code_challenge", SecurityUtils.pkceChallenge(verifier));
                params.put("code_challenge_method", "S256");
                params.put("prompt", "select_account");
            }
            default -> { // GOOGLE
                authUrl  = GOOGLE_AUTH;
                nonce    = SecurityUtils.randomUrlSafe(32);
                verifier = SecurityUtils.randomUrlSafe(32);
                params.put("client_id", config.googleClientId());
                params.put("redirect_uri", config.googleRedirectUri());
                params.put("scope", "openid email profile");
                params.put("nonce", nonce);
                params.put("code_challenge", SecurityUtils.pkceChallenge(verifier));
                params.put("code_challenge_method", "S256");
                params.put("prompt", "select_account");
            }
        }

        store.saveOidcFlow(new OidcFlowRecord(
                state, nonce, verifier, returnTo, inviteCode,
                Instant.now().plus(Duration.ofMinutes(10)),
                provider
        ));

        return authUrl + "?" + encode(params);
    }

    /** Backwards-compatible overload — defaults to GOOGLE. */
    public String createAuthorizationUrl(String returnTo, String inviteCode) {
        return createAuthorizationUrl(returnTo, inviteCode, "GOOGLE");
    }

    // -------------------------------------------------------------------------
    // Callback: exchange code → identity
    // -------------------------------------------------------------------------

    public OidcIdentity exchangeAndValidate(String code, String state) {
        OidcFlowRecord flow = store.consumeOidcFlow(state)
                .orElseThrow(() -> new IllegalArgumentException("Invalid state"));
        if (flow.expiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("State expired");
        }
        return switch (flow.provider()) {
            case "GITHUB"    -> exchangeGithub(code, flow);
            case "MICROSOFT" -> exchangeMicrosoft(code, flow);
            default          -> exchangeGoogle(code, flow);
        };
    }

    // -------------------------------------------------------------------------
    // Google
    // -------------------------------------------------------------------------

    private OidcIdentity exchangeGoogle(String code, OidcFlowRecord flow) {
        String body = "code=" + enc(code)
                + "&client_id=" + enc(config.googleClientId())
                + "&client_secret=" + enc(config.googleClientSecret())
                + "&redirect_uri=" + enc(config.googleRedirectUri())
                + "&grant_type=authorization_code"
                + "&code_verifier=" + enc(flow.codeVerifier());
        JsonNode json = postForm(GOOGLE_TOKEN, body);
        String idToken = json.path("id_token").asText();
        JWTClaimsSet claims = verifyIdToken(idToken, flow.nonce(), GOOGLE_JWKS,
                config.googleClientId(), GOOGLE_ISSUER_1, GOOGLE_ISSUER_2);
        return new OidcIdentity(
                "google:" + claims.getSubject(),
                (String) claims.getClaim("email"),
                (String) claims.getClaim("name"),
                Boolean.TRUE.equals(claims.getClaim("email_verified")),
                flow.returnTo(),
                flow.inviteCode(),
                "GOOGLE"
        );
    }

    // -------------------------------------------------------------------------
    // GitHub (OAuth2, no id_token)
    // -------------------------------------------------------------------------

    private OidcIdentity exchangeGithub(String code, OidcFlowRecord flow) {
        String tokenBody = "code=" + enc(code)
                + "&client_id=" + enc(config.githubClientId())
                + "&client_secret=" + enc(config.githubClientSecret());
        JsonNode tokenJson = postForm(GITHUB_TOKEN, tokenBody, "application/json");
        String accessToken = tokenJson.path("access_token").asText();
        if (accessToken.isBlank()) {
            throw new IllegalArgumentException("GitHub token exchange failed: "
                    + tokenJson.path("error_description").asText("unknown"));
        }

        JsonNode user = getJson(GITHUB_USER_API, accessToken);
        String githubId = user.path("id").asText();
        String login    = user.path("login").asText();
        String name     = user.path("name").asText(login);

        String email = user.path("email").asText(null);
        if (email == null || email.isBlank()) {
            email = fetchGithubPrimaryEmail(accessToken);
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("GitHub account has no verified public email. "
                    + "Please set a public email in GitHub profile settings.");
        }

        return new OidcIdentity(
                "github:" + githubId,
                email,
                name,
                true, // GitHub only exposes verified emails
                flow.returnTo(),
                flow.inviteCode(),
                "GITHUB"
        );
    }

    private String fetchGithubPrimaryEmail(String accessToken) {
        try {
            JsonNode emails = getJson(GITHUB_EMAIL_API, accessToken);
            for (JsonNode e : emails) {
                if (e.path("primary").asBoolean() && e.path("verified").asBoolean()) {
                    return e.path("email").asText(null);
                }
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Microsoft (OIDC)
    // -------------------------------------------------------------------------

    private OidcIdentity exchangeMicrosoft(String code, OidcFlowRecord flow) {
        String tid      = config.microsoftTenantId();
        String tokenUrl = MICROSOFT_TOKEN_TEMPLATE.formatted(tid);
        String body = "code=" + enc(code)
                + "&client_id=" + enc(config.microsoftClientId())
                + "&client_secret=" + enc(config.microsoftClientSecret())
                + "&redirect_uri=" + enc(callbackUrl())
                + "&grant_type=authorization_code"
                + "&code_verifier=" + enc(flow.codeVerifier());
        JsonNode json    = postForm(tokenUrl, body);
        String idToken   = json.path("id_token").asText();
        URI jwksUri      = URI.create(MICROSOFT_JWKS_TEMPLATE.formatted(tid));
        JWTClaimsSet cls = verifyIdTokenMicrosoft(idToken, flow.nonce(), jwksUri, config.microsoftClientId());

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
                    "MICROSOFT"
            );
        } catch (ParseException e) {
            throw new IllegalArgumentException("Failed to read Microsoft claims", e);
        }
    }

    // -------------------------------------------------------------------------
    // JWT verification
    // -------------------------------------------------------------------------

    private JWTClaimsSet verifyIdToken(String idToken, String expectedNonce,
            URI jwksUri, String expectedAudience,
            String validIssuer1, String validIssuer2) {
        try {
            ConfigurableJWTProcessor<SecurityContext> proc = new DefaultJWTProcessor<>();
            JWKSource<SecurityContext> keys = new RemoteJWKSet<>(jwksUri.toURL());
            proc.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
            proc.setJWTClaimsSetVerifier((JWTClaimsSetVerifier<SecurityContext>) (claims, ctx) -> {
                String issuer = claims.getIssuer();
                if (!validIssuer1.equals(issuer) && (validIssuer2 == null || !validIssuer2.equals(issuer))) {
                    throw new IllegalArgumentException("Invalid issuer: " + issuer);
                }
                if (!claims.getAudience().contains(expectedAudience)) {
                    throw new IllegalArgumentException("Invalid audience");
                }
                if (claims.getExpirationTime() == null || claims.getExpirationTime().toInstant().isBefore(Instant.now())) {
                    throw new IllegalArgumentException("Token expired");
                }
                String nonce = (String) claims.getClaim("nonce");
                if (nonce == null || !nonce.equals(expectedNonce)) {
                    throw new IllegalArgumentException("Invalid nonce");
                }
                if (!Boolean.TRUE.equals(claims.getClaim("email_verified"))) {
                    throw new IllegalArgumentException("Email not verified");
                }
            });
            return proc.process(idToken, null);
        } catch (JOSEException | BadJOSEException | ParseException | java.net.MalformedURLException e) {
            throw new IllegalArgumentException("Invalid id_token", e);
        }
    }

    private JWTClaimsSet verifyIdTokenMicrosoft(String idToken, String expectedNonce,
            URI jwksUri, String expectedAudience) {
        try {
            ConfigurableJWTProcessor<SecurityContext> proc = new DefaultJWTProcessor<>();
            JWKSource<SecurityContext> keys = new RemoteJWKSet<>(jwksUri.toURL());
            proc.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
            proc.setJWTClaimsSetVerifier((JWTClaimsSetVerifier<SecurityContext>) (claims, ctx) -> {
                // Issuer varies per tenant: https://login.microsoftonline.com/{tid}/v2.0
                String issuer = claims.getIssuer();
                if (issuer == null || !issuer.startsWith(MICROSOFT_ISSUER_PREFIX)) {
                    throw new IllegalArgumentException("Invalid Microsoft issuer: " + issuer);
                }
                if (!claims.getAudience().contains(expectedAudience)) {
                    throw new IllegalArgumentException("Invalid audience");
                }
                if (claims.getExpirationTime() == null || claims.getExpirationTime().toInstant().isBefore(Instant.now())) {
                    throw new IllegalArgumentException("Token expired");
                }
                String nonce = (String) claims.getClaim("nonce");
                if (nonce == null || !nonce.equals(expectedNonce)) {
                    throw new IllegalArgumentException("Invalid nonce");
                }
            });
            return proc.process(idToken, null);
        } catch (JOSEException | BadJOSEException | ParseException | java.net.MalformedURLException e) {
            throw new IllegalArgumentException("Invalid Microsoft id_token", e);
        }
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    private JsonNode postForm(String url, String body) {
        return postForm(url, body, null);
    }

    private JsonNode postForm(String url, String body, String acceptOverride) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", acceptOverride != null ? acceptOverride : "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalArgumentException("Token exchange failed: " + resp.statusCode() + " " + resp.body());
            }
            return mapper.readTree(resp.body());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode getJson(String url, String bearerToken) {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Bearer " + bearerToken)
                            .header("Accept", "application/json")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalArgumentException("API call failed: " + resp.statusCode());
            }
            return mapper.readTree(resp.body());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private String callbackUrl() {
        return config.baseUrl() + "/callback";
    }

    private static String encode(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sb.append("&");
            first = false;
            sb.append(enc(e.getKey())).append("=").append(enc(e.getValue()));
        }
        return sb.toString();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    public record OidcIdentity(
            String sub,           // "google:<sub>" | "github:<id>" | "microsoft:<sub>"
            String email,
            String displayName,
            boolean emailVerified,
            String returnTo,
            String inviteCode,
            String provider       // GOOGLE | GITHUB | MICROSOFT
    ) {
        /** Backwards-compatible alias used by Main.java. */
        public String googleSub() { return sub; }
    }
}
