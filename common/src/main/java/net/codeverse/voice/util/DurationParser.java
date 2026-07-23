package net.codeverse.voice.util;

import java.util.Locale;
import java.util.Optional;

/**
 * Parses staff facing duration strings such as 30m, 2h, 7d into milliseconds.
 *
 * Returns an empty optional rather than throwing on malformed input, so a
 * mistyped command produces a usage message instead of a stack trace in the
 * console. Compound forms like 1d12h are supported because staff type them
 * naturally, and the parser rejects a bare number so that an ambiguous
 * argument is never silently interpreted as the wrong unit.
 */
public final class DurationParser {

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60L * SECOND;
    private static final long HOUR = 60L * MINUTE;
    private static final long DAY = 24L * HOUR;
    private static final long WEEK = 7L * DAY;

    /** Upper bound of roughly ten years, treated as permanent beyond this. */
    public static final long MAXIMUM_MILLIS = 3650L * DAY;

    private DurationParser() {
    }

    /**
     * Parses a duration. Recognised suffixes are s, m, h, d and w. The literal
     * strings perm, permanent and forever produce an empty optional paired with
     * {@link #isPermanent(String)}, which callers check first.
     */
    public static Optional<Long> parse(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String normalised = input.trim().toLowerCase(Locale.ROOT);
        long total = 0L;
        long current = 0L;
        boolean sawDigit = false;
        boolean sawUnit = false;

        for (int i = 0; i < normalised.length(); i++) {
            char character = normalised.charAt(i);
            if (character >= '0' && character <= '9') {
                sawDigit = true;
                current = current * 10L + (character - '0');
                if (current > MAXIMUM_MILLIS) {
                    return Optional.of(MAXIMUM_MILLIS);
                }
                continue;
            }
            if (!sawDigit) {
                return Optional.empty();
            }
            long unit = switch (character) {
                case 's' -> SECOND;
                case 'm' -> MINUTE;
                case 'h' -> HOUR;
                case 'd' -> DAY;
                case 'w' -> WEEK;
                default -> -1L;
            };
            if (unit < 0) {
                return Optional.empty();
            }
            total += current * unit;
            current = 0L;
            sawDigit = false;
            sawUnit = true;
        }

        // A trailing number with no unit is ambiguous and rejected outright.
        if (sawDigit || !sawUnit) {
            return Optional.empty();
        }
        if (total <= 0L) {
            return Optional.empty();
        }
        return Optional.of(Math.min(total, MAXIMUM_MILLIS));
    }

    public static boolean isPermanent(String input) {
        if (input == null) {
            return false;
        }
        String normalised = input.trim().toLowerCase(Locale.ROOT);
        return normalised.equals("perm") || normalised.equals("permanent") || normalised.equals("forever");
    }

    /** Renders a remaining duration for display, largest two units only. */
    public static String format(long millis) {
        if (millis <= 0L) {
            return "0s";
        }
        long remaining = millis;
        StringBuilder builder = new StringBuilder();
        int parts = 0;

        long weeks = remaining / WEEK;
        if (weeks > 0) {
            builder.append(weeks).append('w');
            remaining -= weeks * WEEK;
            parts++;
        }
        long days = remaining / DAY;
        if (days > 0 && parts < 2) {
            builder.append(days).append('d');
            remaining -= days * DAY;
            parts++;
        }
        long hours = remaining / HOUR;
        if (hours > 0 && parts < 2) {
            builder.append(hours).append('h');
            remaining -= hours * HOUR;
            parts++;
        }
        long minutes = remaining / MINUTE;
        if (minutes > 0 && parts < 2) {
            builder.append(minutes).append('m');
            remaining -= minutes * MINUTE;
            parts++;
        }
        long seconds = remaining / SECOND;
        if (seconds > 0 && parts < 2) {
            builder.append(seconds).append('s');
            parts++;
        }
        return builder.isEmpty() ? "0s" : builder.toString();
    }
}
