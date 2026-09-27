package top.cheesesmp.duelcore.queue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Not the same opponent twice in a row (config.yml {@code matchmaking.avoid-rematch}): two players who just played
 * each other are only paired again once both have searched long enough for their rating window
 * ({@link Matchmaker.Window}, the same growth in every mode) to reach {@code allow-at-window}, and even then only
 * when nobody else fits (a big cost instead of a ban). Remembers each player's last queue opponent; in-memory, main
 * thread only.
 */
public final class LastOpponents implements MatchPolicy {

    /** Added to a rematch once it is allowed: more than any rating gap and latency penalty together. */
    static final double LAST_RESORT = 100_000;

    private final Map<UUID, UUID> last = new HashMap<>();
    private volatile boolean enabled;
    private volatile double allowAtWindow;
    private volatile Matchmaker.Window window;

    public LastOpponents(boolean enabled, double allowAtWindow, Matchmaker.Window window) {
        configure(enabled, allowAtWindow, window);
    }

    public void configure(boolean enabled, double allowAtWindow, Matchmaker.Window window) {
        this.enabled = enabled;
        this.allowAtWindow = allowAtWindow;
        this.window = window;
    }

    /** {@code a} and {@code b} were just paired. */
    public void record(UUID a, UUID b) {
        last.put(a, b);
        last.put(b, a);
    }

    @Override
    public double cost(QueueEntry a, QueueEntry b, long now) {
        if (!enabled || !(b.player().equals(last.get(a.player())) || a.player().equals(last.get(b.player())))) return 0;
        Matchmaker.Window w = window;
        double searched = Math.min(w.at(a.waitSeconds(now)), w.at(b.waitSeconds(now)));
        return searched >= allowAtWindow ? LAST_RESORT : Double.POSITIVE_INFINITY;
    }
}
