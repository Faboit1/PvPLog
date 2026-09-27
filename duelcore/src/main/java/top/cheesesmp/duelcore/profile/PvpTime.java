package top.cheesesmp.duelcore.profile;

import java.util.Locale;

/**
 * Formatting of a player's total PvP time ({@link PlayerProfile#pvpTimeMs()}) for placeholders and the profile dialog.
 */
public final class PvpTime {

    private PvpTime() {
    }

    /** "12h 34m" from an hour on, "34m 5s" under an hour, "0m" for none (under a second). */
    public static String format(long ms) {
        long s = seconds(ms);
        if (s <= 0) return "0m";
        long h = s / 3600;
        long m = (s % 3600) / 60;
        if (h > 0) return h + "h " + m + "m";
        return m + "m " + (s % 60) + "s";
    }

    /** Whole seconds, never negative. */
    public static long seconds(long ms) {
        return Math.max(0, ms) / 1000;
    }

    /** Hours with one decimal ("12.6"), always with a dot. */
    public static String hours(long ms) {
        return String.format(Locale.ROOT, "%.1f", Math.max(0, ms) / 3_600_000.0);
    }
}
