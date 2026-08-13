package org.unlaxer.infra.volta;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 滑動ウィンドウ方式（sliding window counter）のレート制限。
 *
 * キーは IP / userId / "ip:endpoint" のいずれか。
 *
 * <h2>なぜ固定ウィンドウをやめたか (#36)</h2>
 * 以前は {@code epochSecond / 60} の分バケットで数える固定ウィンドウだった。
 * この方式はバケットの境界で**上限の2倍が一瞬で通る**。limit=200 のとき
 * 0:59 に 200 件、1:01 にさらに 200 件で、2秒間に 400 件が成立する。
 * 認証エンドポイントのブルートフォース対策としては穴になる。
 *
 * <h2>現在の方式</h2>
 * 直前のバケットのカウントを、現在バケットの経過割合で重み付けして足す:
 * <pre>
 *   推定値 = 前バケット件数 × (1 - 現バケット経過率) + 現バケット件数
 * </pre>
 * 0:59（経過率 ~0.98）の直後 1:01（経過率 ~0.02）では前バケットが 98% の重みで
 * 残るため、境界を跨いだバーストが上限内に収まる。
 * メモリは1キーあたり 2 カウンタで、ログ方式（全リクエストの時刻を持つ）より軽い。
 */
public final class RateLimiter {
    private final int defaultMaxPerMinute;
    private final Map<String, Integer> endpointLimits;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public RateLimiter(int defaultMaxPerMinute) {
        this.defaultMaxPerMinute = defaultMaxPerMinute;
        // Sensitive endpoints get stricter limits
        this.endpointLimits = Map.ofEntries(
                Map.entry("/login", 20),
                Map.entry("/callback", 20),
                Map.entry("/mfa/challenge", 20),
                Map.entry("/auth/callback/complete", 20),
                Map.entry("/auth/passkey/start", 10),
                Map.entry("/auth/passkey/finish", 10),
                Map.entry("/auth/mfa/verify", 10),
                Map.entry("/auth/switch-account", 10),
                Map.entry("/auth/magic-link/send", 10),
                Map.entry("/auth/magic-link/verify", 20),
                Map.entry("/auth/saml/callback", 20),
                Map.entry("/auth/logout", 20),
                Map.entry("/invite/", 20),
                Map.entry("/api/v1/users/", 30)  // prefix match handled in allow()
        );
    }

    public boolean allow(String key) {
        return allow(key, defaultMaxPerMinute);
    }

    /** ウィンドウ幅（秒）。分単位のまま（既存の limit 値の意味を変えない）。 */
    private static final long WINDOW_SECONDS = 60;

    public boolean allow(String key, int limit) {
        long now = Instant.now().getEpochSecond();
        long bucket = now / WINDOW_SECONDS;
        long elapsedInBucket = now % WINDOW_SECONDS;
        // 現バケットの進み具合。進むほど前バケットの重みが下がる。
        double previousWeight = 1.0 - (double) elapsedInBucket / WINDOW_SECONDS;

        Counter counter = counters.compute(key, (k, v) -> {
            if (v == null) return new Counter(bucket, 0, 1);
            if (v.bucketMinute == bucket) {
                v.count++;
                return v;
            }
            if (v.bucketMinute == bucket - 1) {
                // 直前のバケットだけ引き継ぐ（それより古いものは重み 0 なので捨てる）
                return new Counter(bucket, v.count, 1);
            }
            return new Counter(bucket, 0, 1);
        });

        double estimated = counter.previousCount * previousWeight + counter.count;
        // 既存の意味を保つ: カウンタ値が limit に達したら拒否（limit-1 件まで通る）。
        return estimated < limit;
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
        long currentBucket = Instant.now().getEpochSecond() / WINDOW_SECONDS;
        // 直前バケットは重み付けに使うので残す（-1 は保持、-2 より古いものを捨てる）。
        counters.entrySet().removeIf(e -> e.getValue().bucketMinute < currentBucket - 1);
    }

    private static final class Counter {
        private final long bucketMinute;
        /** 直前バケットの件数。滑動ウィンドウの重み付けに使う。 */
        private final int previousCount;
        private int count;

        private Counter(long bucketMinute, int previousCount, int count) {
            this.bucketMinute = bucketMinute;
            this.previousCount = previousCount;
            this.count = count;
        }
    }
}
