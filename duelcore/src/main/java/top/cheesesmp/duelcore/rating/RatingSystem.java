package top.cheesesmp.duelcore.rating;

import top.cheesesmp.duelcore.profile.KitStats;

/** Pluggable rating algorithm. Implementations are pure functions of their inputs. */
public interface RatingSystem {

    /**
     * @param a      player A's stats before the match (not modified)
     * @param b      player B's stats before the match (not modified)
     * @param scoreA 1 = A won, 0 = A lost, 0.5 = draw
     */
    Result rate(KitStats a, KitStats b, double scoreA);

    record Side(double rating, double rd, double volatility) {
        public double delta(KitStats before) {
            return rating - before.rating;
        }
    }

    record Result(Side a, Side b) {
    }
}
