package top.cheesesmp.duelcore.ui.anim;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jspecify.annotations.Nullable;

/**
 * Text effects for animated action bars, titles and dialogs. Pure (Adventure only, no Bukkit), so they are unit
 * tested. Everything works on finished components, so messages.yml templates can be rendered first and animated
 * afterwards.
 *
 * <p>Units: a text component counts one unit per code point; any other component (a sprite, a head, a translatable)
 * counts as one unit together with its children.
 */
public final class TextFx {

    public static final TextColor WHITE = TextColor.color(0xFFFFFF);

    private TextFx() {
    }

    // ------------------------------------------------------------------ colours

    /** a → b at {@code t} (clamped to 0..1). */
    public static TextColor lerp(TextColor a, TextColor b, double t) {
        return TextColor.lerp((float) Ease.clamp01(t), a, b);
    }

    /** "#rrggbb" or {@code fallback} when it isn't one. */
    public static TextColor color(@Nullable String hex, TextColor fallback) {
        TextColor c = hex == null ? null : TextColor.fromHexString(hex.trim());
        return c == null ? fallback : c;
    }

    /** The first explicit colour in a component tree (depth first), e.g. the colour of a tiers.yml format. */
    public static @Nullable TextColor firstColor(Component component) {
        if (component.color() != null) return component.color();
        for (Component child : component.children()) {
            TextColor c = firstColor(child);
            if (c != null) return c;
        }
        return null;
    }

    /** The whole tree in one colour (explicit colours of children are replaced too). */
    public static Component recolor(Component component, TextColor color) {
        List<Component> children = new ArrayList<>(component.children().size());
        for (Component child : component.children()) children.add(recolor(child, color));
        return component.color(color).children(children);
    }

    /**
     * Every colour of the tree moved {@code t} of the way towards {@code target}; text without a colour counts as
     * {@code base}. t = 0 keeps the component's look, t = 1 is all {@code target}.
     */
    public static Component fade(Component component, TextColor target, double t, TextColor base) {
        return fade(component, target, Ease.clamp01(t), base, true);
    }

    private static Component fade(Component c, TextColor target, double t, TextColor base, boolean root) {
        List<Component> children = new ArrayList<>(c.children().size());
        for (Component child : c.children()) children.add(fade(child, target, t, base, false));
        TextColor own = c.color();
        Component out = c.children(children);
        if (own != null) return out.color(lerp(own, target, t));
        return root ? out.color(lerp(base, target, t)) : out; // children without a colour inherit the faded parent
    }

    // ------------------------------------------------------------------ typewriter

    /** Units (see class doc) in a component tree. */
    public static int length(Component component) {
        if (!(component instanceof TextComponent text)) return 1;
        int n = text.content().codePointCount(0, text.content().length());
        for (Component child : component.children()) n += length(child);
        return n;
    }

    /** The first {@code visible} code points of a string. */
    public static String typewriter(String text, int visible) {
        int total = text.codePointCount(0, text.length());
        if (visible >= total) return text;
        if (visible <= 0) return "";
        return text.substring(0, text.offsetByCodePoints(0, visible));
    }

    /** The first {@code visible} units of a component, styles kept (typewriter reveal). */
    public static Component typewriter(Component component, int visible) {
        if (visible >= length(component)) return component;
        int[] budget = {Math.max(0, visible)};
        return reveal(component, budget);
    }

    /** {@link #typewriter(Component, int)} at progress 0..1 of the whole text. */
    public static Component typewriter(Component component, double progress) {
        return typewriter(component, (int) Math.ceil(length(component) * Ease.clamp01(progress)));
    }

    private static Component reveal(Component c, int[] budget) {
        if (!(c instanceof TextComponent text)) {
            if (budget[0] <= 0) return Component.empty();
            budget[0]--;
            return c;
        }
        String content = text.content();
        int cps = content.codePointCount(0, content.length());
        String shown = typewriter(content, budget[0]);
        budget[0] = Math.max(0, budget[0] - cps);
        List<Component> children = new ArrayList<>();
        for (Component child : c.children()) {
            if (budget[0] <= 0) break;
            children.add(reveal(child, budget));
        }
        return text.content(shown).children(children);
    }

    // ------------------------------------------------------------------ shimmer

    /**
     * A bright band sweeping over plain text: every code point gets {@code base} lerped towards {@code highlight}
     * by how close it is to the band's centre. {@code phase} 0 puts the band just left of the text, 1 just right of
     * it; {@code width} is the band's half width in characters.
     */
    public static Component shimmer(String text, TextColor base, TextColor highlight, double phase, double width,
                                     TextDecoration... decorations) {
        int total = text.codePointCount(0, text.length());
        TextComponent.Builder out = Component.text();
        for (TextDecoration d : decorations) out.decoration(d, true);
        int i = 0;
        for (int offset = 0; offset < text.length(); i++) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            out.append(Component.text(Character.toString(cp), lerp(base, highlight, glow(i, total, phase, width))));
        }
        return out.build();
    }

    /** How bright character {@code index} of {@code total} is in the {@link #shimmer} band, 0..1. */
    public static double glow(int index, int total, double phase, double width) {
        double w = Math.max(0.5, width);
        double centre = -w + (total - 1 + 2 * w) * Ease.clamp01(phase);
        return Math.max(0, 1 - Math.abs(index - centre) / w);
    }
}
