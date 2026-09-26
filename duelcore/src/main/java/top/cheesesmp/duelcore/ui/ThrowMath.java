package top.cheesesmp.duelcore.ui;

/**
 * The arc of the respawn throw ({@link RespawnPull}), kept free of Bukkit so it can be tested.
 *
 * <p>The player is carried along the path a thrown player would fly under Minecraft's own physics, so the flight
 * looks and feels like a real throw although the server moves them every tick.
 */
public final class ThrowMath {

    /** Minecraft's gravity for players, blocks per tick². */
    public static final double GRAVITY = 0.08;

    private ThrowMath() {
    }

    /**
     * Flight time for an arc that bulges {@code bulge} blocks above its chord: the path chord(u) + 4A·u(1-u)
     * accelerates downwards by 8A/T² per tick², so T is picked to make that Minecraft's gravity.
     */
    public static int ticks(double bulge) {
        return (int) Math.clamp(Math.round(Math.sqrt(8 * Math.max(0, bulge) / GRAVITY)), 14, 80);
    }

    /** Vertical drag applied after gravity each tick in the air. */
    public static final double DRAG_Y = 0.98;
    /** Horizontal drag each tick in the air. */
    public static final double DRAG_XZ = 0.91;
    /** Horizontal factor for a tick that starts on normal ground (block friction 0.6 times air drag). */
    public static final double GROUND_XZ = 0.6 * 0.91;

    /**
     * The single launch velocity that carries a player {@code dx, dy, dz} blocks in exactly {@code ticks} ticks under
     * Minecraft's own player physics (each tick: move by the velocity, then {@code vy = (vy - 0.08) * 0.98},
     * horizontal times 0.91, or times 0.546 after a tick that started on the ground), with no input from the player.
     * Returns {x, y, z} in blocks per tick.
     */
    public static double[] launch(double dx, double dy, double dz, int ticks, boolean startsOnGround) {
        int n = Math.max(1, ticks);
        double sumH = 0; // sum of the horizontal speed factors over the flight
        double h = 1;
        double a = 1; // vy_i = a_i * v0 - b_i
        double b = 0;
        double sumA = 0;
        double sumB = 0;
        for (int i = 0; i < n; i++) {
            sumH += h;
            sumA += a;
            sumB += b;
            h *= i == 0 && startsOnGround ? GROUND_XZ : DRAG_XZ;
            a *= DRAG_Y;
            b = (b + GRAVITY) * DRAG_Y;
        }
        return new double[] {dx / sumH, (dy + sumB) / sumA, dz / sumH};
    }

    /**
     * Where a player launched with {@code v} is after each tick of the flight, relative to the start (the same
     * physics as {@link #launch}); element {@code i} is the offset after {@code i + 1} ticks.
     */
    public static double[][] path(double[] v, int ticks, boolean startsOnGround) {
        double[][] out = new double[Math.max(1, ticks)][];
        double x = 0, y = 0, z = 0;
        double vx = v[0], vy = v[1], vz = v[2];
        for (int i = 0; i < out.length; i++) {
            x += vx;
            y += vy;
            z += vz;
            out[i] = new double[] {x, y, z};
            double f = i == 0 && startsOnGround ? GROUND_XZ : DRAG_XZ;
            vx *= f;
            vz *= f;
            vy = (vy - GRAVITY) * DRAG_Y;
        }
        return out;
    }

    /**
     * The same flight with its launch and landing softened: the speed along the path ramps up from zero over the
     * first {@code easeIn} ticks and back down to zero over the last {@code easeOut} ticks (a smoothstep ramp, so
     * there is no jolt at either end), and runs at the real speed in between. The shape of the path is unchanged
     * (positions between two ticks of {@code path} are interpolated); only the timing is. The result is
     * {@code (easeIn + easeOut) / 2} ticks longer (use even ramps) and ends exactly on the last point of {@code path}.
     * Ramps longer than the flight allows are shortened.
     */
    public static double[][] soften(double[][] path, int easeIn, int easeOut) {
        int n = path.length;
        int a = Math.max(0, Math.min(easeIn, n / 2)) & ~1;
        int b = Math.max(0, Math.min(easeOut, n / 2)) & ~1;
        int total = n + (a + b) / 2;
        double[][] out = new double[total][];
        for (int k = 0; k < total; k++) out[k] = at(path, warp(k + 1, total, a, b));
        return out;
    }

    /**
     * Path time (0..{@code total - (a + b) / 2}) at real time {@code t} of a flight that speeds up over its first
     * {@code a} ticks and slows down over its last {@code b}: the integral of a speed that is 0 at both ends, 1 in the
     * middle, with smoothstep ramps (whose integral from 0 to x is x³ − x⁴/2).
     */
    static double warp(double t, int total, int a, int b) {
        t = Math.clamp(t, 0, total);
        if (a > 0 && t < a) return a * rampArea(t / a);
        double p = a / 2.0;
        double downAt = total - b;
        if (b <= 0 || t <= downAt) return p + (t - a);
        double x = (t - downAt) / b;
        return p + (downAt - a) + b * (x - rampArea(x));
    }

    private static double rampArea(double x) {
        return x * x * x - x * x * x * x / 2;
    }

    /** The point at fractional tick {@code time} of {@code path} (tick 0 is the start, the origin). */
    private static double[] at(double[][] path, double time) {
        int i = (int) Math.floor(time);
        if (i >= path.length) return path[path.length - 1].clone();
        double f = time - i;
        double[] from = i == 0 ? new double[3] : path[i - 1];
        double[] to = path[i];
        return new double[] {from[0] + (to[0] - from[0]) * f, from[1] + (to[1] - from[1]) * f, from[2] + (to[2] - from[2]) * f};
    }

    /** Height at progress {@code u} (0..1) of the arc from {@code y0} to {@code y1} bulging {@code bulge} above the chord. */
    public static double y(double y0, double y1, double bulge, double u) {
        return y0 + (y1 - y0) * u + 4 * bulge * u * (1 - u);
    }
}
