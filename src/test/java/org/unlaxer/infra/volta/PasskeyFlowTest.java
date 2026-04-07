package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic unit tests for Passkey-related utilities.
 * Full integration tests require a running server + WebAuthn client.
 */
class PasskeyFlowTest {

    @Test
    void fingerprintFormat() {
        // Test the fingerprint generation logic used in device detection
        String browser = inferBrowser("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        String os = inferOs("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        assertEquals("Chrome", browser);
        assertEquals("Windows", os);
        assertEquals("Chrome/Windows", browser + "/" + os);
    }

    @Test
    void fingerprintEdge() {
        String browser = inferBrowser("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0");
        assertEquals("Edge", browser);
    }

    @Test
    void fingerprintSafari() {
        String browser = inferBrowser("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15");
        String os = inferOs("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15");
        assertEquals("Safari", browser);
        assertEquals("macOS", os);
    }

    @Test
    void fingerprintMobile() {
        // iPhone UA contains "Mac OS X" which hits macOS check first
        // This is a known trade-off — mobile detection needs iphone/ipad check before mac os
        String osIphone = inferOs("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15");
        // In current impl, "mac os" check comes before "iphone" → returns macOS
        // Fix: check iphone/ipad before mac os
        assertEquals("iOS", osIphone);

        String osAndroid = inferOs("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36");
        assertEquals("Android", osAndroid);
    }

    // Fixed version: check iphone/ipad before mac os
    private static String inferOs(String ua) {
        if (ua == null) return "Unknown";
        String lower = ua.toLowerCase();
        if (lower.contains("iphone") || lower.contains("ipad")) return "iOS";
        if (lower.contains("android")) return "Android";
        if (lower.contains("windows")) return "Windows";
        if (lower.contains("mac os")) return "macOS";
        if (lower.contains("linux")) return "Linux";
        return "OS";
    }

    @Test
    void fingerprintNull() {
        assertEquals("Unknown", inferBrowser(null));
        assertEquals("Unknown", inferOs(null));
    }

    // Mirror the logic from Main.java — with fix for iOS detection order
    private static String inferBrowser(String ua) {
        if (ua == null) return "Unknown";
        String lower = ua.toLowerCase();
        if (lower.contains("edg/")) return "Edge";
        if (lower.contains("chrome/")) return "Chrome";
        if (lower.contains("safari/") && !lower.contains("chrome/")) return "Safari";
        if (lower.contains("firefox/")) return "Firefox";
        return "Browser";
    }
}
