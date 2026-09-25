package top.cheesesmp.duelcore.rating;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Immutable tier configuration: the rating threshold of every tier, per kit ("default" for all kits, "overall" for the
 * overall Elo).
 * Built from tiers.yml by {@link top.cheesesmp.duelcore.config.ConfigManager}.
 */
public final class TierLadder {

    private final int placementMatches;
    private final EnumMap<Tier, Double> defaultThresholds;
    private final Map<String, EnumMap<Tier, Double>> kitThresholds;

    public TierLadder(int placementMatches,
                      Map<Tier, Double> defaultThresholds,
                      Map<String, Map<Tier, Double>> kitThresholds) {
        this.placementMatches = Math.max(0, placementMatches);
        this.defaultThresholds = complete(defaultThresholds, defaultRatingThresholds());
        this.kitThresholds = new HashMap<>();
        kitThresholds.forEach((kit, map) -> this.kitThresholds.put(kit, complete(map, this.defaultThresholds)));
    }

    public static TierLadder defaults() {
        return new TierLadder(5, defaultRatingThresholds(), Map.of());
    }

    public int placementMatches() {
        return placementMatches;
    }

    /** Best tier whose rating threshold is &lt;= rating. Never null: LT5 is the floor. */
    public Tier kitTier(String kit, double rating) {
        EnumMap<Tier, Double> map = kitThresholds.getOrDefault(kit, defaultThresholds);
        for (Tier tier : Tier.values()) {
            if (rating >= map.get(tier)) return tier;
        }
        return Tier.LT5;
    }

    public double threshold(String kit, Tier tier) {
        return kitThresholds.getOrDefault(kit, defaultThresholds).get(tier);
    }

    /** Next better tier's rating threshold, or NaN when already HT1. */
    public double nextThreshold(String kit, Tier current) {
        if (current == Tier.HT1) return Double.NaN;
        return threshold(kit, Tier.values()[current.ordinal() - 1]);
    }

    public static EnumMap<Tier, Double> defaultRatingThresholds() {
        EnumMap<Tier, Double> m = new EnumMap<>(Tier.class);
        double[] v = {2000, 1900, 1800, 1700, 1620, 1550, 1480, 1410, 1350, 1290, 1230, 1170, 1110, 1050, 0};
        for (Tier t : Tier.values()) m.put(t, v[t.ordinal()]);
        return m;
    }

    private static EnumMap<Tier, Double> complete(Map<Tier, Double> given, Map<Tier, Double> fallback) {
        EnumMap<Tier, Double> m = new EnumMap<>(Tier.class);
        for (Tier t : Tier.values()) m.put(t, given.getOrDefault(t, fallback.get(t)));
        return m;
    }

}
