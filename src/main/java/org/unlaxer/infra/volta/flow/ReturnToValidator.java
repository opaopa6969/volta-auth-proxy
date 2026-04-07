package org.unlaxer.infra.volta.flow;

import java.net.URI;
import java.util.Set;

/**
 * Validates return_to URLs to prevent open redirect attacks.
 * Allows relative paths with approved prefixes and absolute URLs with approved domains.
 */
public final class ReturnToValidator {
    private static final Set<String> ALLOWED_PATH_PREFIXES = Set.of(
            "/console/", "/invite/", "/settings/", "/mfa/", "/step-up", "/select-tenant"
    );

    private final Set<String> allowedDomains;

    public ReturnToValidator(String allowedDomainsCsv) {
        this.allowedDomains = Set.of(allowedDomainsCsv.split(","));
    }

    public String validateOrNull(String returnTo) {
        if (returnTo == null || returnTo.isBlank()) return null;

        // Relative path — check prefix whitelist
        if (returnTo.startsWith("/")) {
            for (String prefix : ALLOWED_PATH_PREFIXES) {
                if (returnTo.startsWith(prefix) || returnTo.equals(prefix.replaceAll("/$", ""))) {
                    return returnTo;
                }
            }
            return null;
        }

        // Absolute URL — check domain whitelist
        try {
            URI uri = URI.create(returnTo);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                return null;
            }
            if (uri.getHost() != null && allowedDomains.contains(uri.getHost().trim())) {
                return returnTo;
            }
        } catch (Exception e) {
            // invalid URI
        }
        return null;
    }
}
