package top.cheesesmp.duelcore.queue;

/**
 * Hook for matchmaking preferences beyond rating (region, latency, anti-boosting…).
 * Return extra "cost" in rating points for pairing a and b right now; {@link Double#POSITIVE_INFINITY} forbids it.
 * Lower total cost (|Δrating| + policy cost) wins among acceptable partners. Other plugins may replace the policy
 * through {@link top.cheesesmp.duelcore.api.DuelCoreApi#setMatchPolicy(MatchPolicy)}.
 */
@FunctionalInterface
public interface MatchPolicy {

    double cost(QueueEntry a, QueueEntry b, long now);

    MatchPolicy NONE = (a, b, now) -> 0;

    default MatchPolicy and(MatchPolicy other) {
        return (a, b, now) -> {
            double c = cost(a, b, now);
            return Double.isInfinite(c) ? c : c + other.cost(a, b, now);
        };
    }
}
