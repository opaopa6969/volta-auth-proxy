package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Documents the invariant that invitation codes are looked up by their SHA-256
 * hash. The hash produced here must match what the V27 Flyway migration computes
 * with {@code encode(digest(code, 'sha256'), 'hex')} so that already-issued
 * invite links keep working after the plaintext column is dropped.
 */
class InvitationCodeHashTest {

    @Test
    void inviteCodeHashIs64CharLowercaseHex() {
        String code = SecurityUtils.inviteCode();
        String hash = SecurityUtils.sha256Hex(code);
        assertEquals(64, hash.length(), "SHA-256 hex digest is 64 chars");
        assertTrue(hash.matches("[0-9a-f]{64}"), "must be lowercase hex to match pgcrypto digest()");
    }

    @Test
    void knownVectorMatchesSha256() {
        // SHA-256("abc") — the canonical test vector, lowercase hex.
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                SecurityUtils.sha256Hex("abc"));
    }

    @Test
    void differentCodesHashDifferently() {
        assertNotEquals(SecurityUtils.sha256Hex("code-a"), SecurityUtils.sha256Hex("code-b"));
    }

    @Test
    void sameCodeHashesStably() {
        // Lookup depends on this being deterministic across issue and redeem.
        String code = "stable-invite-code";
        assertEquals(SecurityUtils.sha256Hex(code), SecurityUtils.sha256Hex(code));
    }
}
