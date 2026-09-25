package top.cheesesmp.duelcore.match;

/**
 * Pure helpers for matches with any number of teams (a 1v1 has two, a free-for-all one per fighter). No Bukkit
 * types, so the round logic and the free-for-all spawn ring are unit tested.
 */
public final class Teams {

    /** {@link #outcome} result while two or more teams still have a fighter standing. */
    public static final int ONGOING = -2;

    private Teams() {
    }

    /**
     * Round outcome from which teams still have someone standing: {@link #ONGOING} while at least two do, the index
     * of the last team standing, or -1 (a draw) when nobody is left.
     */
    public static int outcome(boolean[] alive) {
        int last = -1;
        int count = 0;
        for (int t = 0; t < alive.length; t++) {
            if (!alive[t]) continue;
            count++;
            last = t;
        }
        if (count >= 2) return ONGOING;
        return count == 1 ? last : -1;
    }

    /** Index of the single highest value, or -1 when another value is within {@code epsilon} of it (a tie). */
    public static int best(double[] values, double epsilon) {
        if (values.length == 0) return -1;
        int best = 0;
        for (int i = 1; i < values.length; i++) if (values[i] > values[best]) best = i;
        for (int i = 0; i < values.length; i++) {
            if (i != best && values[best] - values[i] <= epsilon) return -1;
        }
        return best;
    }

    /** Team with the most round wins, or -1 when the lead is shared. */
    public static int leader(int[] scores) {
        double[] values = new double[scores.length];
        for (int i = 0; i < scores.length; i++) values[i] = scores[i];
        return best(values, 0);
    }

    /** Highest score of any team other than {@code team} (for two teams simply the opponent's). */
    public static int bestOther(int[] scores, int team) {
        int best = 0;
        for (int i = 0; i < scores.length; i++) if (i != team) best = Math.max(best, scores[i]);
        return best;
    }

    /**
     * Where fighter {@code index} of {@code count} starts a free-for-all: evenly spaced on a circle around the middle
     * of the two arena spawns A and B, the first one on A's side. The radius is half the distance between the spawns,
     * at most {@code maxRadius} (keeps everyone inside the arena) and at least 2. Returns {x, z, yaw}, the yaw facing
     * the centre (Minecraft yaw: 0 = +z, 90 = -x).
     */
    public static double[] ring(double ax, double az, double bx, double bz, int index, int count, double maxRadius) {
        double cx = (ax + bx) / 2;
        double cz = (az + bz) / 2;
        double radius = Math.max(2, Math.min(Math.hypot(bx - ax, bz - az) / 2, maxRadius));
        double start = ax == bx && az == bz ? 0 : Math.atan2(az - cz, ax - cx);
        double angle = start + 2 * Math.PI * index / Math.max(1, count);
        double x = cx + radius * Math.cos(angle);
        double z = cz + radius * Math.sin(angle);
        double yaw = -Math.toDegrees(Math.atan2(cx - x, cz - z));
        return new double[] {x, z, yaw};
    }
}
