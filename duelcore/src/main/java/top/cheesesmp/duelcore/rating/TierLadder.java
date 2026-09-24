package top.cheesesmp.duelcore.rating;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Immutable tier configuration: rating thresholds per kit, tier points and overall thresholds.
 * Built from tiers.yml by {@link top.cheesesmp.duelcore.config.ConfigManager}.
 */
public final class TierLadder {

    private final int placementMatches;
    private final EnumMap<Tier, Double> defaultThresholds;
    private final Map<String, EnumMap<Tier, Double>> kitThresholds;
    private final EnumMap<Tier, Integer> points;
    private final EnumMap<Tier, Integer> overallThresholds;

    public TierLadder(int placementMatches,
                      Map<Tier, Double> defaultThresholds,
                      Map<String, Map<Tier, Double>> kitThresholds,
                      Map<Tier, Integer> points,
                      Map<Tier, Integer> overallThresholds) {
        this.placementMatches = Math.max(0, placementMatches);
        this.defaultThresholds = complete(defaultThresholds, defaultRatingThresholds());
        this.kitThresholds = new HashMap<>();
        kitThresholds.forEach((kit, map) -> this.kitThresholds.put(kit, complete(map, this.defaultThresholds)));
        this.points = completeInt(points, defaultPoints());
        this.overallThresholds = completeInt(overallThresholds, defaultOverallThresholds());
    }

    public static TierLadder defaults() {
        return new TierLadder(5, defaultRatingThresholds(), Map.of(), defaultPoints(), defaultOverallThresholds());
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

    public int points(@Nullable Tier tier) {
        return tier == null ? 0 : points.get(tier);
    }

    /** Overall tier from global points. */
    public Tier overallTier(int globalPoints) {
        for (Tier tier : Tier.values()) {
            if (globalPoints >= overallThresholds.get(tier)) return tier;
        }
        return Tier.LT5;
    }

    public int overallThreshold(Tier tier) {
        return overallThresholds.get(tier);
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

    public static EnumMap<Tier, Integer> defaultPoints() {
        EnumMap<Tier, Integer> m = new EnumMap<>(Tier.class);
        int[] v = {60, 50, 45, 40, 32, 28, 22, 16, 12, 8, 5, 3, 2, 1, 0};
        for (Tier t : Tier.values()) m.put(t, v[t.ordinal()]);
        return m;
    }

    public static EnumMap<Tier, Integer> defaultOverallThresholds() {
        EnumMap<Tier, Integer> m = new EnumMap<>(Tier.class);
        int[] v = {400, 350, 300, 250, 200, 150, 125, 100, 75, 50, 30, 15, 5, 1, 0};
        for (Tier t : Tier.values()) m.put(t, v[t.ordinal()]);
        return m;
    }

    private static EnumMap<Tier, Double> complete(Map<Tier, Double> given, Map<Tier, Double> fallback) {
        EnumMap<Tier, Double> m = new EnumMap<>(Tier.class);
        for (Tier t : Tier.values()) m.put(t, given.getOrDefault(t, fallback.get(t)));
        return m;
    }

    private static EnumMap<Tier, Integer> completeInt(Map<Tier, Integer> given, Map<Tier, Integer> fallback) {
        EnumMap<Tier, Integer> m = new EnumMap<>(Tier.class);
        for (Tier t : Tier.values()) m.put(t, given.getOrDefault(t, fallback.get(t)));
        return m;
    }
}
