package com.volta.authproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
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
import java.util.Map;

/**
 * Abstract base class for {@link IdpProvider} implementations.
 *
 * <p>Provides shared utilities:
 * <ul>
 *   <li>URL encoding helpers</li>
 *   <li>HTTP form POST and GET JSON</li>
 *   <li>OIDC id_token verification via JWKS</li>
 * </ul>
 */
public abstract class BaseIdpProvider implements IdpProvider {

    // -------------------------------------------------------------------------
    // URL helpers
    // -------------------------------------------------------------------------

    protected static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    protected static String buildQueryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sb.append("&");
            first = false;
            sb.append(enc(e.getKey())).append("=").append(enc(e.getValue()));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    protected static JsonNode postForm(HttpClient http, ObjectMapper mapper,
            String url, String body) {
        return postForm(http, mapper, url, body, "application/json");
    }

    protected static JsonNode postForm(HttpClient http, ObjectMapper mapper,
            String url, String body, String accept) {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(15))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Accept", accept)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalArgumentException(
                        "Token exchange failed [" + resp.statusCode() + "]: " + resp.body());
            }
            return mapper.readTree(resp.body());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("HTTP request failed: " + url, e);
        }
    }

    protected static JsonNode getJson(HttpClient http, ObjectMapper mapper,
            String url, String bearerToken) {
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
                throw new IllegalArgumentException(
                        "API call failed [" + resp.statusCode() + "]: " + url);
            }
            return mapper.readTree(resp.body());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("HTTP request failed: " + url, e);
        }
    }

    // -------------------------------------------------------------------------
    // OIDC id_token verification
    // -------------------------------------------------------------------------

    /**
     * Verify a standard OIDC id_token via JWKS.
     *
     * @param idToken       raw JWT string
     * @param expectedNonce nonce that must appear in the token claims
     * @param jwksUri       JWKS endpoint URL
     * @param audience      expected audience (client_id)
     * @param validIssuers  one or more accepted issuer values
     */
    protected static JWTClaimsSet verifyIdToken(
            String idToken,
            String expectedNonce,
            URI jwksUri,
            String audience,
            String... validIssuers) {
        try {
            ConfigurableJWTProcessor<SecurityContext> proc = new DefaultJWTProcessor<>();
            proc.setJWSKeySelector(
                    new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
                            new RemoteJWKSet<>(jwksUri.toURL())));
            proc.setJWTClaimsSetVerifier((JWTClaimsSetVerifier<SecurityContext>) (claims, ctx) -> {
                validateIssuer(claims.getIssuer(), validIssuers);
                if (!claims.getAudience().contains(audience)) {
                    throw new IllegalArgumentException("Invalid audience");
                }
                if (claims.getExpirationTime() == null
                        || claims.getExpirationTime().toInstant().isBefore(Instant.now())) {
                    throw new IllegalArgumentException("Token expired");
                }
                String nonce = (String) claims.getClaim("nonce");
                if (nonce == null || !nonce.equals(expectedNonce)) {
                    throw new IllegalArgumentException("Invalid nonce");
                }
            });
            return proc.process(idToken, null);
        } catch (JOSEException | BadJOSEException | ParseException
                 | java.net.MalformedURLException e) {
            throw new IllegalArgumentException("Invalid id_token: " + e.getMessage(), e);
        }
    }

    /**
     * Verify an OIDC id_token where the issuer must start with a known prefix
     * (e.g. Microsoft's per-tenant issuers).
     */
    protected static JWTClaimsSet verifyIdTokenByIssuerPrefix(
            String idToken,
            String expectedNonce,
            URI jwksUri,
            String audience,
            String issuerPrefix) {
        try {
            ConfigurableJWTProcessor<SecurityContext> proc = new DefaultJWTProcessor<>();
            proc.setJWSKeySelector(
                    new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
                            new RemoteJWKSet<>(jwksUri.toURL())));
            proc.setJWTClaimsSetVerifier((JWTClaimsSetVerifier<SecurityContext>) (claims, ctx) -> {
                String issuer = claims.getIssuer();
                if (issuer == null || !issuer.startsWith(issuerPrefix)) {
                    throw new IllegalArgumentException("Invalid issuer: " + issuer);
                }
                if (!claims.getAudience().contains(audience)) {
                    throw new IllegalArgumentException("Invalid audience");
                }
                if (claims.getExpirationTime() == null
                        || claims.getExpirationTime().toInstant().isBefore(Instant.now())) {
                    throw new IllegalArgumentException("Token expired");
                }
                String nonce = (String) claims.getClaim("nonce");
                if (nonce == null || !nonce.equals(expectedNonce)) {
                    throw new IllegalArgumentException("Invalid nonce");
                }
            });
            return proc.process(idToken, null);
        } catch (JOSEException | BadJOSEException | ParseException
                 | java.net.MalformedURLException e) {
            throw new IllegalArgumentException("Invalid id_token: " + e.getMessage(), e);
        }
    }

    private static void validateIssuer(String actual, String[] valid) {
        for (String v : valid) {
            if (v.equals(actual)) return;
        }
        throw new IllegalArgumentException("Invalid issuer: " + actual);
    }
}
