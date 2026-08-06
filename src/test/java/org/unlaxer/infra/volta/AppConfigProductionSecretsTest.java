package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for #34: AUTH_FLOW_HMAC_KEY / JWT_KEY_ENCRYPTION_SECRET
 * must fail fast on boot when DEV_MODE=false and the secret is still the
 * dev-only default or empty.
 */
class AppConfigProductionSecretsTest {

    @Test
    void devMode_skipsValidation_evenWithDefaultSecrets() {
        AppConfig config = AppConfigTestFactory.builder()
                .devMode(true)
                .jwtKeyEncryptionSecret("dev-only-secret-change-me")
                .authFlowHmacKey("dev-only-hmac-key-change-me")
                .build();
        assertDoesNotThrow(config::validateProductionSecrets);
    }

    @Test
    void productionMode_throwsWhenJwtSecretIsDefault() {
        AppConfig config = AppConfigTestFactory.builder()
                .devMode(false)
                .jwtKeyEncryptionSecret("dev-only-secret-change-me")
                .authFlowHmacKey("strong-production-key")
                .build();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                config::validateProductionSecrets);
        assertTrue(ex.getMessage().contains("JWT_KEY_ENCRYPTION_SECRET"));
    }

    @Test
    void productionMode_throwsWhenHmacKeyIsDefault() {
        AppConfig config = AppConfigTestFactory.builder()
                .devMode(false)
                .jwtKeyEncryptionSecret("strong-production-secret")
                .authFlowHmacKey("dev-only-hmac-key-change-me")
                .build();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                config::validateProductionSecrets);
        assertTrue(ex.getMessage().contains("AUTH_FLOW_HMAC_KEY"));
    }

    @Test
    void productionMode_throwsWhenJwtSecretIsBlank() {
        AppConfig config = AppConfigTestFactory.builder()
                .devMode(false)
                .jwtKeyEncryptionSecret("   ")
                .authFlowHmacKey("strong-production-key")
                .build();
        assertThrows(IllegalStateException.class, config::validateProductionSecrets);
    }

    @Test
    void productionMode_throwsWhenHmacKeyIsEmpty() {
        AppConfig config = AppConfigTestFactory.builder()
                .devMode(false)
                .jwtKeyEncryptionSecret("strong-production-secret")
                .authFlowHmacKey("")
                .build();
        assertThrows(IllegalStateException.class, config::validateProductionSecrets);
    }

    @Test
    void productionMode_passesWithStrongSecrets() {
        AppConfig config = AppConfigTestFactory.builder()
                .devMode(false)
                .jwtKeyEncryptionSecret("strong-production-secret-32-bytes!!")
                .authFlowHmacKey("strong-production-hmac-32-bytes!!")
                .build();
        assertDoesNotThrow(config::validateProductionSecrets);
    }

    @Test
    void isJwtKeyEncryptionSecretDefault_recognizesDefaultAndBlank() {
        assertTrue(AppConfigTestFactory.builder()
                .jwtKeyEncryptionSecret("dev-only-secret-change-me").build()
                .isJwtKeyEncryptionSecretDefault());
        assertTrue(AppConfigTestFactory.builder()
                .jwtKeyEncryptionSecret("").build()
                .isJwtKeyEncryptionSecretDefault());
        assertFalse(AppConfigTestFactory.builder()
                .jwtKeyEncryptionSecret("real-secret").build()
                .isJwtKeyEncryptionSecretDefault());
    }

    @Test
    void isAuthFlowHmacKeyDefault_recognizesDefaultAndBlank() {
        assertTrue(AppConfigTestFactory.builder()
                .authFlowHmacKey("dev-only-hmac-key-change-me").build()
                .isAuthFlowHmacKeyDefault());
        assertTrue(AppConfigTestFactory.builder()
                .authFlowHmacKey("").build()
                .isAuthFlowHmacKeyDefault());
        assertFalse(AppConfigTestFactory.builder()
                .authFlowHmacKey("real-key").build()
                .isAuthFlowHmacKeyDefault());
    }
}
