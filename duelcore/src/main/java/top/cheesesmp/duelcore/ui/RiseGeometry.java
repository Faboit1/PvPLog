package top.cheesesmp.duelcore.ui;

import java.util.function.IntPredicate;

/**
 * Where the rising spawn platform sits (see {@link SpawnRise}). Pure math, no world access.
 *
 * <p>The platform is the 2×2 of block columns around the block corner nearest to the spawn, so the player stands
 * exactly in its middle. {@code topY} is the block the player stands on; the hole is {@code depth} layers deep
 * ({@code topY - depth + 1 .. topY}), and the player starts on {@link #floorY()}, the first block below it.
 */
public record RiseGeometry(int cornerX, int cornerZ, int topY, int depth) {

    /** Faces of the platform that touch the hole walls are pulled in by this much, so they never z-fight. */
    public static final float INSET = 0.002f;

    public static RiseGeometry around(double x, double y, double z, int depth) {
        // a spawn on the surface (y = 64.0) stands on block 63; one on a slab (64.5) on block 64
        return new RiseGeometry((int) Math.round(x), (int) Math.round(z), (int) Math.floor(y - 1.0e-3), depth);
    }

    public RiseGeometry withDepth(int newDepth) {
        return new RiseGeometry(cornerX, cornerZ, topY, newDepth);
    }

    /** The four columns as {x, z}: min/min, max/min, min/max, max/max. */
    public int[][] columns() {
        return new int[][] {
            {cornerX - 1, cornerZ - 1}, {cornerX, cornerZ - 1}, {cornerX - 1, cornerZ}, {cornerX, cornerZ}};
    }

    /** Lowest carved layer. */
    public int bottomY() {
        return topY - depth + 1;
    }

    /** The block under the hole the player starts on. */
    public int floorY() {
        return topY - depth;
    }

    /** Feet height at the bottom of the hole for a spawn at {@code spawnY} (never inside the floor block). */
    public double startY(double spawnY) {
        return Math.max(spawnY - depth, floorY() + 1);
    }

    /**
     * Translation (x, y, z) of a platform block in column {@code (x, z)} {@code lift} blocks below its real place,
     * with the outer faces inset. The matching scale is {@link #scale()}.
     */
    public float[] translation(int x, int z, double lift) {
        float tx = x == cornerX - 1 ? INSET : 0f;
        float tz = z == cornerZ - 1 ? INSET : 0f;
        return new float[] {tx, (float) (-lift - INSET), tz};
    }

    /** Scale of every platform block: a hair smaller sideways (the outer faces), full height. */
    public float[] scale() {
        return new float[] {1f - INSET, 1f, 1f - INSET};
    }

    /** True if the column {@code (x, z)} belongs to this platform. */
    public boolean contains(int x, int z) {
        return (x == cornerX - 1 || x == cornerX) && (z == cornerZ - 1 || z == cornerZ);
    }

    /**
     * The deepest usable hole, at most {@code max}: every layer from the top down must be carvable ({@code layerOk}
     * gets the layer's Y) and the block under the lowest one solid ({@code floorOk}). 0 = no hole possible.
     */
    public static int usableDepth(int topY, int max, IntPredicate layerOk, IntPredicate floorOk) {
        int best = 0;
        for (int d = 1; d <= max; d++) {
            if (!layerOk.test(topY - d + 1)) break;
            if (floorOk.test(topY - d)) best = d;
        }
        return best;
    }

    /** Feet height after {@code tick} of {@code ticks} (linear, like the display interpolation). */
    public static double heightAt(double startY, double endY, int tick, int ticks) {
        if (ticks <= 0 || tick >= ticks) return endY;
        if (tick <= 0) return startY;
        return startY + (endY - startY) * tick / ticks;
    }
}
