package top.cheesesmp.duelcore.ui.anim;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

/**
 * The "settle" end of a notice: the text flashes {@code blinks} times (each flash {@code blinkTicks} on, then as
 * many ticks normal), then every colour fades towards {@code to} over {@code fadeTicks}, then it is gone. Pure.
 *
 * <pre>
 * new BlinkFade(3, 3, 60, TextFx.WHITE, gray).apply(text, tick)   // null once finished: clear the action bar
 * </pre>
 */
public record BlinkFade(int blinks, int blinkTicks, int fadeTicks, TextColor flash, TextColor to) {

    public BlinkFade {
        blinks = Math.max(0, blinks);
        blinkTicks = Math.max(1, blinkTicks);
        fadeTicks = Math.max(0, fadeTicks);
    }

    /** Ticks until finished. */
    public int length() {
        return blinks * blinkTicks * 2 + fadeTicks;
    }

    public boolean done(int tick) {
        return tick >= length();
    }

    /** True while a flash is on (only during the blink part). */
    public boolean flashing(int tick) {
        if (tick < 0 || tick >= blinks * blinkTicks * 2) return false;
        return (tick / blinkTicks) % 2 == 0;
    }

    /** How far the fade is, 0..1 (0 during the blinks). */
    public double fade(int tick) {
        int start = blinks * blinkTicks * 2;
        if (tick < start) return 0;
        return Ease.easeInOutSine(Ease.progress(tick - start, fadeTicks));
    }

    /**
     * The text as it looks at {@code tick}: all {@code flash} while flashing, faded towards {@code to} later, null once
     * finished. Text without a colour counts as white.
     */
    public @Nullable Component apply(Component text, int tick) {
        if (done(tick)) return null;
        if (flashing(tick)) return TextFx.recolor(text, flash);
        double f = fade(tick);
        return f <= 0 ? text : TextFx.fade(text, to, f, TextFx.WHITE);
    }
}
