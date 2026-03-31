package com.volta.authproxy;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RateLimiter {
    private final int maxPerMinute;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public RateLimiter(int maxPerMinute) {
        this.maxPerMinute = maxPerMinute;
    }

    public boolean allow(String key) {
        long bucket = Instant.now().getEpochSecond() / 60;
        Counter counter = counters.compute(key, (k, v) -> {
            if (v == null || v.bucketMinute != bucket) {
                return new Counter(bucket, 1);
            }
            v.count++;
            return v;
        });
        return counter.count <= maxPerMinute;
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
