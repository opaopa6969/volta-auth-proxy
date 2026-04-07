package org.unlaxer.infra.volta.flow;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Encodes/decodes flow_id into OIDC state parameter with HMAC signature.
 * Format: base64url(flow_id + ":" + nonce + ":" + hmac(flow_id + ":" + nonce))
 */
public final class OidcStateCodec {
    private static final String ALGORITHM = "HmacSHA256";
    private final byte[] key;

    public OidcStateCodec(String hmacKey) {
        this.key = hmacKey.getBytes(StandardCharsets.UTF_8);
    }

    /** Encode flow_id and nonce into a signed state parameter. */
    public String encode(String flowId, String nonce) {
        String payload = flowId + ":" + nonce;
        String mac = computeHmac(payload);
        String full = payload + ":" + mac;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                full.getBytes(StandardCharsets.UTF_8));
    }

    /** Decode and verify a state parameter. Returns the flow_id if valid. */
    public Optional<String> decode(String state) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(state);
            String full = new String(decoded, StandardCharsets.UTF_8);
            int lastColon = full.lastIndexOf(':');
            if (lastColon < 0) return Optional.empty();

            String payload = full.substring(0, lastColon);
            String mac = full.substring(lastColon + 1);

            if (!constantTimeEquals(computeHmac(payload), mac)) {
                return Optional.empty();
            }

            int firstColon = payload.indexOf(':');
            if (firstColon < 0) return Optional.empty();

            String flowId = payload.substring(0, firstColon);
            return Optional.of(flowId);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
