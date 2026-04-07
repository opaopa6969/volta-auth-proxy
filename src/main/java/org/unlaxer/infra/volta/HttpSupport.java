package org.unlaxer.infra.volta;

import io.javalin.http.Context;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class HttpSupport {
    private HttpSupport() {
    }

    public static boolean wantsJson(Context ctx) {
        String accept = ctx.header("Accept");
        return accept != null && accept.toLowerCase().contains("application/json");
    }

    public static void jsonError(Context ctx, int status, String code, String message) {
        ctx.status(status);
        ctx.json(Map.of("error", Map.of("code", code, "message", message)));
    }

    /**
     * Resolve client IP. Prefers CF-Connecting-IP (Cloudflare Tunnel),
     * then X-Real-IP (Traefik/Nginx), then X-Forwarded-For first entry,
     * then Javalin's ctx.ip() as fallback.
     */
    public static String clientIp(Context ctx) {
        String cfIp = ctx.header("CF-Connecting-IP");
        if (cfIp != null && !cfIp.isBlank()) return cfIp.trim();
        String realIp = ctx.header("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return realIp.trim();
        String forwarded = ctx.header("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return ctx.ip();
    }

    public static boolean isAllowedReturnTo(String returnTo, String allowedDomainsCsv) {
        if (returnTo == null || returnTo.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(returnTo);
        } catch (Exception e) {
            return false;
        }
        if (uri.getHost() == null || !"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost().toLowerCase();
        for (String pattern : allowedDomainsCsv.split(",")) {
            String p = pattern.trim().toLowerCase();
            if (p.isEmpty()) continue;
            if (p.startsWith("*.")) {
                // Wildcard: *.unlaxer.org matches console.unlaxer.org, auth.unlaxer.org, etc.
                String suffix = p.substring(1); // ".unlaxer.org"
                if (host.endsWith(suffix) || host.equals(p.substring(2))) return true;
            } else {
                if (host.equals(p)) return true;
            }
        }
        return false;
    }

    private static final boolean FORCE_SECURE_COOKIE =
            "true".equalsIgnoreCase(System.getenv("FORCE_SECURE_COOKIE"));

    /**
     * Set a session cookie with proper security attributes.
     * Secure flag is added if the request is over HTTPS or FORCE_SECURE_COOKIE=true.
     */
    public static void setSessionCookie(Context ctx, String cookieName, String value, int maxAgeSeconds) {
        String cookieDomain = System.getenv("COOKIE_DOMAIN");
        StringBuilder sb = new StringBuilder();
        sb.append(cookieName).append("=").append(value)
                .append("; Path=/; Max-Age=").append(maxAgeSeconds)
                .append("; HttpOnly; SameSite=Lax");
        if (cookieDomain != null && !cookieDomain.isEmpty()) {
            sb.append("; Domain=").append(cookieDomain);
        }
        if (FORCE_SECURE_COOKIE || ctx.req().isSecure()) {
            sb.append("; Secure");
        }
        ctx.header("Set-Cookie", sb.toString());
    }

    public static int parseOffset(String offsetRaw) {
        if (offsetRaw == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(offsetRaw));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static int parseLimit(String limitRaw) {
        if (limitRaw == null) return 20;
        try {
            return Math.min(100, Math.max(1, Integer.parseInt(limitRaw)));
        } catch (NumberFormatException e) {
            return 20;
        }
    }
}
