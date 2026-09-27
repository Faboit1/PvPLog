package top.cheesesmp.duelcore.queue;

/**
 * Default latency-aware policy. Before {@code relaxAfterSeconds} (of the longer waiter) it prefers:
 * <ul>
 *   <li>same region: a cross-region pair costs {@code regionPenalty} rating points,</li>
 *   <li>same country: a cross-country pair costs {@code countryPenalty}, only when both players have a country and
 *       both want this ({@link QueueEntry#preferCountry()}),</li>
 *   <li>similar ping: each ms of ping difference costs {@code pingPenaltyPerMs},</li>
 *   <li>each player's max-ping preference: an opponent above it costs {@code maxPingPenalty}.</li>
 * </ul>
 * After the relax time all latency costs fade to zero so nobody waits forever. A pair's combined ping costs nothing on
 * purpose: two high-ping players are an even match, and a cost for it would pull them towards low-ping opponents,
 * against the similar-ping preference.
 */
public final class RegionPingPolicy implements MatchPolicy {

    private final boolean regionEnabled;
    private final double regionPenalty;
    private final boolean countryEnabled;
    private final double countryPenalty;
    private final boolean pingEnabled;
    private final double pingPenaltyPerMs;
    private final double maxPingPenalty;
    private final double relaxAfterSeconds;

    /** Without the same-country preference. */
    public RegionPingPolicy(boolean regionEnabled, double regionPenalty, boolean pingEnabled, double pingPenaltyPerMs,
                            double maxPingPenalty, double relaxAfterSeconds) {
        this(regionEnabled, regionPenalty, false, 0, pingEnabled, pingPenaltyPerMs, maxPingPenalty, relaxAfterSeconds);
    }

    public RegionPingPolicy(boolean regionEnabled, double regionPenalty, boolean countryEnabled, double countryPenalty,
                            boolean pingEnabled, double pingPenaltyPerMs, double maxPingPenalty, double relaxAfterSeconds) {
        this.regionEnabled = regionEnabled;
        this.regionPenalty = regionPenalty;
        this.countryEnabled = countryEnabled;
        this.countryPenalty = countryPenalty;
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
        if (countryEnabled && crossCountry(a, b)) cost += countryPenalty;
        if (pingEnabled) {
            cost += Math.abs(a.ping() - b.ping()) * pingPenaltyPerMs;
            if (a.maxPing() > 0 && b.ping() > a.maxPing()) cost += maxPingPenalty;
            if (b.maxPing() > 0 && a.ping() > b.maxPing()) cost += maxPingPenalty;
        }
        return cost * fade;
    }

    /** Both players want an opponent from their own country, both have one, and the two differ. */
    static boolean crossCountry(QueueEntry a, QueueEntry b) {
        return a.preferCountry() && b.preferCountry() && a.country() != null && b.country() != null
            && !a.country().equalsIgnoreCase(b.country());
    }
}
