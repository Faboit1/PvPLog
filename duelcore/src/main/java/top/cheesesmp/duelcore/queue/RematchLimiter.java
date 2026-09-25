package top.cheesesmp.duelcore.queue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Anti-boosting: counts ranked games between the same two players in a rolling 24 h window and forbids further
 * ranked pairings once the limit is reached. In-memory (resets on restart), main thread only.
 */
public final class RematchLimiter implements MatchPolicy {

    private static final long WINDOW = 24L * 60 * 60 * 1000;

    private final Map<String, Deque<Long>> games = new HashMap<>();
    private volatile int limit;

    public RematchLimiter(int limit) {
        this.limit = limit;
    }

    public void limit(int limit) {
        this.limit = limit;
    }

    public void record(UUID a, UUID b, long now) {
        games.computeIfAbsent(key(a, b), k -> new ArrayDeque<>()).addLast(now);
    }

    public int count(UUID a, UUID b, long now) {
        Deque<Long> d = games.get(key(a, b));
        if (d == null) return 0;
        while (!d.isEmpty() && d.peekFirst() < now - WINDOW) d.pollFirst();
        return d.size();
    }

    @Override
    public double cost(QueueEntry a, QueueEntry b, long now) {
        if (limit <= 0 || a.mode() != QueueMode.RANKED) return 0;
        return count(a.player(), b.player(), now) >= limit ? Double.POSITIVE_INFINITY : 0;
    }

    /** Drops expired entries (called periodically so the map can't grow forever). */
    public void prune(long now) {
        games.values().removeIf(d -> {
            while (!d.isEmpty() && d.peekFirst() < now - WINDOW) d.pollFirst();
            return d.isEmpty();
        });
    }

    public int size() {
        return games.size();
    }

    private static String key(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
    }
}
