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

    @Test
    void mapsToApiRestrictionPreservingEveryField() {
        UUID issuer = UUID.randomUUID();
        VoiceBan ban = new VoiceBan(IDENTITY, "harassment", issuer, ISSUED, ISSUED + 60_000L, true);

        net.codeverse.api.voice.VoiceRestriction api = ban.toApi();

        assertEquals(IDENTITY, api.internalId());
        assertEquals("harassment", api.reason());
        assertEquals(java.util.Optional.of(issuer), api.issuedBy());
        assertEquals(java.time.Instant.ofEpochMilli(ISSUED), api.issuedAt());
        assertEquals(java.util.Optional.of(java.time.Instant.ofEpochMilli(ISSUED + 60_000L)), api.expiresAt());
        assertTrue(api.active());
    }

    @Test
    void mapsStorageSentinelsToApiAbsences() {
        // A null issuer becomes an empty optional and the zero permanent
        // sentinel becomes an empty expiry, so a consumer of the API never
        // has to know the storage encoding to read a restriction correctly.
        VoiceBan consolePermanent = new VoiceBan(IDENTITY, "console ban", null, ISSUED, 0L, true);

        net.codeverse.api.voice.VoiceRestriction api = consolePermanent.toApi();

        assertEquals(java.util.Optional.empty(), api.issuedBy());
        assertEquals(java.util.Optional.empty(), api.expiresAt());
        assertTrue(api.isPermanent());
    }
}
