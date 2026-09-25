package top.cheesesmp.duelcore.ui.anim;

import java.util.function.DoubleUnaryOperator;

/**
 * Easing curves (t in 0..1 → 0..1, clamped) and tweening of numbers. Pure, no Bukkit. The curves are the usual
 * easings.net ones; {@link #easeOutBack} overshoots slightly above 1 before settling.
 */
public final class Ease {

    public static final DoubleUnaryOperator LINEAR = Ease::linear;
    public static final DoubleUnaryOperator OUT_QUAD = Ease::easeOutQuad;
    public static final DoubleUnaryOperator OUT_CUBIC = Ease::easeOutCubic;
    public static final DoubleUnaryOperator IN_CUBIC = Ease::easeInCubic;
    public static final DoubleUnaryOperator IN_OUT_CUBIC = Ease::easeInOutCubic;
    public static final DoubleUnaryOperator OUT_BACK = Ease::easeOutBack;
    public static final DoubleUnaryOperator IN_OUT_SINE = Ease::easeInOutSine;
    public static final DoubleUnaryOperator OUT_EXPO = Ease::easeOutExpo;

    private Ease() {
    }

    /** Progress of frame {@code tick} through an animation of {@code duration} ticks, 0..1 (1 when duration ≤ 0). */
    public static double progress(int tick, int duration) {
        return duration <= 0 ? 1 : clamp01((double) tick / duration);
    }

    public static double clamp01(double t) {
        return t < 0 ? 0 : t > 1 ? 1 : t;
    }

    public static double linear(double t) {
        return clamp01(t);
    }

    public static double easeOutQuad(double t) {
        t = clamp01(t);
        return 1 - (1 - t) * (1 - t);
    }

    public static double easeOutCubic(double t) {
        t = clamp01(t);
        double u = 1 - t;
        return 1 - u * u * u;
    }

    public static double easeInCubic(double t) {
        t = clamp01(t);
        return t * t * t;
    }

    public static double easeInOutCubic(double t) {
        t = clamp01(t);
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    /** Overshoots to about 1.1 around t = 0.6, then settles on 1. */
    public static double easeOutBack(double t) {
        t = clamp01(t);
        double c1 = 1.70158;
        double c3 = c1 + 1;
        double u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }

    public static double easeInOutSine(double t) {
        t = clamp01(t);
        return (1 - Math.cos(Math.PI * t)) / 2;
    }

    public static double easeOutExpo(double t) {
        t = clamp01(t);
        return t >= 1 ? 1 : 1 - Math.pow(2, -10 * t);
    }

    /** a → b at eased progress {@code t} (t is not clamped here, so an overshooting curve overshoots). */
    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /**
     * A number counting from {@code from} to {@code to} (up or down) at frame {@code tick} of {@code duration},
     * rounded, eased by {@code ease}. Never overshoots past {@code to}, even with {@link #OUT_BACK}.
     */
    public static long count(double from, double to, int tick, int duration, DoubleUnaryOperator ease) {
        double v = lerp(from, to, ease.applyAsDouble(progress(tick, duration)));
        v = from <= to ? Math.min(v, to) : Math.max(v, to);
        return Math.round(v);
    }
}
