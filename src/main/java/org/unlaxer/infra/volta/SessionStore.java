package org.unlaxer.infra.volta;

import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.time.Instant;
import java.util.*;

interface SessionStore extends AutoCloseable {
    void createSession(UUID sessionId, UUID userId, UUID tenantId, String returnTo, Instant expiresAt, Instant mfaVerifiedAt, String ip, String userAgent, String csrfToken);

    Optional<SessionRecord> findSession(UUID sessionId);

    void touchSession(UUID sessionId, Instant expiresAt);

    void markSessionMfaVerified(UUID sessionId);

    void revokeSession(UUID sessionId);

    void revokeAllSessions(UUID userId);

    List<SessionRecord> listUserSessions(UUID userId);

    int countActiveSessions(UUID userId);

    int revokeOldestActiveSessions(UUID userId, int count);

    void revokeSessionsForUserTenant(UUID userId, UUID tenantId);

    // Passkey challenge storage (short-lived, in-memory is fine)
    java.util.concurrent.ConcurrentHashMap<String, String> PASSKEY_CHALLENGES = new java.util.concurrent.ConcurrentHashMap<>();

    default void setPasskeyChallenge(String sessionCookie, String challenge) {
        if (sessionCookie != null) PASSKEY_CHALLENGES.put("pk:" + sessionCookie, challenge);
    }

    default String getPasskeyChallenge(String sessionCookie) {
        return sessionCookie != null ? PASSKEY_CHALLENGES.get("pk:" + sessionCookie) : null;
    }

    default void clearPasskeyChallenge(String sessionCookie) {
        if (sessionCookie != null) PASSKEY_CHALLENGES.remove("pk:" + sessionCookie);
    }

    static SessionStore create(AppConfig config, SqlStore store) {
        if ("redis".equalsIgnoreCase(config.sessionStore())) {
            // #37: Redis に平文 JSON で置くと csrf_token / userId / returnTo が
            // Redis を見られる人（運用者・同一ホストの他プロセス・スナップショット）に
            // 読める。Postgres 側は RLS (V24) で守られているが Redis は運用モデルが
            // 違うので、保存時に暗号化する。
            //
            // 鍵は SESSION_ENCRYPTION_SECRET があればそれを使い、無ければ
            // JWT_KEY_ENCRYPTION_SECRET から派生する（新しい env を必須にすると
            // 既存デプロイが起動しなくなるため）。分離しておくと、セッション鍵を
            // 回しても JWT 鍵に影響しない（セッションは 8h で切れるので、回した
            // 瞬間に全員ログアウトするだけで済む）。
            return new RedisSessionStore(config.redisUrl(), config.sessionEncryptionSecret());
        }
        return new PostgresSessionStore(store);
    }

    @Override
    default void close() {
    }
}

final class PostgresSessionStore implements SessionStore {
    private static final System.Logger LOG = System.getLogger("volta.session");
    private final SqlStore store;

    PostgresSessionStore(SqlStore store) {
        this.store = store;
    }

    @Override
    public void createSession(UUID sessionId, UUID userId, UUID tenantId, String returnTo, Instant expiresAt, Instant mfaVerifiedAt, String ip, String userAgent, String csrfToken) {
        store.createSession(sessionId, userId, tenantId, returnTo, expiresAt, mfaVerifiedAt, ip, userAgent, csrfToken);
    }

    @Override
    public Optional<SessionRecord> findSession(UUID sessionId) {
        return store.findSession(sessionId);
    }

    @Override
    public void touchSession(UUID sessionId, Instant expiresAt) {
        store.touchSession(sessionId, expiresAt);
    }

    @Override
    public void markSessionMfaVerified(UUID sessionId) {
        store.markSessionMfaVerified(sessionId);
    }

    @Override
    public void revokeSession(UUID sessionId) {
        store.revokeSession(sessionId);
    }

    @Override
    public void revokeAllSessions(UUID userId) {
        store.revokeAllSessions(userId);
    }

    @Override
    public List<SessionRecord> listUserSessions(UUID userId) {
        return store.listUserSessions(userId);
    }

    @Override
    public int countActiveSessions(UUID userId) {
        return store.countActiveSessions(userId);
    }

    @Override
    public int revokeOldestActiveSessions(UUID userId, int count) {
        return store.revokeOldestActiveSessions(userId, count);
    }

    @Override
    public void revokeSessionsForUserTenant(UUID userId, UUID tenantId) {
        store.revokeSessionsForUserTenant(userId, tenantId);
    }
}

final class RedisSessionStore implements SessionStore {
    private static final System.Logger LOG = System.getLogger("volta.session.redis");
    private final JedisPooled jedis;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** #37: Redis に置く前に AES-GCM で暗号化する。null なら暗号化しない（後方互換）。 */
    private final KeyCipher cipher;

    RedisSessionStore(String redisUrl) {
        this(redisUrl, null);
    }

    RedisSessionStore(String redisUrl, String encryptionSecret) {
        this.jedis = new JedisPooled(URI.create(redisUrl));
        if (encryptionSecret == null || encryptionSecret.isBlank()) {
            this.cipher = null;
            LOG.log(System.Logger.Level.WARNING,
                    "session encryption is disabled: sessions will be stored in Redis as plaintext JSON. "
                    + "Set SESSION_ENCRYPTION_SECRET or JWT_KEY_ENCRYPTION_SECRET (#37)");
        } else {
            this.cipher = new KeyCipher(encryptionSecret);
        }
    }

    private static String skey(UUID sessionId) {
        return "volta:session:" + sessionId;
    }

    private static String ukey(UUID userId) {
        return "volta:user_sessions:" + userId;
    }

    @Override
    public void createSession(UUID sessionId, UUID userId, UUID tenantId, String returnTo, Instant expiresAt, Instant mfaVerifiedAt, String ip, String userAgent, String csrfToken) {
        SessionRecord rec = new SessionRecord(
                sessionId, userId, tenantId, returnTo,
                Instant.now(), Instant.now(), expiresAt, null, mfaVerifiedAt, ip, userAgent, csrfToken
        );
        write(rec);
        jedis.zadd(ukey(userId), rec.createdAt().toEpochMilli(), sessionId.toString());
    }

    @Override
    public Optional<SessionRecord> findSession(UUID sessionId) {
        SessionRecord rec = read(sessionId);
        if (rec == null) return Optional.empty();
        if (!rec.isValidAt(Instant.now())) return Optional.empty();
        return Optional.of(rec);
    }

    @Override
    public void touchSession(UUID sessionId, Instant expiresAt) {
        SessionRecord rec = read(sessionId);
        if (rec == null) return;
        SessionRecord next = new SessionRecord(
                rec.id(), rec.userId(), rec.tenantId(), rec.returnTo(), rec.createdAt(), Instant.now(),
                expiresAt, rec.invalidatedAt(), rec.mfaVerifiedAt(), rec.ipAddress(), rec.userAgent(), rec.csrfToken()
        );
        write(next);
    }

    @Override
    public void markSessionMfaVerified(UUID sessionId) {
        SessionRecord rec = read(sessionId);
        if (rec == null) return;
        SessionRecord next = new SessionRecord(
                rec.id(), rec.userId(), rec.tenantId(), rec.returnTo(), rec.createdAt(), rec.lastActiveAt(),
                rec.expiresAt(), rec.invalidatedAt(), Instant.now(), rec.ipAddress(), rec.userAgent(), rec.csrfToken()
        );
        write(next);
    }

    @Override
    public void revokeSession(UUID sessionId) {
        SessionRecord rec = read(sessionId);
        if (rec == null) return;
        SessionRecord next = new SessionRecord(
                rec.id(), rec.userId(), rec.tenantId(), rec.returnTo(), rec.createdAt(), rec.lastActiveAt(),
                rec.expiresAt(), Instant.now(), rec.mfaVerifiedAt(), rec.ipAddress(), rec.userAgent(), rec.csrfToken()
        );
        write(next);
    }

    @Override
    public void revokeAllSessions(UUID userId) {
        for (SessionRecord rec : listUserSessions(userId)) {
            revokeSession(rec.id());
        }
    }

    @Override
    public List<SessionRecord> listUserSessions(UUID userId) {
        List<String> ids = jedis.zrevrange(ukey(userId), 0, 49);
        List<SessionRecord> out = new ArrayList<>();
        for (String id : ids) {
            try {
                SessionRecord rec = read(UUID.fromString(id));
                if (rec != null) out.add(rec);
            } catch (Exception e) {
                // 1件の読み取り失敗で一覧全体を落とさない。ただし壊れた
                // セッションが溜まっていることに気付けるようログを残す (#40)。
                LOG.log(System.Logger.Level.WARNING,
                        "failed to read session {0} while listing (skipped): {1}", id, e.toString());
            }
        }
        return out;
    }

    @Override
    public int countActiveSessions(UUID userId) {
        int count = 0;
        Instant now = Instant.now();
        for (SessionRecord rec : listUserSessions(userId)) {
            if (rec.isValidAt(now)) count++;
        }
        return count;
    }

    @Override
    public int revokeOldestActiveSessions(UUID userId, int count) {
        if (count <= 0) return 0;
        List<SessionRecord> all = new ArrayList<>(listUserSessions(userId));
        all.sort(Comparator.comparing(SessionRecord::createdAt));
        int revoked = 0;
        for (SessionRecord rec : all) {
            if (revoked >= count) break;
            if (!rec.isValidAt(Instant.now())) continue;
            revokeSession(rec.id());
            revoked++;
        }
        return revoked;
    }

    @Override
    public void revokeSessionsForUserTenant(UUID userId, UUID tenantId) {
        for (SessionRecord rec : listUserSessions(userId)) {
            if (tenantId.equals(rec.tenantId())) {
                revokeSession(rec.id());
            }
        }
    }

    @Override
    public void close() {
        jedis.close();
    }

    private void write(SessionRecord rec) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", rec.id().toString());
            payload.put("userId", rec.userId().toString());
            payload.put("tenantId", rec.tenantId().toString());
            payload.put("returnTo", rec.returnTo());
            payload.put("createdAt", rec.createdAt().toString());
            payload.put("lastActiveAt", rec.lastActiveAt().toString());
            payload.put("expiresAt", rec.expiresAt().toString());
            payload.put("invalidatedAt", rec.invalidatedAt() == null ? null : rec.invalidatedAt().toString());
            payload.put("mfaVerifiedAt", rec.mfaVerifiedAt() == null ? null : rec.mfaVerifiedAt().toString());
            payload.put("ipAddress", rec.ipAddress());
            payload.put("userAgent", rec.userAgent());
            payload.put("csrfToken", rec.csrfToken());
            String json = objectMapper.writeValueAsString(payload);
            jedis.set(skey(rec.id()), cipher == null ? json : cipher.encrypt(json));
            long ttl = Math.max(1, rec.expiresAt().getEpochSecond() - Instant.now().getEpochSecond());
            jedis.expire(skey(rec.id()), ttl);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SessionRecord read(UUID sessionId) {
        try {
            String stored = jedis.get(skey(sessionId));
            if (stored == null) return null;
            // KeyCipher.decrypt は "v1:"/"v2:" 以外をそのまま返すので、
            // 暗号化を有効にする前に書かれた平文セッションも読める（移行のため）。
            String json = cipher == null ? stored : cipher.decrypt(stored);
            Map<?, ?> m = objectMapper.readValue(json, Map.class);
            return new SessionRecord(
                    UUID.fromString((String) m.get("id")),
                    UUID.fromString((String) m.get("userId")),
                    UUID.fromString((String) m.get("tenantId")),
                    (String) m.get("returnTo"),
                    Instant.parse((String) m.get("createdAt")),
                    Instant.parse((String) m.get("lastActiveAt")),
                    Instant.parse((String) m.get("expiresAt")),
                    m.get("invalidatedAt") == null ? null : Instant.parse((String) m.get("invalidatedAt")),
                    m.get("mfaVerifiedAt") == null ? null : Instant.parse((String) m.get("mfaVerifiedAt")),
                    (String) m.get("ipAddress"),
                    (String) m.get("userAgent"),
                    (String) m.get("csrfToken")
            );
        } catch (Exception e) {
            return null;
        }
    }
}
