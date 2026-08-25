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
    private static final System.Logger LOG = System.getLogger("volta.device-trust");
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
    /** tenant_security_policies に値が無い / 0 のときの上限。 */
    private static final int DEFAULT_MAX_TRUSTED_DEVICES = 10;

    public TrustedDevice trustDevice(Context ctx, UUID userId) {
        return trustDevice(ctx, userId, null);
    }

    /**
     * 信頼済みデバイスを登録する。
     *
     * #45: 上限は 10 のハードコードだった（TODO が残っていた）。
     * {@code tenant_security_policies.max_trusted_devices} 列は既にあり
     * SqlStore も読めるのに、繋がっていなかった。tenantId が分かる呼び出し元は
     * こちらを使うとテナントごとの上限が効く。
     *
     * @param tenantId null なら既定値（{@value #DEFAULT_MAX_TRUSTED_DEVICES}）を使う。
     *                 UserRecord が tenantId を持たないため、userId から引くことは
     *                 できない（members 経由の追加クエリが必要）。呼び出し元が
     *                 principal 等から持っている値を渡す設計にしている。
     */
    public TrustedDevice trustDevice(Context ctx, UUID userId, UUID tenantId) {
        UUID deviceId = UUID.randomUUID();
        String userAgent = ctx.userAgent();
        String ip = HttpSupport.clientIp(ctx);
        String deviceName = DeviceNameResolver.fromUserAgent(userAgent);

        // Enforce max devices (LRU eviction)
        int maxDevices = resolveMaxTrustedDevices(tenantId);
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

    /**
     * テナントの信頼済みデバイス上限を解決する (#45)。
     *
     * policy が無い / 値が 0 以下なら既定値。policy 取得で例外が出ても既定値に
     * 倒す（デバイス登録そのものを止めるほどの話ではない）。
     */
    private int resolveMaxTrustedDevices(UUID tenantId) {
        if (tenantId == null) return DEFAULT_MAX_TRUSTED_DEVICES;
        try {
            return store.findSecurityPolicy(tenantId)
                    .map(SqlStore.TenantSecurityPolicy::maxTrustedDevices)
                    .filter(n -> n > 0)
                    .orElse(DEFAULT_MAX_TRUSTED_DEVICES);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "failed to read tenant_security_policies.max_trusted_devices, using default {0}: {1}",
                    DEFAULT_MAX_TRUSTED_DEVICES, e.toString());
            return DEFAULT_MAX_TRUSTED_DEVICES;
        }
    }

}
