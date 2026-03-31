package com.volta.authproxy;

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
        Set<String> allowedDomains = Arrays.stream(allowedDomainsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
        return allowedDomains.contains(uri.getHost());
    }
}
