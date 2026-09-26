package top.cheesesmp.duelcore.ui;

import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.ui.anim.Ease;

/**
 * The flights of the camera-style respawn animations of {@link RespawnPull} (float, orbit, swoop), kept free of
 * Bukkit so they can be tested.
 *
 * <p>Every flight is a list of feet positions relative to the start, one per tick (element {@code i} is where the
 * player is after {@code i + 1} ticks, like {@link ThrowMath#path}), ending exactly on the target. The player rides
 * a seat the client glides between them, so the positions may change fast; each flight is timed by a curve whose
 * speed is zero at both ends ({@link Ease#smootherstep} or {@link Ease#easeInOutSine}), so it lifts off and touches
 * down without a jolt. Flights that steer the view also carry a yaw and pitch per tick: they blend in from where the
 * player was looking and out into the spawn's facing (eased in and out with {@link Ease#easeInOutSine}, the gentlest
 * peak turn rate), so the view never snaps at either end.
 */
public final class RespawnPaths {

    /** Eye height of a standing player above their feet. */
    public static final double EYE = 1.62;
    /** Ticks of the swoop's pause at the top, looking over the arena before diving. */
    public static final int SWOOP_HOVER_TICKS = 6;
    /** Part of an orbit (by time) over which the view turns from the player's own onto the arena centre. */
    static final double ORBIT_FACE_IN = 0.35;
    /** Last part of an orbit (by time) over which the view turns from the arena centre onto the spawn's facing. */
    static final double ORBIT_FACE_OUT = 0.3;
    /** Smallest distance the orbit keeps from the centre half way through, so the view doesn't whip round. */
    static final double ORBIT_MIN_RADIUS = 7;
    /** The orbit turns at least this far round the centre (the short way round when it is at least this long). */
    static final double ORBIT_MIN_TURN = 150;

    /**
     * A planned flight: feet offsets from the start per tick, and the view per tick ({yaw, pitch}), or null when the
     * player looks around freely.
     */
    public record Flight(double[][] path, float @Nullable [][] facing) {
        public int ticks() {
            return path.length;
        }
    }

    private RespawnPaths() {
    }

    // ------------------------------------------------------------------ float

    /** Flight time of a float over {@code horizontal} blocks rising {@code lift} blocks. */
    public static int floatTicks(double horizontal, double lift) {
        return (int) Math.clamp(Math.round(24 + horizontal * 0.5 + lift * 1.2), 30, 70);
    }

    /**
     * Float: straight up out of the start, across, and straight down onto the target ({@code d} from the start),
     * peaking about {@code lift} blocks above the higher end. A cubic Bézier whose inner points sit above both ends,
     * timed by {@link Ease#smootherstep}. The player looks around freely.
     */
    public static Flight floatUp(double[] d, double lift, int ticks) {
        double h = Math.max(0, d[1]) + lift * 4 / 3; // a Bézier with both inner points at h peaks at 3/4 h
        double[] p0 = {0, 0, 0};
        double[] p1 = {0, h, 0};
        double[] p2 = {d[0], h, d[2]};
        double[] p3 = d.clone();
        double[][] path = new double[Math.max(1, ticks)][];
        for (int k = 0; k < path.length; k++) {
            path[k] = bezier(p0, p1, p2, p3, Ease.smootherstep(Ease.progress(k + 1, path.length)));
        }
        return new Flight(path, null);
    }

    // ------------------------------------------------------------------ orbit

    /**
     * How far (degrees, signed) the orbit turns round the centre from angle {@code from} to {@code to}: the short way
     * when that is at least {@link #ORBIT_MIN_TURN}, otherwise the long way (so it always sweeps across the arena).
     */
    public static double orbitTurn(double from, double to) {
        double turn = RespawnMotion.wrap(to - from);
        if (Math.abs(turn) >= ORBIT_MIN_TURN) return turn;
        return turn >= 0 ? turn - 360 : turn + 360;
    }

    /** Flight time of an orbit turning {@code turn} degrees. */
    public static int orbitTicks(double turn) {
        return (int) Math.clamp(Math.round(40 + Math.abs(turn) / 12), 55, 80);
    }

    /**
     * Orbit: a spiral round the arena centre {@code c} (relative to the start) from the start to the target {@code d},
     * rising {@code lift} blocks in the middle and never closer than {@link #ORBIT_MIN_RADIUS} to the centre half way,
     * timed by {@link Ease#easeInOutSine} (gentler on the view than smootherstep). The view turns from
     * {@code startYaw}/{@code startPitch} onto the centre, follows it round, and ends on {@code endYaw}/{@code endPitch}.
     */
    public static Flight orbit(double[] d, double[] c, double lift, float startYaw, float startPitch, float endYaw,
                               float endPitch) {
        double fromAngle = Math.toDegrees(Math.atan2(-c[2], -c[0]));
        double toAngle = Math.toDegrees(Math.atan2(d[2] - c[2], d[0] - c[0]));
        double rFrom = Math.hypot(c[0], c[2]);
        double rTo = Math.hypot(d[0] - c[0], d[2] - c[2]);
        double bulge = Math.max(0, ORBIT_MIN_RADIUS - (rFrom + rTo) / 2);
        double turn = orbitTurn(fromAngle, toAngle);
        int ticks = orbitTicks(turn);
        double[] focus = {c[0], c[1] + 1, c[2]};
        double[][] path = new double[ticks][];
        float[][] facing = new float[ticks][];
        for (int k = 0; k < ticks; k++) {
            double t = Ease.progress(k + 1, ticks);
            double u = Ease.easeInOutSine(t);
            double a = Math.toRadians(fromAngle + turn * u);
            double r = Ease.lerp(rFrom, rTo, u) + bulge * Math.sin(Math.PI * u);
            double y = d[1] * u + lift * Math.sin(Math.PI * u);
            path[k] = k == ticks - 1 ? d.clone() : new double[] {c[0] + r * Math.cos(a), y, c[2] + r * Math.sin(a)};
            float[] look = lookAt(path[k], focus);
            facing[k] = face(look, t, ORBIT_FACE_IN, ORBIT_FACE_OUT, startYaw, startPitch, endYaw, endPitch);
        }
        return new Flight(path, facing);
    }

    // ------------------------------------------------------------------ swoop

    /**
     * The top of a swoop, relative to the start: {@code back} blocks behind the target {@code d} (away from the arena
     * centre {@code c}, or behind the spawn's facing {@code endYaw} when the target is on the centre) and
     * {@code height} blocks above it.
     */
    public static double[] swoopTop(double[] d, double[] c, float endYaw, double back, double height) {
        double bx = d[0] - c[0];
        double bz = d[2] - c[2];
        double len = Math.hypot(bx, bz);
        if (len < 1) { // on the centre: behind the way the spawn faces
            double yaw = Math.toRadians(endYaw);
            bx = Math.sin(yaw);
            bz = -Math.cos(yaw);
            len = 1;
        }
        return new double[] {d[0] + bx / len * back, d[1] + height, d[2] + bz / len * back};
    }

    /** Ticks of a swoop's rise from the start to its top {@code top}, and of its dive from there onto {@code d}. */
    public static int[] swoopTicks(double[] d, double[] top) {
        int up = (int) Math.clamp(Math.round(18 + length(top) * 0.5), 20, 36);
        int down = (int) Math.clamp(Math.round(16 + distance(top, d) * 0.6), 20, 34);
        return new int[] {up, down};
    }

    /**
     * Swoop (a dolly shot): up and back to {@code top} (see {@link #swoopTop}) while the view turns onto the arena
     * centre {@code c}, a short pause there, then a dive that levels out onto the target {@code d} while the view
     * turns into the spawn's facing. Both legs are timed by {@link Ease#smootherstep}, so the pause is reached and
     * left at zero speed.
     */
    public static Flight swoop(double[] d, double[] c, double[] top, float startYaw, float startPitch, float endYaw,
                               float endPitch) {
        int[] legs = swoopTicks(d, top);
        int up = legs[0];
        int down = legs[1];
        int ticks = up + SWOOP_HOVER_TICKS + down;
        double[] focus = {c[0], c[1] + 1, c[2]};
        // up: rise first, then drift back to the top
        double[] u0 = {0, 0, 0};
        double[] u1 = {0, top[1], 0};
        // down: dive steeply, then level out and glide the last few blocks onto the spawn
        double hx = d[0] - top[0];
        double hz = d[2] - top[2];
        double run = Math.hypot(hx, hz);
        double lead = run < 1e-6 ? 0 : Math.min(3, run * 0.4) / run;
        double[] d1 = {top[0] + hx * 0.35, d[1] + (top[1] - d[1]) * 0.25, top[2] + hz * 0.35};
        double[] d2 = {d[0] - hx * lead, d[1] + 0.2, d[2] - hz * lead};
        double[][] path = new double[ticks][];
        float[][] facing = new float[ticks][];
        for (int k = 0; k < ticks; k++) {
            int tick = k + 1;
            float[] view;
            if (tick <= up) {
                path[k] = bezier(u0, u1, top, top, Ease.smootherstep(Ease.progress(tick, up)));
                view = blend(startYaw, startPitch, lookAt(path[k], focus), Ease.easeInOutSine(Ease.progress(tick, up)));
            } else if (tick <= up + SWOOP_HOVER_TICKS) {
                path[k] = top.clone();
                view = lookAt(top, focus);
            } else {
                double t = Ease.progress(tick - up - SWOOP_HOVER_TICKS, down);
                path[k] = tick == ticks ? d.clone() : bezier(top, d1, d2, d, Ease.smootherstep(t));
                float[] look = lookAt(path[k], focus);
                view = blend(look[0], look[1], new float[] {endYaw, endPitch}, Ease.easeInOutSine(t));
            }
            facing[k] = view;
        }
        return new Flight(path, facing);
    }

    // ------------------------------------------------------------------ helpers

    /** Point at {@code u} (0..1) of the cubic Bézier curve p0 → p3 with inner points p1 and p2. */
    public static double[] bezier(double[] p0, double[] p1, double[] p2, double[] p3, double u) {
        double v = 1 - u;
        double a = v * v * v;
        double b = 3 * v * v * u;
        double c = 3 * v * u * u;
        double e = u * u * u;
        return new double[] {
            a * p0[0] + b * p1[0] + c * p2[0] + e * p3[0],
            a * p0[1] + b * p1[1] + c * p2[1] + e * p3[1],
            a * p0[2] + b * p1[2] + c * p2[2] + e * p3[2]};
    }

    /** Minecraft yaw of a look along {@code dx, dz} (0 = south, 90 = west). */
    public static float yaw(double dx, double dz) {
        return RespawnMotion.wrap(Math.toDegrees(Math.atan2(-dx, dz)));
    }

    /** {yaw, pitch} of a player standing with their feet at {@code feet} looking at {@code at} (pitch down is positive). */
    public static float[] lookAt(double[] feet, double[] at) {
        double dx = at[0] - feet[0];
        double dy = at[1] - (feet[1] + EYE);
        double dz = at[2] - feet[2];
        double flat = Math.hypot(dx, dz);
        float pitch = (float) Math.clamp(-Math.toDegrees(Math.atan2(dy, flat)), -90, 90);
        return new float[] {flat < 1e-6 ? 0 : yaw(dx, dz), pitch};
    }

    /** The view {@code w} (0..1) of the way from {@code yaw}/{@code pitch} to {@code to}, turning the short way round. */
    public static float[] blend(float yaw, float pitch, float[] to, double w) {
        return new float[] {RespawnMotion.wrap(yaw + RespawnMotion.wrap(to[0] - yaw) * w),
            (float) Ease.lerp(pitch, to[1], w)};
    }

    /**
     * The view at progress {@code t} of a flight that looks along {@code look}: turning from the start view over the
     * first {@code in} of the time, and into the end view over the last {@code out}.
     */
    static float[] face(float[] look, double t, double in, double out, float startYaw, float startPitch, float endYaw,
                        float endPitch) {
        float[] view = look;
        if (t < in) view = blend(startYaw, startPitch, look, Ease.easeInOutSine(t / in));
        if (t > 1 - out) view = blend(view[0], view[1], new float[] {endYaw, endPitch}, Ease.easeInOutSine((t - (1 - out)) / out));
        return view;
    }

    /** The longest single-tick move of {@code path} (from the start at the origin), in blocks. */
    public static double maxStep(double[][] path) {
        double max = 0;
        double[] prev = {0, 0, 0};
        for (double[] p : path) {
            max = Math.max(max, distance(prev, p));
            prev = p;
        }
        return max;
    }

    static double length(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
