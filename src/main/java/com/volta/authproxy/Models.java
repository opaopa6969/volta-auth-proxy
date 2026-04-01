package com.volta.authproxy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record UserRecord(UUID id, String email, String displayName, String googleSub) {
}

record TenantRecord(UUID id, String name, String slug) {
}

record MembershipRecord(UUID id, UUID userId, UUID tenantId, String role, boolean active) {
}

record SessionRecord(
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
    boolean isValidAt(Instant now) {
        return invalidatedAt == null && expiresAt.isAfter(now);
    }
}

record InvitationRecord(
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
    boolean isUsableAt(Instant now) {
        return usedCount < maxUses && expiresAt.isAfter(now);
    }
}

record OidcFlowRecord(
        String state,
        String nonce,        // null for GitHub (no id_token)
        String codeVerifier, // null for GitHub (no PKCE)
        String returnTo,
        String inviteCode,
        Instant expiresAt,
        String provider      // GOOGLE | GITHUB | MICROSOFT
) {
}

record OidcIdentity(
        String sub,           // "google:<sub>" | "github:<id>" | "microsoft:<sub>"
        String email,
        String displayName,
        boolean emailVerified,
        String returnTo,
        String inviteCode,
        String provider       // GOOGLE | GITHUB | MICROSOFT
) {
    /** Backwards-compatible alias. */
    public String googleSub() { return sub; }
}

record AuthPrincipal(
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
