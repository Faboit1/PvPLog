package top.cheesesmp.duelcore.ui;

/**
 * Timing of the respawn throw ({@link RespawnPull}), kept free of Bukkit so it can be tested.
 *
 * <p>The client runs {@code ping} milliseconds (round trip) behind the server: it gets each velocity half a ping
 * late and its position arrives back half a ping later. Everything that waits for the client to move is stretched by
 * that many ticks.
 */
public final class ThrowMath {

    /** Minecraft's gravity for players, blocks per tick². */
    public static final double GRAVITY = 0.08;
    /** Ticks after the last velocity a normal-ping player gets to touch down before being stood on the spawn. */
    static final int BASE_SETTLE_TICKS = 15;
    static final int MAX_SETTLE_TICKS = 40;

    private ThrowMath() {
    }

    /** Server ticks one round trip of {@code ping} ms spans (rounded up). */
    public static int pingTicks(int ping) {
        return Math.max(0, (Math.max(0, ping) + 49) / 50);
    }

    /**
     * Flight time for an arc that bulges {@code bulge} blocks above its chord: the path chord(u) + 4A·u(1-u)
     * accelerates downwards by 8A/T² per tick², so T is picked to make that Minecraft's gravity.
     */
    public static int ticks(double bulge) {
        return (int) Math.clamp(Math.round(Math.sqrt(8 * Math.max(0, bulge) / GRAVITY)), 14, 80);
    }

    /** How long to wait for touch-down after steering ends: the base wait plus the player's round trip. */
    public static int settleTicks(int ping) {
        return Math.min(MAX_SETTLE_TICKS, BASE_SETTLE_TICKS + pingTicks(ping));
    }

    /** After this many steering ticks a throw the server never saw move is given up (stall ticks plus round trip). */
    public static int stallTicks(int stall, int ping) {
        return Math.max(1, stall) + pingTicks(ping);
    }

    /** Height at progress {@code u} (0..1) of the arc from {@code y0} to {@code y1} bulging {@code bulge} above the chord. */
    public static double y(double y0, double y1, double bulge, double u) {
        return y0 + (y1 - y0) * u + 4 * bulge * u * (1 - u);
    }
}
