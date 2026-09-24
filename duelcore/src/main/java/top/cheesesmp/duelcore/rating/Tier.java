package top.cheesesmp.duelcore.rating;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The 15-step tier ladder, best first: HT1 &gt; MT1 &gt; LT1 &gt; HT2 &gt; … &gt; LT5.
 * "H/M/L" = high/mid/low within a tier level 1 (best) to 5.
 */
public enum Tier {
    HT1, MT1, LT1,
    HT2, MT2, LT2,
    HT3, MT3, LT3,
    HT4, MT4, LT4,
    HT5, MT5, LT5;

    private static final Tier[] VALUES = values();

    /** Tier level, 1 (best) to 5. */
    public int level() {
        return ordinal() / 3 + 1;
    }

    /** "HT", "MT" or "LT". */
    public String band() {
        return name().substring(0, 2);
    }

    /** Stable small id used in the database (0 = HT1 … 14 = LT5). */
    public int id() {
        return ordinal();
    }

    public boolean isBetterThan(Tier other) {
        return ordinal() < other.ordinal();
    }

    public static @Nullable Tier byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : null;
    }

    public static @Nullable Tier parse(@Nullable String raw) {
        if (raw == null) return null;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
