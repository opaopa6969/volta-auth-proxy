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
}
