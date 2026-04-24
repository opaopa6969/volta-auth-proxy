package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecurityUtilsTest {
    @Test
    void pkceChallengeMatchesKnownVector() {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String challenge = SecurityUtils.pkceChallenge(verifier);
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge);
    }

    @Test
    void randomHelperReturnsNonEmpty() {
        assertNotNull(SecurityUtils.randomUrlSafe(16));
    }

    @Test
    void sha256HexProducesLowercaseHex() {
        String hash = SecurityUtils.sha256Hex("hello");
        // SHA-256 of "hello" is well-known
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash);
    }

    @Test
    void sha256HexIsDeterministic() {
        String a = SecurityUtils.sha256Hex("test-input");
        String b = SecurityUtils.sha256Hex("test-input");
        assertEquals(a, b);
    }

    @Test
    void sha256HexDifferentInputsDifferentOutputs() {
        String a = SecurityUtils.sha256Hex("input-a");
        String b = SecurityUtils.sha256Hex("input-b");
        assertNotEquals(a, b);
    }

    @Test
    void constantTimeEqualsReturnsTrueForSameStrings() {
        assertTrue(SecurityUtils.constantTimeEquals("abc", "abc"));
        assertTrue(SecurityUtils.constantTimeEquals("", ""));
    }

    @Test
    void constantTimeEqualsReturnsFalseForDifferentStrings() {
        assertFalse(SecurityUtils.constantTimeEquals("abc", "xyz"));
        assertFalse(SecurityUtils.constantTimeEquals("abc", "ab"));
    }

    @Test
    void constantTimeEqualsHandlesNulls() {
        assertFalse(SecurityUtils.constantTimeEquals(null, "abc"));
        assertFalse(SecurityUtils.constantTimeEquals("abc", null));
        assertFalse(SecurityUtils.constantTimeEquals(null, null));
    }

    @Test
    void hmacSha256HexIsDeterministic() {
        String a = SecurityUtils.hmacSha256Hex("secret", "payload");
        String b = SecurityUtils.hmacSha256Hex("secret", "payload");
        assertEquals(a, b);
    }

    @Test
    void hmacSha256HexDifferentSecretsProduceDifferentMacs() {
        String a = SecurityUtils.hmacSha256Hex("secret1", "payload");
        String b = SecurityUtils.hmacSha256Hex("secret2", "payload");
        assertNotEquals(a, b);
    }

    @Test
    void hmacSha256HexDifferentPayloadsProduceDifferentMacs() {
        String a = SecurityUtils.hmacSha256Hex("secret", "payload1");
        String b = SecurityUtils.hmacSha256Hex("secret", "payload2");
        assertNotEquals(a, b);
    }

    @Test
    void inviteCodeIsNonEmptyAndUrlSafe() {
        String code = SecurityUtils.inviteCode();
        assertNotNull(code);
        assertFalse(code.isBlank());
        // URL-safe base64 must not contain +, /, or = padding
        assertFalse(code.contains("+"));
        assertFalse(code.contains("/"));
        assertFalse(code.contains("="));
    }

    @Test
    void newUuidIsNotNull() {
        assertNotNull(SecurityUtils.newUuid());
    }
}
