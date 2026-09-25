package top.cheesesmp.duelcore.queue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pairs queue entries of one (kit, mode) bucket. Pure logic, no Bukkit calls: the caller filters out
 * offline/busy players and turns pairs into matches.
 *
 * <p>Each entry accepts opponents within its own rating window
 * {@code initial + growthPerSecond × wait} (capped at {@code max}); a pair is valid only when the rating gap fits
 * <em>both</em> windows. Oldest waiters pick first, choosing the valid partner with the lowest
 * {@code |Δrating| + policy cost}.
 */
public final class Matchmaker {

    public record Window(double initial, double growthPerSecond, double max) {
        public double at(double waitSeconds) {
            return Math.min(max, initial + growthPerSecond * waitSeconds);
        }
    }

    public record Pair(QueueEntry a, QueueEntry b, double ratingGap, double windowA, double windowB) {
    }

    private final Window window;
    private MatchPolicy policy;

    public Matchmaker(Window window, MatchPolicy policy) {
        this.window = window;
        this.policy = policy;
    }

    public void policy(MatchPolicy policy) {
        this.policy = policy;
    }

    public MatchPolicy policy() {
        return policy;
    }

    public Window window() {
        return window;
    }

    /** Pairs as many entries as possible. Entries of different sizes (solo vs party) are never paired. */
    public List<Pair> pair(List<QueueEntry> bucket, long now) {
        List<QueueEntry> sorted = new ArrayList<>(bucket);
        sorted.sort(Comparator.comparingLong(QueueEntry::joinedAt));
        boolean[] used = new boolean[sorted.size()];
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            if (used[i]) continue;
            QueueEntry a = sorted.get(i);
            double wa = windowFor(a, now);
            int best = -1;
            double bestCost = Double.POSITIVE_INFINITY;
            double bestWb = 0;
            for (int j = 0; j < sorted.size(); j++) {
                if (j == i || used[j]) continue;
                QueueEntry b = sorted.get(j);
                if (b.player().equals(a.player()) || b.size() != a.size() || overlaps(a, b)) continue;
                double gap = Math.abs(a.rating() - b.rating());
                double wb = windowFor(b, now);
                if (gap > wa || gap > wb) continue;
                double cost = gap + policy.cost(a, b, now);
                if (cost < bestCost) {
                    bestCost = cost;
                    best = j;
                    bestWb = wb;
                }
            }
            if (best >= 0) {
                used[i] = true;
                used[best] = true;
                QueueEntry b = sorted.get(best);
                pairs.add(new Pair(a, b, Math.abs(a.rating() - b.rating()), wa, bestWb));
            }
        }
        return pairs;
    }

    public double windowFor(QueueEntry entry, long now) {
        if (entry.mode() != QueueMode.RANKED) return Double.POSITIVE_INFINITY;
        return window.at(entry.waitSeconds(now));
    }

    private static boolean overlaps(QueueEntry a, QueueEntry b) {
        if (a.partyMembers().contains(b.player()) || b.partyMembers().contains(a.player())) return true;
        for (var m : a.partyMembers()) if (b.partyMembers().contains(m)) return true;
        return false;
    }
}
