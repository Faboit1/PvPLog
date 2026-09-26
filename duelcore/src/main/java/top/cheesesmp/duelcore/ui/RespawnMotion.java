package top.cheesesmp.duelcore.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.ui.anim.Ease;

/**
 * The respawn animation styles of {@link RespawnPull} and their camera math, kept free of Bukkit so it can be tested.
 *
 * <p>Rotations are sent once per tick (the client snaps to each), so every curve eases in and out: the steps are
 * small at both ends, where the eye notices them most.
 */
public final class RespawnMotion {

    /** Ticks of the look-down (and of the look back up): about 0.6 s each. */
    public static final int LOOK_TICKS = 12;
    /** Longest wait at the spawn, still looking down, for the teleport to land before looking back up. */
    public static final int LOOK_HOLD_MAX_TICKS = 20;
    /** Ticks of the full 360° spin (1 s); the teleport happens exactly half way. */
    public static final int SPIN_TICKS = 20;
    /** Straight down. */
    public static final float DOWN = 90f;

    /** One respawn animation. */
    public enum Style {
        /** Carried along a ballistic arc onto the spawn. */
        THROW("throw"),
        /** Look slowly down, teleport, look slowly back up. */
        LOOK_DOWN("look-down"),
        /** One smooth 360° turn, teleported half way through it. */
        SPIN("spin");

        public final String key;

        Style(String key) {
            this.key = key;
        }

        /** The style named {@code name} (case and {@code _}/{@code -} don't matter), or null. */
        public static @Nullable Style parse(String name) {
            String n = name.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            for (Style s : values()) if (s.key.equals(n)) return s;
            return null;
        }
    }

    /**
     * The configured styles with their weights ({@code animations.respawn-styles}): entries are {@code "<style>"} or
     * {@code "<style> <weight>"}; a style listed twice adds up. Nothing usable means {@link Style#THROW}.
     */
    public static final class Pool {
        private final List<Style> styles;
        private final int[] weights;
        private final int total;
        private final List<String> problems;

        private Pool(List<Style> styles, int[] weights, List<String> problems) {
            this.styles = List.copyOf(styles);
            this.weights = weights;
            int sum = 0;
            for (int w : weights) sum += w;
            this.total = sum;
            this.problems = List.copyOf(problems);
        }

        public static Pool parse(List<String> entries) {
            List<Style> styles = new ArrayList<>();
            List<Integer> weights = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            for (String entry : entries) {
                if (entry == null || entry.isBlank()) continue;
                String[] parts = entry.trim().split("\\s+");
                Style style = Style.parse(parts[0]);
                if (style == null) {
                    problems.add("unknown respawn style '" + parts[0] + "' (throw, look-down, spin)");
                    continue;
                }
                int weight = 1;
                if (parts.length > 1) {
                    try {
                        weight = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        weight = -1;
                    }
                    if (weight < 0 || parts.length > 2) {
                        problems.add("bad respawn style entry '" + entry.trim() + "' (use '<style>' or '<style> <weight>')");
                        continue;
                    }
                }
                if (weight == 0) continue;
                int at = styles.indexOf(style);
                if (at >= 0) {
                    weights.set(at, weights.get(at) + weight);
                } else {
                    styles.add(style);
                    weights.add(weight);
                }
            }
            if (styles.isEmpty()) {
                styles.add(Style.THROW);
                weights.add(1);
            }
            return new Pool(styles, weights.stream().mapToInt(Integer::intValue).toArray(), problems);
        }

        /** The style for a random number {@code r} in [0, 1). */
        public Style pick(double r) {
            double at = Math.clamp(r, 0, Math.nextDown(1.0)) * total;
            for (int i = 0; i < styles.size(); i++) {
                at -= weights[i];
                if (at < 0) return styles.get(i);
            }
            return styles.getLast();
        }

        public List<Style> styles() {
            return styles;
        }

        /** Weight of {@code style} (0 when not listed). */
        public int weight(Style style) {
            int at = styles.indexOf(style);
            return at < 0 ? 0 : weights[at];
        }

        /** Entries that were skipped, for the load log. */
        public List<String> problems() {
            return problems;
        }
    }

    private RespawnMotion() {
    }

    /** {@code degrees} wrapped into [-180, 180). */
    public static float wrap(double degrees) {
        double d = (degrees + 180) % 360;
        if (d < 0) d += 360;
        return (float) (d - 180);
    }

    /** Pitch at {@code tick} of {@code ticks} while looking down from {@code from} to straight down. */
    public static float lookDown(float from, int tick, int ticks) {
        return (float) Ease.lerp(from, DOWN, Ease.easeInOutSine(Ease.progress(tick, ticks)));
    }

    /** Pitch at {@code tick} of {@code ticks} while looking back up from straight down to {@code to}. */
    public static float lookUp(float to, int tick, int ticks) {
        return (float) Ease.lerp(DOWN, to, Ease.easeInOutSine(Ease.progress(tick, ticks)));
    }

    /**
     * How far the spin turns from {@code from} to end facing {@code to}: one full turn plus the shortest way between
     * them, so always at least half a turn and never more than one and a half.
     */
    public static float spinTotal(float from, float to) {
        return 360f + wrap(to - from);
    }

    /** Yaw at {@code tick} of {@code ticks} of a spin from {@code from} ending on {@code to} (see {@link #spinTotal}). */
    public static float spinYaw(float from, float to, int tick, int ticks) {
        return wrap(from + spinTotal(from, to) * Ease.easeInOutSine(Ease.progress(tick, ticks)));
    }

    /** Pitch at {@code tick} of {@code ticks} of a spin: levels out from {@code from} to {@code to} over the spin. */
    public static float spinPitch(float from, float to, int tick, int ticks) {
        return (float) Ease.lerp(from, to, Ease.easeInOutSine(Ease.progress(tick, ticks)));
    }

    /** The tick of the spin at which the player is teleported: exactly half way through the turn. */
    public static int spinTeleportTick(int ticks) {
        return ticks / 2;
    }
}
