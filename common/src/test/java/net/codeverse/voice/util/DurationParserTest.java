package net.codeverse.voice.util;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationParserTest {

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60L * SECOND;
    private static final long HOUR = 60L * MINUTE;
    private static final long DAY = 24L * HOUR;
    private static final long WEEK = 7L * DAY;

    @Test
    void parsesSingleUnits() {
        assertEquals(Optional.of(30L * SECOND), DurationParser.parse("30s"));
        assertEquals(Optional.of(15L * MINUTE), DurationParser.parse("15m"));
        assertEquals(Optional.of(2L * HOUR), DurationParser.parse("2h"));
        assertEquals(Optional.of(7L * DAY), DurationParser.parse("7d"));
        assertEquals(Optional.of(2L * WEEK), DurationParser.parse("2w"));
    }

    @Test
    void parsesCompoundDurations() {
        assertEquals(Optional.of(DAY + 12L * HOUR), DurationParser.parse("1d12h"));
        assertEquals(Optional.of(HOUR + 30L * MINUTE), DurationParser.parse("1h30m"));
        assertEquals(Optional.of(WEEK + 2L * DAY + 3L * HOUR), DurationParser.parse("1w2d3h"));
    }

    @Test
    void isCaseInsensitiveAndTrimsWhitespace() {
        assertEquals(Optional.of(2L * HOUR), DurationParser.parse("  2H  "));
        assertEquals(Optional.of(DAY), DurationParser.parse("1D"));
    }

    @Test
    void rejectsBareNumbersBecauseTheUnitWouldBeGuessed() {
        assertTrue(DurationParser.parse("30").isEmpty());
        assertTrue(DurationParser.parse("1d30").isEmpty());
    }

    @Test
    void rejectsMalformedInput() {
        assertTrue(DurationParser.parse(null).isEmpty());
        assertTrue(DurationParser.parse("").isEmpty());
        assertTrue(DurationParser.parse("   ").isEmpty());
        assertTrue(DurationParser.parse("abc").isEmpty());
        assertTrue(DurationParser.parse("d").isEmpty());
        assertTrue(DurationParser.parse("-5m").isEmpty());
        assertTrue(DurationParser.parse("5y").isEmpty());
        assertTrue(DurationParser.parse("0s").isEmpty());
    }

    @Test
    void clampsAbsurdDurationsInsteadOfOverflowing() {
        Optional<Long> parsed = DurationParser.parse("999999999999999999w");
        assertTrue(parsed.isPresent());
        assertEquals(DurationParser.MAXIMUM_MILLIS, parsed.get());
    }

    @Test
    void recognisesPermanentAliases() {
        assertTrue(DurationParser.isPermanent("perm"));
        assertTrue(DurationParser.isPermanent("PERMANENT"));
        assertTrue(DurationParser.isPermanent(" forever "));
        assertFalse(DurationParser.isPermanent("30m"));
        assertFalse(DurationParser.isPermanent(null));
    }

    @Test
    void formatsWithAtMostTwoUnits() {
        assertEquals("30s", DurationParser.format(30L * SECOND));
        assertEquals("1h30m", DurationParser.format(HOUR + 30L * MINUTE));
        assertEquals("2d5h", DurationParser.format(2L * DAY + 5L * HOUR + 30L * MINUTE));
        assertEquals("0s", DurationParser.format(0L));
        assertEquals("0s", DurationParser.format(-1L));
    }
}
