package org.unlaxer.infra.volta;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JWT signing-key selection during rotation. These cover the
 * kid-based key lookup that lets previously issued JWTs (signed with a now
 * "retiring" key) keep verifying after a key rotation, without requiring a DB.
 */
class JwtKeySelectionTest {

    private static RSAKey generateKey(String kid) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair kp = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) kp.getPublic())
                    .privateKey((RSAPrivateKey) kp.getPrivate())
                    .keyID(kid)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void selectsRetiringKeyByKid() {
        RSAKey active = generateKey("kid-active");
        RSAKey retiring = generateKey("kid-retiring");
        Map<String, RSAKey> keys = new LinkedHashMap<>();
        keys.put(active.getKeyID(), active);
        keys.put(retiring.getKeyID(), retiring);

        // A JWT signed under the retiring key must resolve to the retiring key.
        assertSame(retiring, JwtService.selectKey(keys, active, "kid-retiring"));
        // A JWT signed under the active key resolves to the active key.
        assertSame(active, JwtService.selectKey(keys, active, "kid-active"));
    }

    @Test
    void fallsBackToActiveWhenKidMissing() {
        RSAKey active = generateKey("kid-active");
        Map<String, RSAKey> keys = new LinkedHashMap<>();
        keys.put(active.getKeyID(), active);

        // No kid in the header (legacy / single-key token) -> active key.
        assertSame(active, JwtService.selectKey(keys, active, null));
    }

    @Test
    void fallsBackToActiveWhenKidUnknown() {
        RSAKey active = generateKey("kid-active");
        Map<String, RSAKey> keys = new LinkedHashMap<>();
        keys.put(active.getKeyID(), active);

        // Unknown kid (caller will trigger a reload) still falls back to active.
        assertSame(active, JwtService.selectKey(keys, active, "kid-does-not-exist"));
    }

    @Test
    void singleKeySetupBackwardCompatible() {
        // With exactly one key, every selection (any kid) returns that key.
        RSAKey only = generateKey("kid-only");
        Map<String, RSAKey> keys = Map.of(only.getKeyID(), only);
        assertSame(only, JwtService.selectKey(keys, only, "kid-only"));
        assertSame(only, JwtService.selectKey(keys, only, null));
        assertSame(only, JwtService.selectKey(keys, only, "other"));
    }
}
