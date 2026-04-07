package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
