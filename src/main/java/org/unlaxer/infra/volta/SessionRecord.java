package org.unlaxer.infra.volta;

import java.time.Instant;
import java.util.UUID;

public record SessionRecord(
        UUID id,
        UUID userId,
        UUID tenantId,
        String returnTo,
        Instant createdAt,
        Instant lastActiveAt,
        Instant expiresAt,
        Instant invalidatedAt,
        Instant mfaVerifiedAt,
        String ipAddress,
        String userAgent,
        String csrfToken
) {
    public boolean isValidAt(Instant now) {
        return invalidatedAt == null && expiresAt.isAfter(now);
    }
}
