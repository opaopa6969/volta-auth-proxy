package com.volta.authproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for fraud-alert API.
 * Calls /c/checkOnly for risk scoring, /c/loginSucceed and /c/loginFailed for feedback.
 *
 * <p>Fail-open: if the API is unreachable or times out, returns riskLevel = 1 (safe).
 */
public final class FraudAlertClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(FraudAlertClient.class);

    private final String baseUrl;
    private final String siteId;
    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public FraudAlertClient(AppConfig config, ObjectMapper mapper) {
        this.baseUrl = config.fraudAlertUrl();
        this.siteId = config.fraudAlertSiteId();
        this.apiKey = config.fraudAlertApiKey();
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.mapper = mapper;
    }

    /** Returns true if fraud-alert is configured (URL is non-empty). */
    public boolean isEnabled() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    /**
     * Call /c/checkOnly to get risk score.
     * Fail-open: returns RiskResult(1, 0, empty, false) on any failure.
     */
    public RiskResult checkOnly(UUID userId, UUID tenantId, String sessionId,
                                String ipAddress, String userAgent) {
        if (!isEnabled()) return RiskResult.SAFE;

        try {
            String userHash = computeUserHash(tenantId, userId);
            String url = baseUrl + "/c/checkOnly?" + buildQuery(Map.of(
                    "siteId", siteId,
                    "sessionId", sessionId != null ? sessionId : "",
                    "userHash", userHash,
                    "ip", ipAddress != null ? ipAddress : "",
                    "ua", userAgent != null ? userAgent : ""
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .header("X-API-Key", apiKey)
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("fraud-alert returned {}: {}", response.statusCode(), response.body());
                return RiskResult.SAFE;
            }

            JsonNode json = mapper.readTree(response.body());
            int relative = json.path("relativeSuspiciousValue").asInt(1);
            int total = json.path("totalSuspiciousValue").asInt(0);

            Map<String, Integer> byKind = new LinkedHashMap<>();
            JsonNode kindsNode = json.path("suspiciousByKind");
            if (kindsNode.isObject()) {
                kindsNode.fields().forEachRemaining(e ->
                        byKind.put(e.getKey(), e.getValue().asInt(0)));
            }

            return new RiskResult(relative, total, byKind, relative >= 5);

        } catch (Exception e) {
            log.warn("fraud-alert check failed (fail-open): {}", e.getMessage());
            return RiskResult.SAFE;
        }
    }

    /**
     * Report successful login for device learning. Fire-and-forget.
     */
    public void reportLoginSucceed(UUID userId, UUID tenantId, String sessionId,
                                   String ipAddress, String userAgent) {
        if (!isEnabled()) return;
        fireAndForget("/c/loginSucceed", userId, tenantId, sessionId, ipAddress, userAgent);
    }

    /**
     * Report failed login for pattern learning. Fire-and-forget.
     */
    public void reportLoginFailed(UUID userId, UUID tenantId, String sessionId,
                                  String ipAddress, String userAgent) {
        if (!isEnabled()) return;
        fireAndForget("/c/loginFailed", userId, tenantId, sessionId, ipAddress, userAgent);
    }

    private void fireAndForget(String path, UUID userId, UUID tenantId,
                               String sessionId, String ipAddress, String userAgent) {
        Thread.startVirtualThread(() -> {
            try {
                String userHash = computeUserHash(tenantId, userId);
                String url = baseUrl + path + "?" + buildQuery(Map.of(
                        "siteId", siteId,
                        "sessionId", sessionId != null ? sessionId : "",
                        "userHash", userHash,
                        "ip", ipAddress != null ? ipAddress : "",
                        "ua", userAgent != null ? userAgent : ""
                ));

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .header("X-API-Key", apiKey)
                        .GET()
                        .build();

                http.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                log.debug("fraud-alert {} report failed: {}", path, e.getMessage());
            }
        });
    }

    /**
     * userHash = SHA256(tenantId:userId) — tenant isolation per spec.
     */
    static String computeUserHash(UUID tenantId, UUID userId) {
        try {
            String input = tenantId.toString() + ":" + userId.toString();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }

    private static String buildQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (var entry : params.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    // ─── Result ─────────────────────────────────────────────

    public record RiskResult(
            int relativeSuspiciousValue,
            int totalSuspiciousValue,
            Map<String, Integer> suspiciousByKind,
            boolean blocked
    ) {
        public static final RiskResult SAFE = new RiskResult(1, 0, Map.of(), false);
    }
}
