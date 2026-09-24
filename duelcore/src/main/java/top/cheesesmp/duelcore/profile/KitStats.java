package top.cheesesmp.duelcore.profile;

import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.rating.Tier;

/**
 * Mutable per-kit, per-season stats for one player. Only touched on the main thread;
 * persistence works on {@link #snapshot()} copies.
 */
public final class KitStats {

    public double rating;
    public double rd;
    public double volatility;
    public int games;
    public int wins;
    public int losses;
    public int streak;
    public int bestStreak;
    public double peak;
    public @Nullable Tier tierOverride;
    public long updatedAt;

    public KitStats(double rating, double rd, double volatility) {
        this.rating = rating;
        this.rd = rd;
        this.volatility = volatility;
        this.peak = rating;
    }

    public boolean placed(int placementMatches) {
        return tierOverride != null || games >= placementMatches;
    }

    public double winRate() {
        return games == 0 ? 0 : (double) wins / games;
    }

    public void recordResult(boolean won) {
        games++;
        if (won) {
            wins++;
            streak = Math.max(1, streak + 1);
            bestStreak = Math.max(bestStreak, streak);
        } else {
            losses++;
            streak = Math.min(-1, streak - 1);
        }
    }

    public KitStats snapshot() {
        KitStats copy = new KitStats(rating, rd, volatility);
        copy.games = games;
        copy.wins = wins;
        copy.losses = losses;
        copy.streak = streak;
        copy.bestStreak = bestStreak;
        copy.peak = peak;
        copy.tierOverride = tierOverride;
        copy.updatedAt = updatedAt;
        return copy;
    }
}
