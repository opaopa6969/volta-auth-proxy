package org.unlaxer.infra.volta;

import java.time.Instant;
import java.util.UUID;

/**
 * An invitation record as stored in the database.
 *
 * <p>The invite code is never persisted in plaintext: only its SHA-256 hash
 * ({@link #codeHash}) is stored. The raw code is returned to the caller exactly
 * once at issue time (invite email / API response) and must be hashed via
 * {@link SecurityUtils#sha256Hex(String)} when looking a record up.</p>
 */
public record InvitationRecord(
        UUID id,
        UUID tenantId,
        String codeHash,
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
