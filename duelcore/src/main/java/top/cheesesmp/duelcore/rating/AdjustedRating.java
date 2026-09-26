package top.cheesesmp.duelcore.rating;

import top.cheesesmp.duelcore.profile.KitStats;

/**
 * Wraps a {@link RatingSystem} and reshapes its rating change: each player's change becomes
 * {@code multiplier × change + bonus}, and no rating ends below {@code floor}. The wrapped system should use no floor
 * of its own, so its change is the true one before it is scaled. RD and volatility are passed through unchanged.
 */
public final class AdjustedRating implements RatingSystem {

    private final RatingSystem inner;
    private final double multiplier;
    private final double bonus;
    private final double floor;

    public AdjustedRating(RatingSystem inner, double multiplier, double bonus, double floor) {
        this.inner = inner;
        this.multiplier = multiplier;
        this.bonus = bonus;
        this.floor = floor;
    }

    /** The new rating for a change of {@code delta} from {@code before}: scaled, plus the bonus, never under the floor. */
    public static double adjust(double before, double delta, double multiplier, double bonus, double floor) {
        return Math.max(floor, before + multiplier * delta + bonus);
    }

    @Override
    public Result rate(KitStats a, KitStats b, double scoreA) {
        Result r = inner.rate(a, b, scoreA);
        return new Result(side(r.a(), a), side(r.b(), b));
    }

    private Side side(Side s, KitStats before) {
        return new Side(adjust(before.rating, s.delta(before), multiplier, bonus, floor), s.rd(), s.volatility());
    }
}
