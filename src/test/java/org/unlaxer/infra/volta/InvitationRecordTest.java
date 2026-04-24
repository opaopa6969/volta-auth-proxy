package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InvitationRecordTest {

    private InvitationRecord invite(int maxUses, int usedCount, Instant expiresAt) {
        return new InvitationRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "code123",
                "user@example.com",
                "MEMBER",
                maxUses,
                usedCount,
                UUID.randomUUID(),
                expiresAt
        );
    }

    @Test
    void usableWhenNotExpiredAndUsesRemaining() {
        var inv = invite(5, 2, Instant.now().plusSeconds(3600));
        assertTrue(inv.isUsableAt(Instant.now()));
    }

    @Test
    void notUsableWhenExpired() {
        var inv = invite(5, 2, Instant.now().minusSeconds(1));
        assertFalse(inv.isUsableAt(Instant.now()));
    }

    @Test
    void notUsableWhenUsedCountEqualsMaxUses() {
        var inv = invite(3, 3, Instant.now().plusSeconds(3600));
        assertFalse(inv.isUsableAt(Instant.now()));
    }

    @Test
    void notUsableWhenUsedCountExceedsMaxUses() {
        var inv = invite(3, 4, Instant.now().plusSeconds(3600));
        assertFalse(inv.isUsableAt(Instant.now()));
    }

    @Test
    void usableOnFirstUseOfSingleUseInvite() {
        var inv = invite(1, 0, Instant.now().plusSeconds(3600));
        assertTrue(inv.isUsableAt(Instant.now()));
    }

    @Test
    void notUsableWhenExpiredAndUsesRemaining() {
        var inv = invite(10, 0, Instant.now().minusSeconds(60));
        assertFalse(inv.isUsableAt(Instant.now()));
    }
}
