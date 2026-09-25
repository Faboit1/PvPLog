package top.cheesesmp.duelcore.ui.anim;

import java.util.List;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

/**
 * A per-player animation run by {@link AnimationService} on one {@link Channel}. {@link #frame} is called on the main
 * thread every {@link #period()} ticks with the ticks since the start (0, period, 2·period, …) until it returns
 * false. {@link #end} is called exactly once afterwards, also when the animation is replaced, cancelled (quit, world
 * change, plugin disable) or throws: clean up there (clear the action bar, hide a boss bar).
 *
 * <pre>
 * plugin.anim().start(player, Channel.ACTION_BAR, Animation.timed(40, (p, tick) -&gt;
 *     p.sendActionBar(Component.text(Ease.count(0, 25, tick, 30, Ease.OUT_CUBIC)))));
 * </pre>
 */
public interface Animation {

    /** Why an animation stopped. */
    enum End { FINISHED, REPLACED, CANCELLED }

    /** Draws frame {@code tick} (ticks since the start). False = finished (no more frames). */
    boolean frame(Player player, int tick);

    /** Ticks between frames. Visual channels are held to at least 2 (packet budget); sounds may use 1. */
    default int period() {
        return 2;
    }

    /** Called once when the animation stops, for whatever reason. */
    default void end(Player player, End reason) {
    }

    /** False (the default) cancels the animation when the player changes worlds. */
    default boolean survivesWorldChange() {
        return false;
    }

    // ------------------------------------------------------------------ building blocks

    /** A frame callback that also gets the tick. */
    @FunctionalInterface
    interface Frame {
        void draw(Player player, int tick);
    }

    /** Calls {@code frame} every 2 ticks for {@code ticks} ticks (the last frame is at tick ≥ ticks - 2). */
    static Animation timed(int ticks, Frame frame) {
        return timed(ticks, 2, frame);
    }

    static Animation timed(int ticks, int period, Frame frame) {
        return new Animation() {
            @Override
            public boolean frame(Player player, int tick) {
                if (tick >= ticks) return false;
                frame.draw(player, tick);
                return true;
            }

            @Override
            public int period() {
                return period;
            }
        };
    }

    /** Does nothing for {@code ticks} ticks (a gap in a {@link #sequence}). */
    static Animation delay(int ticks) {
        return timed(ticks, 1, (p, t) -> { });
    }

    /** Runs {@code action} once. */
    static Animation once(Consumer<Player> action) {
        return new Animation() {
            @Override
            public boolean frame(Player player, int tick) {
                action.accept(player);
                return false;
            }

            @Override
            public int period() {
                return 1;
            }
        };
    }

    /** The parts one after another; each part gets its own tick count from 0 and keeps its own period. */
    static Animation sequence(Animation... parts) {
        return new Sequence(List.of(parts));
    }

    /** Same animation with a cleanup (run once, whatever the reason) added after its own {@link #end}. */
    default Animation onEnd(java.util.function.BiConsumer<Player, End> cleanup) {
        Animation self = this;
        return new Animation() {
            @Override
            public boolean frame(Player player, int tick) {
                return self.frame(player, tick);
            }

            @Override
            public int period() {
                return self.period();
            }

            @Override
            public void end(Player player, End reason) {
                try {
                    self.end(player, reason);
                } finally {
                    cleanup.accept(player, reason);
                }
            }

            @Override
            public boolean survivesWorldChange() {
                return self.survivesWorldChange();
            }
        };
    }

    /** See {@link #sequence}. Runs every tick and hands each part the frames it asked for. */
    final class Sequence implements Animation {

        private final List<Animation> parts;
        private int index;
        private int startedAt;

        Sequence(List<Animation> parts) {
            this.parts = parts;
        }

        @Override
        public boolean frame(Player player, int tick) {
            while (index < parts.size()) {
                Animation part = parts.get(index);
                int local = tick - startedAt;
                if (local % Math.max(1, part.period()) != 0) return true;
                if (part.frame(player, local)) return true;
                part.end(player, End.FINISHED);
                index++;
                startedAt = tick; // the next part starts right away, in this same tick
            }
            return false;
        }

        @Override
        public int period() {
            return 1;
        }

        @Override
        public void end(Player player, End reason) {
            if (index < parts.size()) parts.get(index).end(player, reason);
            index = parts.size();
        }
    }
}
