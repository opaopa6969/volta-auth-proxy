package com.volta.authproxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HttpSupportTest {
    @Test
    void allowsWhitelistedHostOnly() {
        assertTrue(HttpSupport.isAllowedReturnTo("https://app.example.com/path", "app.example.com,localhost"));
        assertFalse(HttpSupport.isAllowedReturnTo("https://evil.example.com/path", "app.example.com,localhost"));
        assertFalse(HttpSupport.isAllowedReturnTo("javascript:alert(1)", "app.example.com,localhost"));
    }
}
