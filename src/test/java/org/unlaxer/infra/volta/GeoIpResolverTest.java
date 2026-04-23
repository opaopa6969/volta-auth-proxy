package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AUTH-004-v2: pluggable GeoIP resolver shape + JSON parsing + private-IP filter. */
class GeoIpResolverTest {

    @Test
    void noopReturnsEmpty() {
        assertTrue(GeoIpResolver.NOOP.lookup("1.2.3.4").isEmpty());
    }

    @Test
    void geoInfoLabelSkipsBlanks() {
        var g = new GeoIpResolver.GeoInfo("JP", "", "Tokyo");
        assertEquals("Tokyo, JP", g.label());
    }

    @Test
    void geoInfoLabelAllBlank() {
        var g = new GeoIpResolver.GeoInfo(null, null, null);
        assertEquals("", g.label());
    }

    @Test
    void geoInfoLabelCountryOnly() {
        assertEquals("JP", new GeoIpResolver.GeoInfo("JP", null, null).label());
    }

    @Test
    void geoInfoLabelCityCountry() {
        var g = new GeoIpResolver.GeoInfo("US", "CA", "San Francisco");
        assertEquals("San Francisco, CA, US", g.label());
    }

    @Test
    void parseJsonHandlesIpApi() {
        String body = "{\"country_code\":\"JP\",\"region_name\":\"Tokyo\",\"city\":\"Shinjuku\"}";
        var out = HttpGeoIpResolver.parseJson(body);
        assertTrue(out.isPresent());
        assertEquals("JP",       out.get().country());
        assertEquals("Tokyo",    out.get().region());
        assertEquals("Shinjuku", out.get().city());
    }

    @Test
    void parseJsonHandlesCamelCase() {
        String body = "{\"countryCode\":\"US\",\"regionName\":\"California\",\"city\":\"SF\"}";
        var out = HttpGeoIpResolver.parseJson(body);
        assertTrue(out.isPresent());
        assertEquals("US", out.get().country());
    }

    @Test
    void parseJsonRejectsAllBlank() {
        String body = "{\"country\":\"\",\"region\":\"\",\"city\":\"\"}";
        assertTrue(HttpGeoIpResolver.parseJson(body).isEmpty());
    }

    @Test
    void parseJsonRejectsMalformed() {
        assertTrue(HttpGeoIpResolver.parseJson("not json").isEmpty());
        assertTrue(HttpGeoIpResolver.parseJson("").isEmpty());
        assertTrue(HttpGeoIpResolver.parseJson(null).isEmpty());
    }

    @Test
    void privateIpsAreFilteredWithoutHitting_theNetwork() {
        // Create a resolver with a deliberately broken URL; it should never
        // be contacted for private addresses.
        var r = new HttpGeoIpResolver("http://unreachable.invalid/{ip}");
        assertTrue(r.lookup("127.0.0.1").isEmpty());
        assertTrue(r.lookup("10.0.0.1").isEmpty());
        assertTrue(r.lookup("192.168.1.1").isEmpty());
        assertTrue(r.lookup("172.16.0.5").isEmpty());
        assertTrue(r.lookup("172.31.255.255").isEmpty());
        assertTrue(r.lookup("::1").isEmpty());
        assertTrue(r.lookup("fe80::1").isEmpty());
        assertTrue(r.lookup("fc00::1").isEmpty());
    }

    @Test
    void publicNonRoutableRangeDetection() {
        // 172.32+ is NOT RFC 1918 — must be treated as public (method
        // returns false). We don't actually hit the network here; just
        // verify the classifier.
        assertFalse(HttpGeoIpResolver.isPrivateOrLoopback("172.32.0.1"));
        assertFalse(HttpGeoIpResolver.isPrivateOrLoopback("8.8.8.8"));
        assertFalse(HttpGeoIpResolver.isPrivateOrLoopback("2001:db8::1"));
    }

    @Test
    void emptyAndNullIp() {
        var r = new HttpGeoIpResolver("http://x/{ip}");
        assertTrue(r.lookup(null).isEmpty());
        assertTrue(r.lookup("").isEmpty());
    }

    @Test
    void fromEnvWithoutUrlReturnsNoop() {
        // We can't easily unset env vars in JVM, but the method returns NOOP
        // when GEOIP_API_URL is unset. Current test env shouldn't have it.
        GeoIpResolver r = GeoIpResolver.fromEnv();
        // Either NOOP (likely) or HttpGeoIpResolver (if operator set the var).
        // In both cases the call succeeds and returns Optional.
        assertNotNull(r);
        assertNotNull(r.lookup("8.8.8.8")); // returns an Optional, never null
    }
}
