package com.volta.authproxy;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window-ish rate limiter with per-key counters.
 * Keys can be: IP, userId, or composite "ip:endpoint".
 */
public final class RateLimiter {
    private final int defaultMaxPerMinute;
    private final Map<String, Integer> endpointLimits;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public RateLimiter(int defaultMaxPerMinute) {
        this.defaultMaxPerMinute = defaultMaxPerMinute;
        // Sensitive endpoints get stricter limits
        this.endpointLimits = Map.of(
                "/login", 20,
                "/auth/passkey/start", 10,
                "/auth/passkey/finish", 10,
                "/auth/mfa/verify", 10,
                "/auth/switch-account", 10,
                "/api/v1/users/", 30  // prefix match handled in allow()
        );
    }

    public boolean allow(String key) {
        return allow(key, defaultMaxPerMinute);
    }

    public boolean allow(String key, int limit) {
        long bucket = Instant.now().getEpochSecond() / 60;
        Counter counter = counters.compute(key, (k, v) -> {
            if (v == null || v.bucketMinute != bucket) {
                return new Counter(bucket, 1);
            }
            v.count++;
            return v;
        });
        return counter.count <= limit;
    }

    /**
     * Check rate limit by IP + endpoint path. Uses stricter limits for sensitive endpoints.
     */
    public boolean allowRequest(String ip, String path) {
        int limit = defaultMaxPerMinute;
        for (var entry : endpointLimits.entrySet()) {
            if (path.startsWith(entry.getKey())) {
                limit = entry.getValue();
                break;
            }
        }
        return allow(ip + ":" + path, limit);
    }

    /**
     * Evict expired counters (call periodically to prevent memory leak).
     */
    public void evictExpired() {
        long currentBucket = Instant.now().getEpochSecond() / 60;
        counters.entrySet().removeIf(e -> e.getValue().bucketMinute < currentBucket - 1);
    }

    private static final class Counter {
        private final long bucketMinute;
        private int count;

        private Counter(long bucketMinute, int count) {
            this.bucketMinute = bucketMinute;
            this.count = count;
        }
    }
}
