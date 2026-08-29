package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 境界値カバレッジ for {@link OutboxWorker#computeRetry(int, int)} の backoff 表.
 *
 * <p>既存 {@link OutboxWorkerRetryTest} は attempt/giveUp 挙動と attempt=1(60s),
 * attempt=2(300s) の backoff を検証するが、3回目以降が 1800s 上限に張り付く
 * こと(order=3 の default 分岐)が未テストだった。backoff が壊れると
 * リトライ間隔が短くなりすぎて下游システムを叩き潰すため回帰しやすい。
 *
 * <p>{@code acceptsEvent} と {@code backoffSeconds} は private で実装を変えない
 * 制約の下では直接テストできないため、{@code computeRetry} の戻り値経由で
 * backoff 境界を固定する。
 */
class OutboxWorkerBackoffBoundaryTest {

    @Test
    void computeRetry_thirdAttemptUsesMaxBackoff() {
        // attemptCount=2 → nextAttempt=3 → backoff(3)=1800s。
        // 3回目のリトライは最大間隔(30分)に達する。
        OutboxWorker.RetryDecision r = OutboxWorker.computeRetry(2, 10);
        assertEquals(3, r.nextAttempt());
        assertFalse(r.giveUp());
        long delta = r.nextAt().getEpochSecond() - Instant.now().getEpochSecond();
        assertEquals(1800, delta, 5);
    }

    @Test
    void computeRetry_fourthAttemptAlsoMaxBackoff() {
        // 3回目以降は default 分岐で 1800s に張り付く。attempt=3 → next=4 も 1800s。
        OutboxWorker.RetryDecision r = OutboxWorker.computeRetry(3, 10);
        assertEquals(4, r.nextAttempt());
        assertFalse(r.giveUp());
        long delta = r.nextAt().getEpochSecond() - Instant.now().getEpochSecond();
        assertEquals(1800, delta, 5);
    }

    @Test
    void computeRetry_backoffIsMonotonicAcrossAttempts() {
        // attempt が増えるほど backoff は増えるか同じ(1800s 上限)。
        // 1 < 2 < 3 = 4 = 5 を、computeRetry 経由で検証する。
        long b1 = backoffViaRetry(0, 10);
        long b2 = backoffViaRetry(1, 10);
        long b3 = backoffViaRetry(2, 10);
        long b4 = backoffViaRetry(3, 10);
        long b5 = backoffViaRetry(4, 10);

        assertEquals(60L, b1);
        assertEquals(300L, b2);
        assertEquals(1800L, b3);
        assertEquals(1800L, b4);
        assertEquals(1800L, b5);
    }

    @Test
    void computeRetry_giveUpAtRetryMaxBoundary() {
        // 実装: nextAttempt = attemptCount + 1; if (nextAttempt >= max(1, retryMax)) giveUp.
        // retryMax=10 のとき nextAttempt=10 で giveUp=true。つまり attemptCount=9 で境界。
        OutboxWorker.RetryDecision r = OutboxWorker.computeRetry(9, 10);
        assertEquals(10, r.nextAttempt());
        assertTrue(r.giveUp());
        // giveUp 時は nextAt=null。これが壊れると「打ち切り」が
        // 次回リトライ時刻として永遠にスケジュールされてしまう。
        assertNull(r.nextAt());

        // 1 つ前 (attemptCount=8) はまだリトライし、backoff(9)=1800s。
        OutboxWorker.RetryDecision still = OutboxWorker.computeRetry(8, 10);
        assertEquals(9, still.nextAttempt());
        assertFalse(still.giveUp());
    }

    private static long backoffViaRetry(int attemptCount, int retryMax) {
        OutboxWorker.RetryDecision r = OutboxWorker.computeRetry(attemptCount, retryMax);
        return r.nextAt().getEpochSecond() - Instant.now().getEpochSecond();
    }
}
