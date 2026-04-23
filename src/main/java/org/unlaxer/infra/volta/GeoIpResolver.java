package org.unlaxer.infra.volta;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AUTH-004-v2: pluggable IP → geo lookup.
 *
 * <p>Design goals:
 * <ul>
 *   <li>No mandatory dependency on MaxMind or any other licensed DB.</li>
 *   <li>Sensible no-op default so existing deployments keep working.</li>
 *   <li>Single env var ({@code GEOIP_API_URL}) opts in to an external API,
 *       replacing {@code {ip}} with the address to look up.</li>
 *   <li>In-memory LRU-ish cache keyed on IP, bounded to a few thousand
 *       entries, so we never spam the upstream.</li>
 *   <li>Fails closed: a timeout / 5xx / parse error returns empty and
 *       lets the caller fall back to "IP only".</li>
 * </ul>
 *
 * <p>Operators who want strict offline lookups can drop in a MaxMind-backed
 * implementation later without touching callers.
 */
public interface GeoIpResolver {

    /**
     * Look up an IP. Returns empty for private / loopback / blank / failed
     * lookups. Callers should never block auth on a missing value.
     */
    Optional<GeoInfo> lookup(String ip);

    record GeoInfo(String country, String region, String city) {
        /** Human-friendly single-line label; empty when all parts are blank. */
        public String label() {
            StringBuilder sb = new StringBuilder();
            if (city   != null && !city.isBlank())   sb.append(city);
            if (region != null && !region.isBlank()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(region);
            }
            if (country != null && !country.isBlank()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(country);
            }
            return sb.toString();
        }
    }

    /** No-op resolver — every lookup returns empty. */
    GeoIpResolver NOOP = ip -> Optional.empty();

    /**
     * Create the default resolver from environment configuration.
     *
     * <ul>
     *   <li>Unset → {@link #NOOP}.</li>
     *   <li>{@code GEOIP_API_URL} set → {@link HttpGeoIpResolver} with that URL
     *       (substitutes {@code {ip}} placeholder).</li>
     * </ul>
     */
    static GeoIpResolver fromEnv() {
        String url = System.getenv().getOrDefault("GEOIP_API_URL", "").trim();
        if (url.isEmpty()) return NOOP;
        return new HttpGeoIpResolver(url);
    }
}

/**
 * Minimal HTTP-based resolver. The upstream is expected to return a JSON
 * body containing {@code country}, {@code region}, {@code city} fields at
 * the top level (common shape across ip-api.com, ipapi.co, freegeoip.app).
 * Unrecognised shapes are treated as "no data".
 */
final class HttpGeoIpResolver implements GeoIpResolver {

    private static final int CACHE_LIMIT = 4096;
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT).build();
    private final String urlTemplate;
    private final ConcurrentHashMap<String, Optional<GeoInfo>> cache = new ConcurrentHashMap<>();
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    HttpGeoIpResolver(String urlTemplate) {
        this.urlTemplate = urlTemplate;
    }

    @Override
    public Optional<GeoInfo> lookup(String ip) {
        if (ip == null || ip.isBlank()) return Optional.empty();
        if (isPrivateOrLoopback(ip)) return Optional.empty();

        Optional<GeoInfo> cached = cache.get(ip);
        if (cached != null) return cached;

        Optional<GeoInfo> result = doLookup(ip);
        // Simple size-bounded cache: clear when we hit the limit so we
        // never unbound ourselves. Not an LRU but good enough for a
        // lookup pattern dominated by recently-active users.
        if (cache.size() >= CACHE_LIMIT) cache.clear();
        cache.put(ip, result);
        return result;
    }

    private Optional<GeoInfo> doLookup(String ip) {
        String url = urlTemplate.replace("{ip}", ip);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) return Optional.empty();
            return parseJson(resp.body());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static Optional<GeoInfo> parseJson(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            var node = MAPPER.readTree(body);
            // Accept both { country: "JP" } and { country_code: "JP" } etc.
            String country = pick(node, "country_code", "countryCode", "country");
            String region  = pick(node, "region_name", "regionName", "region", "state");
            String city    = pick(node, "city");
            if ((country == null || country.isBlank())
                    && (region == null || region.isBlank())
                    && (city == null || city.isBlank())) {
                return Optional.empty();
            }
            return Optional.of(new GeoInfo(country, region, city));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String pick(com.fasterxml.jackson.databind.JsonNode node, String... keys) {
        for (String k : keys) {
            com.fasterxml.jackson.databind.JsonNode v = node.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    static boolean isPrivateOrLoopback(String ip) {
        // Cheap prefix checks — good enough for "don't call an external API
        // for internal traffic". Full RFC 1918 / RFC 4193 checks would need
        // InetAddress parsing which is costly for a hot path.
        if (ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("localhost")) return true;
        if (ip.startsWith("10.")) return true;
        if (ip.startsWith("192.168.")) return true;
        if (ip.startsWith("172.")) {
            int second = ip.indexOf('.') + 1;
            int dot2 = ip.indexOf('.', second);
            if (dot2 > 0) {
                try {
                    int n = Integer.parseInt(ip.substring(second, dot2));
                    if (n >= 16 && n <= 31) return true;
                } catch (NumberFormatException ignored) {}
            }
        }
        if (ip.startsWith("fc") || ip.startsWith("fd") || ip.startsWith("fe80")) return true; // RFC 4193 / link-local
        return false;
    }
}
