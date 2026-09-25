package top.cheesesmp.duelcore.ui.anim;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;

/**
 * A segmented progress bar, e.g. {@code ▰▰▰▰▱▱▱▱▱▱}: full segments in {@code filledColor}, the empty rest in
 * {@code emptyColor}, and a partly filled "head" segment (while animating between two values) in {@code headColor},
 * brighter the fuller it is. Pure. {@link #segments(double)} gives the states for renderers that draw segments
 * themselves (the queue dialog uses head sprites); {@link #render} makes a text component for action bars, titles
 * and dialog text.
 */
public record ProgressBar(int count, String filled, String empty, TextColor filledColor, TextColor emptyColor,
                          TextColor headColor) {

    public enum Kind { FULL, HEAD, EMPTY }

    /** One segment; {@code fill} is 1 for FULL, 0 for EMPTY and in between for the HEAD. */
    public record Segment(Kind kind, double fill) {
    }

    public ProgressBar {
        count = Math.clamp(count, 1, 60);
    }

    /** The default look: 10 segments ▰/▱, green, dark grey, bright head. */
    public static ProgressBar standard() {
        return new ProgressBar(10, "▰", "▱", TextColor.color(0x5BD46A), TextColor.color(0x4B4F57), TextColor.color(0xD8FFD8));
    }

    /** Full segments at {@code progress} (0..1); segments within 0.001 of full count as full. */
    public int full(double progress) {
        return (int) Math.floor(Ease.clamp01(progress) * count + 1e-3);
    }

    public List<Segment> segments(double progress) {
        double scaled = Ease.clamp01(progress) * count;
        int full = full(progress);
        List<Segment> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (i < full) {
                out.add(new Segment(Kind.FULL, 1));
            } else if (i == full && scaled - full > 1e-3) {
                out.add(new Segment(Kind.HEAD, scaled - full));
            } else {
                out.add(new Segment(Kind.EMPTY, 0));
            }
        }
        return out;
    }

    /** The bar as text. */
    public Component render(double progress) {
        TextComponent.Builder out = Component.text();
        for (Segment s : segments(progress)) {
            out.append(switch (s.kind()) {
                case FULL -> Component.text(filled, filledColor);
                case HEAD -> Component.text(filled, TextFx.lerp(emptyColor, headColor, 0.35 + 0.65 * s.fill()));
                case EMPTY -> Component.text(empty, emptyColor);
            });
        }
        return out.build();
    }

    /** The bar as plain glyphs (the head counts as filled), e.g. for logs and tests. */
    public String plain(double progress) {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments(progress)) sb.append(s.kind() == Kind.EMPTY ? empty : filled);
        return sb.toString();
    }
}
