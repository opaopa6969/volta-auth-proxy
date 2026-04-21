package org.unlaxer.infra.volta;

/**
 * Resolves human-readable device name from User-Agent string.
 *
 * <p>AUTH-010 (UA Reduction / UA-CH): Chrome is phasing down the legacy UA
 * string. When the plain UA yields an unknown result, we fall back to
 * User-Agent Client Hints (RFC 8942) headers — {@code Sec-CH-UA} (browser)
 * and {@code Sec-CH-UA-Platform} (OS). Callers that have access to the
 * request should use {@link #fromRequest} to get the best-effort answer.
 */
public final class DeviceNameResolver {

    private DeviceNameResolver() {}

    public static String fromUserAgent(String ua) {
        if (ua == null || ua.isBlank()) return "Unknown device";
        String browser = inferBrowser(ua);
        String os = inferOS(ua);
        return browser + " on " + os;
    }

    /**
     * Compose a device label from UA + Client Hints with fallback preference
     * given to the more-specific UA parse; Client Hints only fill in unknowns.
     *
     * @param ua                 raw User-Agent header (may be null)
     * @param secChUa            {@code Sec-CH-UA} header, e.g.
     *                           {@code "Chromium";v="121", "Not A(Brand";v="99", "Google Chrome";v="121"}
     * @param secChUaPlatform    {@code Sec-CH-UA-Platform} header, e.g. {@code "macOS"}
     */
    public static String fromRequest(String ua, String secChUa, String secChUaPlatform) {
        String browser = inferBrowser(ua);
        if ("Browser".equals(browser)) browser = browserFromClientHints(secChUa);
        String os = inferOS(ua);
        if ("Unknown OS".equals(os)) os = osFromClientHints(secChUaPlatform);
        if ("Browser".equals(browser) && "Unknown OS".equals(os) && (ua == null || ua.isBlank())) {
            return "Unknown device";
        }
        return browser + " on " + os;
    }

    static String inferBrowser(String ua) {
        if (ua == null) return "Browser";
        if (ua.contains("Edg/")) return "Edge";
        if (ua.contains("Chrome/") && !ua.contains("Edg/")) return "Chrome";
        if (ua.contains("Safari/") && !ua.contains("Chrome/")) return "Safari";
        if (ua.contains("Firefox/")) return "Firefox";
        return "Browser";
    }

    static String inferOS(String ua) {
        if (ua == null) return "Unknown OS";
        if (ua.contains("iPhone")) return "iPhone";
        if (ua.contains("iPad")) return "iPad";
        if (ua.contains("Android")) return "Android";
        if (ua.contains("Mac OS X")) return "macOS";
        if (ua.contains("Windows")) return "Windows";
        if (ua.contains("Linux")) return "Linux";
        return "Unknown OS";
    }

    /**
     * Extracts a browser name from a {@code Sec-CH-UA} value. Returns
     * {@code "Browser"} when nothing useful is present so the caller can
     * tell apart "tried but unknown" from a successful match.
     */
    static String browserFromClientHints(String secChUa) {
        if (secChUa == null || secChUa.isBlank()) return "Browser";
        // Sec-CH-UA brands we recognize — check specific brands first so
        // "Google Chrome" beats the generic "Chromium" alias.
        String lower = secChUa.toLowerCase();
        if (lower.contains("\"microsoft edge\"")) return "Edge";
        if (lower.contains("\"google chrome\"")) return "Chrome";
        if (lower.contains("\"opera\"")) return "Opera";
        if (lower.contains("\"brave\"")) return "Brave";
        if (lower.contains("\"vivaldi\"")) return "Vivaldi";
        // Firefox doesn't emit Sec-CH-UA, Safari doesn't either — kept for completeness.
        if (lower.contains("\"chromium\"")) return "Chrome";
        return "Browser";
    }

    /**
     * Extracts an OS from a {@code Sec-CH-UA-Platform} value. The value is
     * a quoted string per RFC 8942, e.g. {@code "macOS"} or {@code "Windows"}.
     */
    static String osFromClientHints(String secChUaPlatform) {
        if (secChUaPlatform == null || secChUaPlatform.isBlank()) return "Unknown OS";
        String trimmed = secChUaPlatform.trim();
        // Strip surrounding quotes if present
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '"' && trimmed.charAt(trimmed.length() - 1) == '"') {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return switch (trimmed) {
            case "macOS"   -> "macOS";
            case "Windows" -> "Windows";
            case "Linux"   -> "Linux";
            case "Android" -> "Android";
            case "iOS"     -> "iOS";
            case "Chrome OS", "Chromium OS" -> "ChromeOS";
            case ""        -> "Unknown OS";
            default        -> trimmed;
        };
    }
}
