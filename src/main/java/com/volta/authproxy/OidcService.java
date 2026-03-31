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
    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String ISSUER_1 = "https://accounts.google.com";
    private static final String ISSUER_2 = "accounts.google.com";
    private static final URI GOOGLE_JWKS = URI.create("https://www.googleapis.com/oauth2/v3/certs");

    private final AppConfig config;
    private final SqlStore store;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OidcService(AppConfig config, SqlStore store) {
        this.config = config;
        this.store = store;
    }

    public String createAuthorizationUrl(String returnTo, String inviteCode) {
        String state = SecurityUtils.randomUrlSafe(32);
        String nonce = SecurityUtils.randomUrlSafe(32);
        String verifier = SecurityUtils.randomUrlSafe(32);
        String challenge = SecurityUtils.pkceChallenge(verifier);
        store.saveOidcFlow(new OidcFlowRecord(
                state,
                nonce,
                verifier,
                returnTo,
                inviteCode,
                Instant.now().plus(Duration.ofMinutes(10))
        ));

        Map<String, String> params = new HashMap<>();
        params.put("response_type", "code");
        params.put("client_id", config.googleClientId());
        params.put("redirect_uri", config.googleRedirectUri());
        params.put("scope", "openid email profile");
        params.put("state", state);
        params.put("nonce", nonce);
        params.put("code_challenge", challenge);
        params.put("code_challenge_method", "S256");
        params.put("prompt", "select_account");
        return AUTH_ENDPOINT + "?" + encode(params);
    }

    public OidcIdentity exchangeAndValidate(String code, String state) {
        OidcFlowRecord flow = store.consumeOidcFlow(state).orElseThrow(() -> new IllegalArgumentException("Invalid state"));
        if (flow.expiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("State expired");
        }
        String idToken = exchangeCode(code, flow.codeVerifier());
        JWTClaimsSet claims = verifyGoogleIdToken(idToken, flow.nonce());
        return new OidcIdentity(
                claims.getSubject(),
                (String) claims.getClaim("email"),
                (String) claims.getClaim("name"),
                Boolean.TRUE.equals(claims.getClaim("email_verified")),
                flow.returnTo(),
                flow.inviteCode()
        );
    }

    private String exchangeCode(String code, String codeVerifier) {
        try {
            String body = "code=" + enc(code)
                    + "&client_id=" + enc(config.googleClientId())
                    + "&client_secret=" + enc(config.googleClientSecret())
                    + "&redirect_uri=" + enc(config.googleRedirectUri())
                    + "&grant_type=authorization_code"
                    + "&code_verifier=" + enc(codeVerifier);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_ENDPOINT))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalArgumentException("Token exchange failed: " + response.statusCode());
            }
            JsonNode json = objectMapper.readTree(response.body());
            return json.path("id_token").asText();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private JWTClaimsSet verifyGoogleIdToken(String idToken, String expectedNonce) {
        try {
            ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            JWKSource<SecurityContext> keySource = new RemoteJWKSet<>(GOOGLE_JWKS.toURL());
            JWSKeySelector<SecurityContext> keySelector = new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);
            processor.setJWSKeySelector(keySelector);
            processor.setJWTClaimsSetVerifier((JWTClaimsSetVerifier<SecurityContext>) (claims, context) -> {
                String issuer = claims.getIssuer();
                if (!ISSUER_1.equals(issuer) && !ISSUER_2.equals(issuer)) {
                    throw new IllegalArgumentException("Invalid issuer");
                }
                if (!claims.getAudience().contains(config.googleClientId())) {
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
            return processor.process(idToken, null);
        } catch (JOSEException | BadJOSEException | ParseException | java.net.MalformedURLException e) {
            throw new IllegalArgumentException("Invalid id_token", e);
        }
    }

    private static String encode(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                sb.append("&");
            }
            first = false;
            sb.append(enc(e.getKey())).append("=").append(enc(e.getValue()));
        }
        return sb.toString();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record OidcIdentity(
            String googleSub,
            String email,
            String displayName,
            boolean emailVerified,
            String returnTo,
            String inviteCode
    ) {
    }
}
