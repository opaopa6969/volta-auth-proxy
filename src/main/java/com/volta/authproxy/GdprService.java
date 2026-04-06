package com.volta.authproxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GDPR data export and right to erasure.
 * Phase 1: synchronous export, 30-day grace period deletion.
 */
public final class GdprService {
    private static final int GRACE_PERIOD_DAYS = 30;

    private final SqlStore store;
    private final SessionStore sessionStore;
    private final DeviceTrustService deviceTrustService;
    private final ObjectMapper objectMapper;

    public GdprService(SqlStore store, SessionStore sessionStore,
                       DeviceTrustService deviceTrustService, ObjectMapper objectMapper) {
        this.store = store;
        this.sessionStore = sessionStore;
        this.deviceTrustService = deviceTrustService;
        this.objectMapper = objectMapper;
    }

    /**
     * Export all user data as JSON.
     * Excludes: TOTP secrets, passkey private keys, recovery code hashes.
     */
    public String exportUserData(UUID userId) {
        var user = store.findUserById(userId)
                .orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "User not found"));

        ObjectNode root = objectMapper.createObjectNode();
        root.put("exported_at", Instant.now().toString());

        // User
        ObjectNode userNode = root.putObject("user");
        userNode.put("id", user.id().toString());
        userNode.put("email", user.email());
        userNode.put("display_name", user.displayName());

        // Memberships
        ArrayNode memberships = root.putArray("memberships");
        for (var info : store.findTenantInfosByUser(userId)) {
            ObjectNode m = memberships.addObject();
            m.put("tenant_id", info.id().toString());
            m.put("tenant_name", info.name());
            m.put("role", info.role());
        }

        // Sessions
        ArrayNode sessions = root.putArray("sessions");
        for (var s : sessionStore.listUserSessions(userId)) {
            ObjectNode sn = sessions.addObject();
            sn.put("id", s.id().toString());
            sn.put("ip_address", maskIp(s.ipAddress()));
            sn.put("user_agent", s.userAgent());
            sn.put("created_at", s.createdAt().toString());
            sn.put("expires_at", s.expiresAt().toString());
        }

        // Trusted devices
        ArrayNode devices = root.putArray("trusted_devices");
        for (var d : deviceTrustService.listDevices(userId)) {
            ObjectNode dn = devices.addObject();
            dn.put("device_name", d.deviceName());
            dn.put("last_seen_at", d.lastSeenAt() != null ? d.lastSeenAt().toString() : null);
        }

        // MFA status (no secrets)
        var mfa = store.findUserMfa(userId, "totp");
        ObjectNode mfaNode = root.putObject("mfa");
        mfaNode.put("totp_enabled", mfa.isPresent() && mfa.get().active());

        // Passkeys (metadata only, no keys)
        ArrayNode passkeys = root.putArray("passkeys");
        for (var pk : store.listPasskeys(userId)) {
            ObjectNode pn = passkeys.addObject();
            pn.put("name", pk.name());
            pn.put("created_at", pk.createdAt() != null ? pk.createdAt().toString() : null);
        }

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize export data", e);
        }
    }

    /**
     * Request account deletion (30-day grace period).
     */
    public Instant requestDeletion(UUID userId) {
        // Soft delete
        store.softDeleteUser(userId);

        // Invalidate all sessions
        sessionStore.revokeAllSessions(userId);

        // Invalidate all trusted devices
        deviceTrustService.removeAllDevices(userId);

        return Instant.now().plusSeconds(GRACE_PERIOD_DAYS * 86400L);
    }

    /**
     * Cancel a pending deletion (user logged back in within grace period).
     */
    public void cancelDeletion(UUID userId) {
        store.cancelUserDeletion(userId);
    }

    /**
     * Execute hard deletion for users past the grace period.
     * Called by a scheduled GC job.
     */
    public int executeHardDeletions() {
        List<UUID> expired = store.findUsersToHardDelete(GRACE_PERIOD_DAYS);
        for (UUID userId : expired) {
            store.hardDeleteUser(userId);
        }
        return expired.size();
    }

    private static String maskIp(String ip) {
        if (ip == null) return null;
        int lastDot = ip.lastIndexOf('.');
        if (lastDot < 0) return ip;
        return ip.substring(0, lastDot) + ".***";
    }
}
