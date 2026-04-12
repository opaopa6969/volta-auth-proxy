package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalNetworkBypassTest {

    @Test
    void matchesRfc1918() {
        var bypass = new LocalNetworkBypass("192.168.0.0/16,10.0.0.0/8,172.16.0.0/12");
        assertTrue(bypass.matches("192.168.1.50"));
        assertTrue(bypass.matches("192.168.0.1"));
        assertTrue(bypass.matches("10.0.0.1"));
        assertTrue(bypass.matches("10.255.255.255"));
        assertTrue(bypass.matches("172.16.0.1"));
        assertTrue(bypass.matches("172.31.255.255"));
    }

    @Test
    void matchesTailscaleCgnat() {
        var bypass = new LocalNetworkBypass("100.64.0.0/10");
        assertTrue(bypass.matches("100.64.0.1"));
        assertTrue(bypass.matches("100.100.0.1"));
        assertTrue(bypass.matches("100.127.255.255"));
        assertFalse(bypass.matches("100.128.0.1")); // outside /10
    }

    @Test
    void matchesLoopback() {
        var bypass = new LocalNetworkBypass("127.0.0.1/32");
        assertTrue(bypass.matches("127.0.0.1"));
        assertFalse(bypass.matches("127.0.0.2"));
    }

    @Test
    void noMatchForPublicIp() {
        var bypass = new LocalNetworkBypass("192.168.0.0/16,10.0.0.0/8,100.64.0.0/10");
        assertFalse(bypass.matches("1.2.3.4"));
        assertFalse(bypass.matches("8.8.8.8"));
        assertFalse(bypass.matches("203.0.113.1"));
    }

    @Test
    void emptyConfigDisablesBypass() {
        var bypass = new LocalNetworkBypass("");
        assertFalse(bypass.matches("192.168.1.1"));
        assertFalse(bypass.matches("10.0.0.1"));
    }

    @Test
    void ipv6NotMatched() {
        var bypass = new LocalNetworkBypass("192.168.0.0/16");
        assertFalse(bypass.matches("::1"));
        assertFalse(bypass.matches("fe80::1"));
    }

    @Test
    void invalidCidrSkipped() {
        // Should not throw — invalid entries are skipped with a warning
        var bypass = new LocalNetworkBypass("not-a-cidr,192.168.0.0/16");
        assertTrue(bypass.matches("192.168.1.1"));
    }

    @Test
    void hostRoute32() {
        var bypass = new LocalNetworkBypass("192.168.1.50/32");
        assertTrue(bypass.matches("192.168.1.50"));
        assertFalse(bypass.matches("192.168.1.51"));
    }
}
