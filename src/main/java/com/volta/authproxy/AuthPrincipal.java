package com.volta.authproxy;

import java.util.List;
import java.util.UUID;

public record AuthPrincipal(
        UUID userId,
        String email,
        String displayName,
        UUID tenantId,
        String tenantName,
        String tenantSlug,
        List<String> roles,
        boolean serviceToken
) {
}
