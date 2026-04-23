package org.unlaxer.infra.volta;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * AUTH-004-v2: stateless signed token for "revoke this device" email links.
 *
 * <p>Token layout (Base64URL, no padding):
 * <pre>v1.userId.fingerprintB64.expiryEpochSec.hmac</pre>
 * All fields are HMAC-SHA256'd with {@code AUTH_FLOW_HMAC_KEY} and then the
 * whole string base64url-encoded. The fingerprint itself is base64url-
 * encoded (dots would break the parse). Fingerprints are already stable
 * derived values (browser/os family) so URL size stays bounded.
 *
 * <p>Stateless: no DB row. Expiry is enforced by the embedded timestamp;
 * after it passes the URL stops working. Users can re-request from the
 * device settings page.
 */
public final class DeviceRevokeToken {

    private static final String VERSION = "v1";
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private DeviceRevokeToken() {}

    /**
     * @param userId        the owning user
     * @param fingerprint   the plaintext fingerprint (hashed into the token)
     * @param ttlSeconds    link validity (e.g. 7 days = 604800)
     * @param hmacSecret    server secret ({@code AUTH_FLOW_HMAC_KEY})
     */
    public static String mint(UUID userId, String fingerprint, long ttlSeconds, String hmacSecret) {
        if (userId == null || fingerprint == null || hmacSecret == null) {
            throw new IllegalArgumentException("mint requires non-null userId / fingerprint / secret");
        }
        long expiry = Instant.now().getEpochSecond() + ttlSeconds;
        String fpB64 = ENC.encodeToString(fingerprint.getBytes(StandardCharsets.UTF_8));
        String core = VERSION + "." + userId + "." + fpB64 + "." + expiry;
        String sig = SecurityUtils.hmacSha256Hex(hmacSecret, core);
        String raw = core + "." + sig;
        return ENC.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Decoded + verified token parts. */
    public record Decoded(UUID userId, String fingerprint, Instant expiry) {}

    /** Verify outcome — {@code decoded} non-null on success, otherwise reason populated. */
    public record VerifyResult(Decoded decoded, String error) {
        public boolean ok() { return decoded != null; }
    }

    public static VerifyResult verify(String token, String hmacSecret) {
        if (token == null || token.isBlank()) return new VerifyResult(null, "missing token");
        if (hmacSecret == null || hmacSecret.isBlank()) return new VerifyResult(null, "server not configured");

        String raw;
        try { raw = new String(DEC.decode(token), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { return new VerifyResult(null, "malformed base64"); }

        // Expected 5 dot-separated parts: version.user.fphash.expiry.sig
        String[] parts = raw.split("\\.");
        if (parts.length != 5) return new VerifyResult(null, "malformed token");
        if (!VERSION.equals(parts[0])) return new VerifyResult(null, "unknown version");

        String core = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
        String expected = SecurityUtils.hmacSha256Hex(hmacSecret, core);
        if (!SecurityUtils.constantTimeEquals(expected, parts[4])) {
            return new VerifyResult(null, "bad signature");
        }

        UUID userId;
        try { userId = UUID.fromString(parts[1]); }
        catch (IllegalArgumentException e) { return new VerifyResult(null, "bad user id"); }

        long expiry;
        try { expiry = Long.parseLong(parts[3]); }
        catch (NumberFormatException e) { return new VerifyResult(null, "bad expiry"); }

        Instant expiresAt = Instant.ofEpochSecond(expiry);
        if (Instant.now().isAfter(expiresAt)) {
            return new VerifyResult(null, "expired");
        }

        String fingerprint;
        try {
            fingerprint = new String(DEC.decode(parts[2]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return new VerifyResult(null, "bad fingerprint");
        }
        return new VerifyResult(new Decoded(userId, fingerprint, expiresAt), null);
    }
}
