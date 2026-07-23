package net.codeverse.voice.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceBanTest {

    private static final UUID IDENTITY = UUID.randomUUID();
    private static final long ISSUED = 1_000_000L;

    private static VoiceBan temporary(long expiresAt) {
        return new VoiceBan(IDENTITY, "spam", null, ISSUED, expiresAt, true);
    }

    @Test
    void permanentRestrictionsNeverExpire() {
        VoiceBan ban = new VoiceBan(IDENTITY, "harassment", null, ISSUED, 0L, true);
        assertTrue(ban.isPermanent());
        assertFalse(ban.hasExpired(Long.MAX_VALUE));
        assertTrue(ban.isEnforceable(Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, ban.remainingMillis(ISSUED));
    }

    @Test
    void temporaryRestrictionsLapseOnTheClock() {
        VoiceBan ban = temporary(ISSUED + 60_000L);
        assertTrue(ban.isEnforceable(ISSUED + 30_000L));
        assertFalse(ban.isEnforceable(ISSUED + 60_000L));
        assertTrue(ban.hasExpired(ISSUED + 60_001L));
        assertEquals(30_000L, ban.remainingMillis(ISSUED + 30_000L));
        assertEquals(0L, ban.remainingMillis(ISSUED + 90_000L));
    }

    @Test
    void liftedRestrictionsStopApplyingButAreRetained() {
        VoiceBan lifted = temporary(ISSUED + 60_000L).lifted();
        assertFalse(lifted.active());
        assertFalse(lifted.isEnforceable(ISSUED + 1L));
        assertEquals("spam", lifted.reason());
        assertEquals(ISSUED, lifted.issuedAt());
    }

    @Test
    void rejectsIncoherentRecords() {
        assertThrows(IllegalArgumentException.class,
                () -> new VoiceBan(null, "reason", null, ISSUED, 0L, true));
        assertThrows(IllegalArgumentException.class,
                () -> new VoiceBan(IDENTITY, "  ", null, ISSUED, 0L, true));
        assertThrows(IllegalArgumentException.class,
                () -> new VoiceBan(IDENTITY, "reason", null, 0L, 0L, true));
        assertThrows(IllegalArgumentException.class,
                () -> new VoiceBan(IDENTITY, "reason", null, ISSUED, -1L, true));
    }

    @Test
    void rejectsExpiryBeforeIssue() {
        assertThrows(IllegalArgumentException.class,
                () -> new VoiceBan(IDENTITY, "reason", null, ISSUED, ISSUED - 1L, true));
        assertThrows(IllegalArgumentException.class,
                () -> new VoiceBan(IDENTITY, "reason", null, ISSUED, ISSUED, true));
    }
}
