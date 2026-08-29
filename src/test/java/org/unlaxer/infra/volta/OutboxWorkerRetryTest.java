package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #71: OutboxWorker 外側 catch でのリトライ管理 — computeRetry が
 * attemptCount を進め、webhookRetryMax 到達で打ち切り、未達なら backoff 適用済み
 * nextAt を返すことを検証する。
 */
class OutboxWorkerRetryTest {

    @Test
    void advancesAttemptAndAppliesBackoff() {
        OutboxWorker.RetryDecision r = OutboxWorker.computeRetry(0, 3);
        assertEquals(1, r.nextAttempt());
        assertTrue(!r.giveUp());
        assertNotNull(r.nextAt());
        // backoff(1) = 60s → nextAt は約60秒後
        assertTrue(r.nextAt().isAfter(Instant.now().plusSeconds(55)));
        assertTrue(r.nextAt().isBefore(Instant.now().plusSeconds(65)));
    }

    @Test
    void secondRetryUsesLongerBackoff() {
        OutboxWorker.RetryDecision r = OutboxWorker.computeRetry(1, 3);
        assertEquals(2, r.nextAttempt());
        assertTrue(!r.giveUp());
        // backoff(2) = 300s
        assertTrue(r.nextAt().isAfter(Instant.now().plusSeconds(295)));
    }

    @Test
    void givesUpAtRetryMax() {
        OutboxWorker.RetryDecision r = OutboxWorker.computeRetry(2, 3);
        assertEquals(3, r.nextAttempt());
        assertTrue(r.giveUp());
        assertNull(r.nextAt());
    }

    @Test
    void givesUpWhenRetryMaxIsOne() {
        // webhookRetryMax=1 → 初回失敗で即打ち切り
        OutboxWorker.RetryDecision r = OutboxWorker.computeRetry(0, 1);
        assertTrue(r.giveUp());
        assertNull(r.nextAt());
    }

    @Test
    void negativeRetryMaxClampedToOne() {
        OutboxWorker.RetryDecision r = OutboxWorker.computeRetry(0, -1);
        assertTrue(r.giveUp());
    }
}
