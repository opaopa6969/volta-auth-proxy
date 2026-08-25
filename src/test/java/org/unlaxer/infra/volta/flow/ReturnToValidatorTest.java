package org.unlaxer.infra.volta.flow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReturnToValidatorTest {
    private final ReturnToValidator validator = new ReturnToValidator("app.example.com,console.example.com");

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

    @Test
    void productionBaseUrlRejectsHttpScheme() {
        ReturnToValidator prod = new ReturnToValidator("app.example.com,console.example.com", "https://auth.example.com");
        // http は本番 (BASE_URL=https) で拒否
        assertNull(prod.validateOrNull("http://app.example.com/dashboard"));
        // https は本番でも許可
        assertEquals("https://app.example.com/dashboard",
                prod.validateOrNull("https://app.example.com/dashboard"));
    }

    @Test
    void devBaseUrlAllowsHttpScheme() {
        ReturnToValidator dev = new ReturnToValidator("app.example.com", "http://localhost:7070");
        assertEquals("http://app.example.com/dashboard",
                dev.validateOrNull("http://app.example.com/dashboard"));
    }
}
