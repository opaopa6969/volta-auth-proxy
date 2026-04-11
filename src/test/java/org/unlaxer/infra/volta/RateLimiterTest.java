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
}
