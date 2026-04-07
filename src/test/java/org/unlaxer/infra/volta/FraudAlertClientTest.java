package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FraudAlertClientTest {

    @Test
    void disabledClient_returnsSafe() {
        // Empty URL = disabled
        var config = AppConfig.fromEnv(); // FRAUD_ALERT_URL defaults to ""
        var client = new FraudAlertClient(config, new com.fasterxml.jackson.databind.ObjectMapper());

        assertFalse(client.isEnabled());

        var result = client.checkOnly(UUID.randomUUID(), UUID.randomUUID(),
                "session-1", "127.0.0.1", "Chrome");
        assertEquals(1, result.relativeSuspiciousValue());
        assertFalse(result.blocked());
    }

    @Test
    void computeUserHash_deterministic() {
        UUID tenant = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID user = UUID.fromString("22222222-2222-2222-2222-222222222222");

        String hash1 = FraudAlertClient.computeUserHash(tenant, user);
        String hash2 = FraudAlertClient.computeUserHash(tenant, user);
        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length()); // SHA-256 hex
    }

    @Test
    void computeUserHash_differentTenants_differentHashes() {
        UUID user = UUID.randomUUID();
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        assertNotEquals(
                FraudAlertClient.computeUserHash(tenantA, user),
                FraudAlertClient.computeUserHash(tenantB, user));
    }

    @Test
    void reportMethods_doNotThrow_whenDisabled() {
        var config = AppConfig.fromEnv();
        var client = new FraudAlertClient(config, new com.fasterxml.jackson.databind.ObjectMapper());

        // Should be no-ops without exception
        assertDoesNotThrow(() -> client.reportLoginSucceed(
                UUID.randomUUID(), UUID.randomUUID(), "s1", "127.0.0.1", "Chrome"));
        assertDoesNotThrow(() -> client.reportLoginFailed(
                UUID.randomUUID(), UUID.randomUUID(), "s1", "127.0.0.1", "Chrome"));
    }
}
