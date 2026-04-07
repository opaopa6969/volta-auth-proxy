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
            return new RedisSessionStore(config.redisUrl());
        }
        return new PostgresSessionStore(store);
    }

    @Override
    default void close() {
    }
}

final class PostgresSessionStore implements SessionStore {
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
    private final JedisPooled jedis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    RedisSessionStore(String redisUrl) {
        this.jedis = new JedisPooled(URI.create(redisUrl));
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
            } catch (Exception ignored) {
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
            jedis.set(skey(rec.id()), json);
            long ttl = Math.max(1, rec.expiresAt().getEpochSecond() - Instant.now().getEpochSecond());
            jedis.expire(skey(rec.id()), ttl);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SessionRecord read(UUID sessionId) {
        try {
            String json = jedis.get(skey(sessionId));
            if (json == null) return null;
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
