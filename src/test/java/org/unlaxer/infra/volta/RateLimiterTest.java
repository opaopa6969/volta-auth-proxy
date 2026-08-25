package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    @Test
    void allowsWithinLimit() {
        RateLimiter limiter = new RateLimiter(11);
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.allow("user1"));
        }
    }

    @Test
    void blocksAtLimit() {
        // limit=5 means counter values 1..4 pass (< 5), counter=5 is blocked
        RateLimiter limiter = new RateLimiter(5);
        for (int i = 0; i < 4; i++) {
            assertTrue(limiter.allow("user1"));
        }
        assertFalse(limiter.allow("user1"));
    }

    @Test
    void separateKeysIndependent() {
        RateLimiter limiter = new RateLimiter(4);
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.allow("user1"));
            assertTrue(limiter.allow("user2"));
        }
        assertFalse(limiter.allow("user1"));
        assertFalse(limiter.allow("user2"));
    }

    @Test
    void endpointSpecificLimits() {
        RateLimiter limiter = new RateLimiter(200);
        // Login has 20/min limit; counter starts at 1, so 19 allowed (1..19 < 20)
        for (int i = 0; i < 19; i++) {
            assertTrue(limiter.allowRequest("1.2.3.4", "/login"));
        }
        assertFalse(limiter.allowRequest("1.2.3.4", "/login"));
        // Different IP is still allowed
        assertTrue(limiter.allowRequest("5.6.7.8", "/login"));
    }

    @Test
    void evictExpired() {
        RateLimiter limiter = new RateLimiter(5);
        limiter.allow("old-key");
        limiter.evictExpired(); // should not throw
    }

    /**
     * #36: 固定ウィンドウだとバケット境界で上限の2倍が通る。滑動ウィンドウでは
     * 直前バケットの件数が重み付きで残るので、境界を跨いでも上限内に収まる。
     *
     * 実時間に依存させずに検証するため、境界の前後で「同じキーに対する許可数の合計」が
     * 上限の2倍にならないことを、バケットを跨いだ状態を作って確認する。
     * （Instant を差し替えられない実装なので、ここでは「前バケットの件数が
     *   引き継がれる」ことを内部挙動として見る代わりに、単一ウィンドウ内で
     *   上限を超えないことと、キーごとに独立していることを固定する。）
     */
    @Test
    void doesNotAllowDoubleTheLimitWithinOneWindow() {
        RateLimiter limiter = new RateLimiter(10);
        int allowed = 0;
        for (int i = 0; i < 50; i++) {
            if (limiter.allow("burst-key")) allowed++;
        }
        // 固定ウィンドウでも滑動ウィンドウでも、1ウィンドウ内では limit-1 件まで。
        assertEquals(9, allowed, "1ウィンドウ内で通るのは limit-1 件");
    }

    /** evictExpired は直前バケットを消さない（滑動ウィンドウの重み付けに必要）。 */
    @Test
    void evictExpiredKeepsRecentCounters() {
        RateLimiter limiter = new RateLimiter(5);
        assertTrue(limiter.allow("k"));
        limiter.evictExpired();
        // 直後の evict でカウンタが消えていたら、上限までの残りが増えてしまう
        assertTrue(limiter.allow("k"));
        assertTrue(limiter.allow("k"));
        assertTrue(limiter.allow("k"));
        assertFalse(limiter.allow("k"), "evict 後もカウントは維持される");
    }

}
