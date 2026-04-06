package com.volta.authproxy;

import java.time.Instant;
import java.util.UUID;

public record InvitationRecord(
        UUID id,
        UUID tenantId,
        String code,
        String email,
        String role,
        int maxUses,
        int usedCount,
        UUID createdBy,
        Instant expiresAt
) {
    public boolean isUsableAt(Instant now) {
        return usedCount < maxUses && expiresAt.isAfter(now);
    }
}
