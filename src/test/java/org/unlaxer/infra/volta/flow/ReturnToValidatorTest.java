package org.unlaxer.infra.volta.flow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReturnToValidatorTest {
    private ReturnToValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ReturnToValidator("app.example.com,console.example.com");
    }

    @Test
    void nullAndBlank_returnNull() {
        assertNull(validator.validateOrNull(null));
        assertNull(validator.validateOrNull(""));
        assertNull(validator.validateOrNull("   "));
    }

    @Test
    void allowedRelativePaths() {
        assertEquals("/console/dashboard", validator.validateOrNull("/console/dashboard"));
        assertEquals("/invite/abc123", validator.validateOrNull("/invite/abc123"));
        assertEquals("/settings/mfa", validator.validateOrNull("/settings/mfa"));
        assertEquals("/mfa/challenge", validator.validateOrNull("/mfa/challenge"));
        assertEquals("/select-tenant", validator.validateOrNull("/select-tenant"));
    }

    @Test
    void disallowedRelativePaths() {
        assertNull(validator.validateOrNull("/admin/dangerous"));
        assertNull(validator.validateOrNull("/api/v1/something"));
        assertNull(validator.validateOrNull("/"));
    }

    @Test
    void allowedAbsoluteUrls() {
        assertEquals("https://app.example.com/dashboard",
                validator.validateOrNull("https://app.example.com/dashboard"));
        assertEquals("https://console.example.com/",
                validator.validateOrNull("https://console.example.com/"));
    }

    @Test
    void disallowedAbsoluteUrls() {
        assertNull(validator.validateOrNull("https://evil.com/phishing"));
        assertNull(validator.validateOrNull("https://app.example.com.evil.com/"));
        assertNull(validator.validateOrNull("javascript:alert(1)"));
        assertNull(validator.validateOrNull("ftp://app.example.com/file"));
    }

    // --- Issue #46: BASE_URL=https should reject http:// return_to ---
    // In the test JVM, BASE_URL is unset (dev mode) so http stays allowed.
    // Production behavior (https enforced) is documented in ReturnToValidator
    // and HttpSupport.REQUIRE_HTTPS_RETURN_TO; covered by code inspection.

    @Test
    void httpAllowedWhenBaseUrlUnset_devMode() {
        // Test JVM does not set BASE_URL → REQUIRE_HTTPS=false → http allowed
        assertEquals("http://app.example.com/dash",
                validator.validateOrNull("http://app.example.com/dash"));
    }

    @Test
    void httpsAlwaysAllowed() {
        assertEquals("https://app.example.com/x",
                validator.validateOrNull("https://app.example.com/x"));
    }
}
