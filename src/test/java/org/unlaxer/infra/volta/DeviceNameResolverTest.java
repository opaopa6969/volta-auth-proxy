package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeviceNameResolverTest {

    @Test
    void chromeMacOS() {
        assertEquals("Chrome on macOS",
                DeviceNameResolver.fromUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"));
    }

    @Test
    void safariIPhone() {
        assertEquals("Safari on iPhone",
                DeviceNameResolver.fromUserAgent("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Safari/604.1"));
    }

    @Test
    void edgeWindows() {
        assertEquals("Edge on Windows",
                DeviceNameResolver.fromUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36 Edg/120.0"));
    }

    @Test
    void firefoxLinux() {
        assertEquals("Firefox on Linux",
                DeviceNameResolver.fromUserAgent("Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0"));
    }

    @Test
    void nullUserAgent() {
        assertEquals("Unknown device", DeviceNameResolver.fromUserAgent(null));
    }

    // ── AUTH-010: User-Agent Client Hints fallback ──────────────────────────

    @Test
    void clientHintsFillBlanksOnReducedUa() {
        // Reduced UA (future Chrome): the legacy UA no longer includes
        // browser version tokens, but still has platform info. Client Hints
        // supply the missing browser identity; UA keeps the OS because
        // "UA first, hints only fill unknowns".
        String reducedUa = "Mozilla/5.0 (Windows) WebKit/537.36";
        String secChUa = "\"Chromium\";v=\"121\", \"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"121\"";
        String platform = "\"macOS\"";
        assertEquals("Chrome on Windows",
                DeviceNameResolver.fromRequest(reducedUa, secChUa, platform));
    }

    @Test
    void clientHintsRecoverBothUnknown() {
        String reducedUa = "";
        String secChUa = "\"Google Chrome\";v=\"121\"";
        String platform = "\"macOS\"";
        assertEquals("Chrome on macOS",
                DeviceNameResolver.fromRequest(reducedUa, secChUa, platform));
    }

    @Test
    void secChUaPlatformQuoted() {
        assertEquals("macOS",   DeviceNameResolver.osFromClientHints("\"macOS\""));
        assertEquals("Windows", DeviceNameResolver.osFromClientHints("\"Windows\""));
        assertEquals("iOS",     DeviceNameResolver.osFromClientHints("\"iOS\""));
        assertEquals("ChromeOS", DeviceNameResolver.osFromClientHints("\"Chrome OS\""));
    }

    @Test
    void secChUaPlatformUnquoted() {
        // RFC 8942 mandates quotes but be lenient.
        assertEquals("macOS", DeviceNameResolver.osFromClientHints("macOS"));
    }

    @Test
    void secChUaPlatformMissing() {
        assertEquals("Unknown OS", DeviceNameResolver.osFromClientHints(null));
        assertEquals("Unknown OS", DeviceNameResolver.osFromClientHints(""));
    }

    @Test
    void secChUaBrandPicksMostSpecific() {
        String secChUa = "\"Chromium\";v=\"121\", \"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"121\"";
        assertEquals("Chrome", DeviceNameResolver.browserFromClientHints(secChUa));
    }

    @Test
    void secChUaBrandEdge() {
        String secChUa = "\"Microsoft Edge\";v=\"121\", \"Chromium\";v=\"121\", \"Not A(Brand\";v=\"99\"";
        assertEquals("Edge", DeviceNameResolver.browserFromClientHints(secChUa));
    }

    @Test
    void secChUaBrandFallbackToChromium() {
        // Only Chromium + greasy brand — best we can say is Chrome.
        String secChUa = "\"Chromium\";v=\"121\", \"Not A(Brand\";v=\"99\"";
        assertEquals("Chrome", DeviceNameResolver.browserFromClientHints(secChUa));
    }

    @Test
    void secChUaBrandUnknown() {
        assertEquals("Browser", DeviceNameResolver.browserFromClientHints(null));
        assertEquals("Browser", DeviceNameResolver.browserFromClientHints(""));
        assertEquals("Browser", DeviceNameResolver.browserFromClientHints("\"Not A(Brand\";v=\"99\""));
    }

    @Test
    void fromRequestAllNull() {
        assertEquals("Unknown device",
                DeviceNameResolver.fromRequest(null, null, null));
    }
}
