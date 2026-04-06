package com.volta.authproxy;

/**
 * Resolves human-readable device name from User-Agent string.
 */
public final class DeviceNameResolver {

    private DeviceNameResolver() {}

    public static String fromUserAgent(String ua) {
        if (ua == null || ua.isBlank()) return "Unknown device";
        String browser = inferBrowser(ua);
        String os = inferOS(ua);
        return browser + " on " + os;
    }

    static String inferBrowser(String ua) {
        if (ua.contains("Edg/")) return "Edge";
        if (ua.contains("Chrome/") && !ua.contains("Edg/")) return "Chrome";
        if (ua.contains("Safari/") && !ua.contains("Chrome/")) return "Safari";
        if (ua.contains("Firefox/")) return "Firefox";
        return "Browser";
    }

    static String inferOS(String ua) {
        if (ua.contains("iPhone")) return "iPhone";
        if (ua.contains("iPad")) return "iPad";
        if (ua.contains("Android")) return "Android";
        if (ua.contains("Mac OS X")) return "macOS";
        if (ua.contains("Windows")) return "Windows";
        if (ua.contains("Linux")) return "Linux";
        return "Unknown OS";
    }
}
