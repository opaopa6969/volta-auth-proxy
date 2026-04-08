package org.unlaxer.infra.volta;

import io.javalin.http.Context;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages trusted devices via Persistent Cookie (__volta_device_trust).
 * Checks if a login is from a known device and handles trust lifecycle.
 */
public final class DeviceTrustService {
    public static final String DEVICE_COOKIE = "__volta_device_trust";
    private static final int COOKIE_MAX_AGE = 7_776_000; // 90 days

    private final SqlStore store;

    public DeviceTrustService(SqlStore store) {
        this.store = store;
    }

    /**
     * Check if the current request is from a trusted device.
     * @return the TrustedDevice if known, empty if new device
     */
    public Optional<TrustedDevice> checkDevice(Context ctx, UUID userId) {
        String cookieValue = ctx.cookie(DEVICE_COOKIE);
        if (cookieValue == null || cookieValue.isBlank()) {
            return Optional.empty();
        }
        try {
            UUID deviceId = UUID.fromString(cookieValue);
            return store.findTrustedDevice(userId, deviceId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Register a new trusted device. Issues cookie and stores in DB.
     */
    public TrustedDevice trustDevice(Context ctx, UUID userId) {
        UUID deviceId = UUID.randomUUID();
        String userAgent = ctx.userAgent();
        String ip = HttpSupport.clientIp(ctx);
        String deviceName = DeviceNameResolver.fromUserAgent(userAgent);

        // Enforce max devices (LRU eviction)
        int maxDevices = 10; // TODO: read from tenant_security_policies
        store.evictOldestDevices(userId, maxDevices - 1);

        store.createTrustedDevice(userId, deviceId, deviceName, userAgent, ip);
        setDeviceCookie(ctx, deviceId);

        return new TrustedDevice(null, userId, deviceId, deviceName, userAgent, ip, null, null);
    }

    /**
     * Refresh the cookie and last_seen_at for an existing trusted device.
     */
    public void refreshDevice(Context ctx, UUID userId, UUID deviceId) {
        store.touchTrustedDevice(userId, deviceId);
        setDeviceCookie(ctx, deviceId);
    }

    /**
     * Remove a specific trusted device.
     */
    public void removeDevice(UUID userId, UUID deviceId) {
        store.deleteTrustedDevice(userId, deviceId);
    }

    /**
     * Remove all trusted devices for a user (e.g., password/MFA reset).
     */
    public void removeAllDevices(UUID userId) {
        store.deleteAllTrustedDevices(userId);
    }

    /**
     * List all trusted devices for a user.
     */
    public List<TrustedDevice> listDevices(UUID userId) {
        return store.listTrustedDevices(userId);
    }

    /**
     * Clear the device trust cookie.
     */
    public void clearCookie(Context ctx) {
        ctx.res().addHeader("Set-Cookie", DEVICE_COOKIE + "=; Path=/login; Max-Age=0; HttpOnly; SameSite=Lax");
    }

    private void setDeviceCookie(Context ctx, UUID deviceId) {
        String cookie = DEVICE_COOKIE + "=" + deviceId
                + "; Path=/login; Max-Age=" + COOKIE_MAX_AGE
                + "; HttpOnly; SameSite=Lax";
        if (ctx.req().isSecure()) cookie += "; Secure";
        ctx.res().addHeader("Set-Cookie", cookie);
    }

    // ─── Record ─────────────────────────────────────────────

    public record TrustedDevice(
            UUID id, UUID userId, UUID deviceId, String deviceName,
            String userAgent, String ipAddress,
            java.time.Instant createdAt, java.time.Instant lastSeenAt
    ) {}
}
