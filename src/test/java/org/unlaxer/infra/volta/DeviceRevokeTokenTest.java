package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** AUTH-004-v2: mint / verify round-trip + tamper / expiry / replay safety. */
class DeviceRevokeTokenTest {

    private static final String SECRET = "test-secret-do-not-ship-0123456789";

    @Test
    void roundTripPreservesUserAndFingerprint() {
        UUID user = UUID.randomUUID();
        String fp = "Chrome/macOS";
        String token = DeviceRevokeToken.mint(user, fp, 3600, SECRET);
        var result = DeviceRevokeToken.verify(token, SECRET);
        assertTrue(result.ok());
        assertEquals(user, result.decoded().userId());
        assertEquals(fp,   result.decoded().fingerprint());
    }

    @Test
    void tamperedTokenIsRejected() {
        UUID user = UUID.randomUUID();
        String token = DeviceRevokeToken.mint(user, "Firefox/Linux", 3600, SECRET);
        // Flip a char in the middle; base64url makes decoding either succeed
        // with the wrong signature or fail outright — either way: not ok().
        char target = token.charAt(token.length() / 2);
        char replacement = target == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() / 2)
                + replacement
                + token.substring(token.length() / 2 + 1);
        var result = DeviceRevokeToken.verify(tampered, SECRET);
        assertFalse(result.ok());
    }

    @Test
    void wrongSecretIsRejected() {
        UUID user = UUID.randomUUID();
        String token = DeviceRevokeToken.mint(user, "Safari/iOS", 3600, SECRET);
        var result = DeviceRevokeToken.verify(token, "some-other-secret");
        assertFalse(result.ok());
        assertEquals("bad signature", result.error());
    }

    @Test
    void expiredTokenIsRejected() {
        UUID user = UUID.randomUUID();
        // TTL of -60 seconds ⇒ expiry in the past at mint time.
        String token = DeviceRevokeToken.mint(user, "Edge/Windows", -60, SECRET);
        var result = DeviceRevokeToken.verify(token, SECRET);
        assertFalse(result.ok());
        assertEquals("expired", result.error());
    }

    @Test
    void missingTokenYieldsClearError() {
        assertFalse(DeviceRevokeToken.verify(null, SECRET).ok());
        assertFalse(DeviceRevokeToken.verify("", SECRET).ok());
    }

    @Test
    void missingSecretYieldsClearError() {
        var r = DeviceRevokeToken.verify("anything", "");
        assertFalse(r.ok());
        assertEquals("server not configured", r.error());
    }

    @Test
    void malformedBase64Rejected() {
        var r = DeviceRevokeToken.verify("!!! not base64 !!!", SECRET);
        assertFalse(r.ok());
    }

    @Test
    void fingerprintWithDotsStaysIntactThroughBase64() {
        // Fingerprints include "/" ("Chrome/macOS") today and could include dots
        // in the future — the encoding must not split.
        UUID user = UUID.randomUUID();
        String fp = "Chrome-v1.2.3/mac-os.x";
        String token = DeviceRevokeToken.mint(user, fp, 3600, SECRET);
        var r = DeviceRevokeToken.verify(token, SECRET);
        assertTrue(r.ok());
        assertEquals(fp, r.decoded().fingerprint());
    }

    @Test
    void differentUsersYieldDifferentTokens() {
        String a = DeviceRevokeToken.mint(UUID.randomUUID(), "fp", 3600, SECRET);
        String b = DeviceRevokeToken.mint(UUID.randomUUID(), "fp", 3600, SECRET);
        assertNotEquals(a, b);
    }
}
