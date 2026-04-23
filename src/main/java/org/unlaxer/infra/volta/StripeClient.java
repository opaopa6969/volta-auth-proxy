package org.unlaxer.infra.volta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SAAS-008: minimal Stripe HTTP client.
 *
 * <p>Intentionally avoids {@code com.stripe:stripe-java} to keep the
 * dependency footprint small. We only need two APIs for the Volta
 * self-service billing flow:
 *
 * <ul>
 *   <li>{@code POST /v1/checkout/sessions} — create a Checkout URL the
 *       user visits to subscribe / upgrade.</li>
 *   <li>{@code POST /v1/billing_portal/sessions} — create a one-time
 *       URL to the Stripe Customer Portal for subscription management.</li>
 * </ul>
 *
 * <p>Both return short-lived URLs the client redirects to. The webhook
 * handler (existing in {@code ApiRouter}) is the source of truth for
 * state transitions — this client is purely for "start a flow" calls.
 */
public final class StripeClient {

    private static final String API = "https://api.stripe.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String secretKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT).build();

    public StripeClient(String secretKey) {
        this.secretKey = secretKey == null ? "" : secretKey;
    }

    public boolean isConfigured() {
        return !secretKey.isBlank();
    }

    /**
     * Create a Checkout Session for a subscription price.
     *
     * @param priceId     Stripe price ID (starts with {@code price_...})
     * @param successUrl  absolute URL the user returns to after success
     * @param cancelUrl   absolute URL for cancel
     * @param tenantId    volta tenant — stored in {@code metadata.tenant_id}
     * @param customerEmail optional — prefills the Stripe form
     * @return Checkout URL
     * @throws ApiException when Stripe rejects the request or misconfigured
     */
    public String createCheckoutSession(String priceId, String successUrl, String cancelUrl,
                                        String tenantId, String customerEmail) {
        ensureConfigured();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("mode", "subscription");
        form.put("success_url", successUrl);
        form.put("cancel_url", cancelUrl);
        form.put("line_items[0][price]", priceId);
        form.put("line_items[0][quantity]", "1");
        form.put("metadata[tenant_id]", tenantId);
        if (customerEmail != null && !customerEmail.isBlank()) {
            form.put("customer_email", customerEmail);
        }
        // Allow promotion codes / tax collection — sensible defaults.
        form.put("allow_promotion_codes", "true");
        form.put("automatic_tax[enabled]", "false");
        JsonNode resp = post("/v1/checkout/sessions", form);
        String url = resp.path("url").asText(null);
        if (url == null || url.isBlank()) {
            throw new ApiException(502, "STRIPE_BAD_RESPONSE", "Checkout session missing url");
        }
        return url;
    }

    /**
     * Create a Billing Portal Session so the user can manage their subscription.
     *
     * @param customerId Stripe customer id (starts with {@code cus_...})
     * @param returnUrl  absolute URL to return to
     */
    public String createPortalSession(String customerId, String returnUrl) {
        ensureConfigured();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("customer", customerId);
        form.put("return_url", returnUrl);
        JsonNode resp = post("/v1/billing_portal/sessions", form);
        String url = resp.path("url").asText(null);
        if (url == null || url.isBlank()) {
            throw new ApiException(502, "STRIPE_BAD_RESPONSE", "Portal session missing url");
        }
        return url;
    }

    // ── internals ──────────────────────────────────────────────────────────

    private void ensureConfigured() {
        if (!isConfigured()) {
            throw new ApiException(503, "STRIPE_NOT_CONFIGURED",
                    "Stripe secret key not set (STRIPE_SECRET_KEY)");
        }
    }

    private JsonNode post(String path, Map<String, String> form) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + path))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + secretKey)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode body = parseJson(resp.body());
            if (resp.statusCode() >= 400) {
                String msg = body == null ? "Stripe " + resp.statusCode()
                        : body.path("error").path("message").asText("Stripe " + resp.statusCode());
                throw new ApiException(502, "STRIPE_ERROR", msg);
            }
            if (body == null) {
                throw new ApiException(502, "STRIPE_BAD_RESPONSE", "empty / malformed body");
            }
            return body;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "STRIPE_UNREACHABLE", e.getMessage());
        }
    }

    static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(),
                    StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) return null;
        try { return MAPPER.readTree(body); }
        catch (Exception e) { return null; }
    }
}
