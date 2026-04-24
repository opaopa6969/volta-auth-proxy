package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HttpSupportTest {
    @Test
    void allowsWhitelistedHostOnly() {
        assertTrue(HttpSupport.isAllowedReturnTo("https://app.example.com/path", "app.example.com,localhost"));
        assertFalse(HttpSupport.isAllowedReturnTo("https://evil.example.com/path", "app.example.com,localhost"));
        assertFalse(HttpSupport.isAllowedReturnTo("javascript:alert(1)", "app.example.com,localhost"));
    }

    @Test
    void wildcardDomainAllowsSubdomains() {
        assertTrue(HttpSupport.isAllowedReturnTo("https://console.unlaxer.org/home", "*.unlaxer.org"));
        assertTrue(HttpSupport.isAllowedReturnTo("https://auth.unlaxer.org/login", "*.unlaxer.org"));
    }

    @Test
    void wildcardDomainDoesNotAllowUnrelatedHost() {
        assertFalse(HttpSupport.isAllowedReturnTo("https://evil.com/path", "*.unlaxer.org"));
        assertFalse(HttpSupport.isAllowedReturnTo("https://unlaxer.org.evil.com/x", "*.unlaxer.org"));
    }

    @Test
    void nullAndBlankReturnToNotAllowed() {
        assertFalse(HttpSupport.isAllowedReturnTo(null, "app.example.com"));
        assertFalse(HttpSupport.isAllowedReturnTo("", "app.example.com"));
        assertFalse(HttpSupport.isAllowedReturnTo("   ", "app.example.com"));
    }

    @Test
    void nonHttpSchemeNotAllowed() {
        assertFalse(HttpSupport.isAllowedReturnTo("ftp://app.example.com/", "app.example.com"));
        assertFalse(HttpSupport.isAllowedReturnTo("javascript:void(0)", "app.example.com"));
    }

    @Test
    void httpSchemeAllowed() {
        assertTrue(HttpSupport.isAllowedReturnTo("http://localhost/path", "localhost"));
    }

    @Test
    void parseOffsetDefault() {
        assertEquals(0, HttpSupport.parseOffset(null));
    }

    @Test
    void parseOffsetValidValue() {
        assertEquals(50, HttpSupport.parseOffset("50"));
    }

    @Test
    void parseOffsetNegativeClampedToZero() {
        assertEquals(0, HttpSupport.parseOffset("-1"));
    }

    @Test
    void parseOffsetInvalidStringFallsToZero() {
        assertEquals(0, HttpSupport.parseOffset("abc"));
    }

    @Test
    void parseLimitDefault() {
        assertEquals(20, HttpSupport.parseLimit(null));
    }

    @Test
    void parseLimitValidValue() {
        assertEquals(50, HttpSupport.parseLimit("50"));
    }

    @Test
    void parseLimitCappedAt100() {
        assertEquals(100, HttpSupport.parseLimit("999"));
    }

    @Test
    void parseLimitMinimumIsOne() {
        assertEquals(1, HttpSupport.parseLimit("0"));
        assertEquals(1, HttpSupport.parseLimit("-5"));
    }

    @Test
    void parseLimitInvalidStringFallsToDefault() {
        assertEquals(20, HttpSupport.parseLimit("notanumber"));
    }
}
