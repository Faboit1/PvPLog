package top.cheesesmp.duelcore.rating;

import top.cheesesmp.duelcore.profile.KitStats;

/**
 * Classic Elo with a higher K-factor while a player is still in placement ("provisional") games.
 * Each side uses its own K, so a veteran is not swung hard by a newcomer's large K.
 */
public final class EloRating implements RatingSystem {

    private final double k;
    private final double provisionalK;
    private final int provisionalGames;
    private final double floor;

    public EloRating(double k, double provisionalK, int provisionalGames, double floor) {
        this.k = k;
        this.provisionalK = provisionalK;
        this.provisionalGames = provisionalGames;
        this.floor = floor;
    }

    public static double expected(double ra, double rb) {
        return 1.0 / (1.0 + Math.pow(10.0, (rb - ra) / 400.0));
    }

    @Override
    public Result rate(KitStats a, KitStats b, double scoreA) {
        double ea = expected(a.rating, b.rating);
        double ka = a.games < provisionalGames ? provisionalK : k;
        double kb = b.games < provisionalGames ? provisionalK : k;
        double na = Math.max(floor, a.rating + ka * (scoreA - ea));
        double nb = Math.max(floor, b.rating + kb * ((1 - scoreA) - (1 - ea)));
        return new Result(new Side(na, a.rd, a.volatility), new Side(nb, b.rd, b.volatility));
    }
}
