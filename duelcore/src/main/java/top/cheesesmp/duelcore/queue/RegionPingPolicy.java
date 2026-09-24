package top.cheesesmp.duelcore.queue;

/**
 * Default latency-aware policy. Before {@code relaxAfterSeconds} (of the longer waiter) it prefers:
 * <ul>
 *   <li>same region: a cross-region pair costs {@code regionPenalty} rating points,</li>
 *   <li>similar ping: each ms of ping difference costs {@code pingPenaltyPerMs},</li>
 *   <li>each player's max-ping preference: an opponent above it costs {@code maxPingPenalty}.</li>
 * </ul>
 * After the relax time all latency costs fade to zero so nobody waits forever.
 */
public final class RegionPingPolicy implements MatchPolicy {

    private final boolean regionEnabled;
    private final double regionPenalty;
    private final boolean pingEnabled;
    private final double pingPenaltyPerMs;
    private final double maxPingPenalty;
    private final double relaxAfterSeconds;

    public RegionPingPolicy(boolean regionEnabled, double regionPenalty, boolean pingEnabled, double pingPenaltyPerMs,
                            double maxPingPenalty, double relaxAfterSeconds) {
        this.regionEnabled = regionEnabled;
        this.regionPenalty = regionPenalty;
        this.pingEnabled = pingEnabled;
        this.pingPenaltyPerMs = pingPenaltyPerMs;
        this.maxPingPenalty = maxPingPenalty;
        this.relaxAfterSeconds = relaxAfterSeconds;
    }

    @Override
    public double cost(QueueEntry a, QueueEntry b, long now) {
        double wait = Math.max(a.waitSeconds(now), b.waitSeconds(now));
        double fade = relaxAfterSeconds <= 0 ? 0 : Math.max(0, 1 - wait / relaxAfterSeconds);
        if (fade == 0) return 0;
        double cost = 0;
        if (regionEnabled && a.region() != null && b.region() != null && !a.region().equalsIgnoreCase(b.region())) {
            cost += regionPenalty;
        }
        if (pingEnabled) {
            cost += Math.abs(a.ping() - b.ping()) * pingPenaltyPerMs;
            if (a.maxPing() > 0 && b.ping() > a.maxPing()) cost += maxPingPenalty;
            if (b.maxPing() > 0 && a.ping() > b.maxPing()) cost += maxPingPenalty;
        }
        return cost * fade;
    }
}
