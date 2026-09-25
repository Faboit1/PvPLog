package top.cheesesmp.duelcore.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import top.cheesesmp.duelcore.ui.anim.TextFx;

/**
 * A colour wave over plain text (the tab list logo): the characters sit on one cosine cycle between two colours
 * spread over the whole text, and moving {@code phase} (0..1, wraps) slides the wave along. Unlike
 * {@link TextFx#shimmer} it has no start or end, so it can move in big steps (one per tab refresh) and still look
 * calm. Pure.
 */
public final class WaveText {

    private WaveText() {
    }

    /** How far character {@code index} of {@code total} is towards the second colour, 0..1. Periodic in phase. */
    public static double at(int index, int total, double phase) {
        double x = (double) index / Math.max(1, total) - phase;
        return 0.5 - 0.5 * Math.cos(2 * Math.PI * x);
    }

    /** The text with every code point coloured by {@link #at}. */
    public static Component wave(String text, TextColor from, TextColor to, double phase, TextDecoration... decorations) {
        int total = text.codePointCount(0, text.length());
        TextComponent.Builder out = Component.text();
        for (TextDecoration d : decorations) out.decoration(d, true);
        int i = 0;
        for (int offset = 0; offset < text.length(); i++) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            out.append(Component.text(Character.toString(cp), TextFx.lerp(from, to, at(i, total, phase))));
        }
        return out.build();
    }
}
