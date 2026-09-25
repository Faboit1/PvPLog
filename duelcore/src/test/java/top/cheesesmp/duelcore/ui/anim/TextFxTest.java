package top.cheesesmp.duelcore.ui.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class TextFxTest {

    private static final TextColor RED = TextColor.color(0xFF0000);
    private static final TextColor BLUE = TextColor.color(0x0000FF);

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void colourLerp() {
        assertEquals(RED, TextFx.lerp(RED, BLUE, 0));
        assertEquals(BLUE, TextFx.lerp(RED, BLUE, 1));
        assertEquals(BLUE, TextFx.lerp(RED, BLUE, 3)); // clamped
        TextColor mid = TextFx.lerp(TextColor.color(0x000000), TextColor.color(0xFFFFFF), 0.5);
        assertTrue(Math.abs(mid.red() - 127) <= 1 && mid.red() == mid.green() && mid.green() == mid.blue(), mid.asHexString());
        assertEquals(RED, TextFx.color("#ff0000", BLUE));
        assertEquals(BLUE, TextFx.color("nope", BLUE));
        assertEquals(BLUE, TextFx.color(null, BLUE));
    }

    @Test
    void firstColourAndRecolour() {
        Component c = Component.text("a").append(Component.text("b", RED)).append(Component.text("c", BLUE));
        assertEquals(RED, TextFx.firstColor(c));
        assertNull(TextFx.firstColor(Component.text("x")));
        Component white = TextFx.recolor(c, TextFx.WHITE);
        assertEquals(TextFx.WHITE, white.color());
        for (Component child : white.children()) assertEquals(TextFx.WHITE, child.color());
        assertEquals("abc", plain(white));
    }

    @Test
    void fadeMovesEveryColourTowardsTheTarget() {
        Component c = Component.text("a").append(Component.text("b", RED));
        Component start = TextFx.fade(c, BLUE, 0, TextFx.WHITE);
        assertEquals(TextFx.WHITE, start.color());
        assertEquals(RED, start.children().getFirst().color());
        Component end = TextFx.fade(c, BLUE, 1, TextFx.WHITE);
        assertEquals(BLUE, end.color());
        assertEquals(BLUE, end.children().getFirst().color());
        Component half = TextFx.fade(c, BLUE, 0.5, TextFx.WHITE);
        TextColor h = half.children().getFirst().color();
        assertTrue(h.red() > 100 && h.red() < 150 && h.blue() > 100 && h.blue() < 150, h.asHexString());
        // an uncoloured child inherits the (faded) root colour
        assertNull(TextFx.fade(Component.text("x").append(Component.text("y")), BLUE, 0.5, TextFx.WHITE).children().getFirst().color());
    }

    @Test
    void typewriterRevealsCodePointsAndKeepsStyles() {
        assertEquals("", TextFx.typewriter("Placed!", 0));
        assertEquals("Pla", TextFx.typewriter("Placed!", 3));
        assertEquals("Placed!", TextFx.typewriter("Placed!", 99));
        assertEquals("▰▰", TextFx.typewriter("▰▰▱", 2));
        assertEquals("a😀", TextFx.typewriter("a😀b", 2)); // a surrogate pair is one unit

        Component c = Component.text("Placed: ", NamedTextColor.GRAY).append(Component.text("HT3", RED)).append(Component.text("!"));
        assertEquals(12, TextFx.length(c));
        assertEquals("", plain(TextFx.typewriter(c, 0)));
        assertEquals("Placed: H", plain(TextFx.typewriter(c, 9)));
        Component partial = TextFx.typewriter(c, 10);
        assertEquals("Placed: HT", plain(partial));
        assertEquals(RED, partial.children().getFirst().color());
        assertEquals(NamedTextColor.GRAY, partial.color());
        assertEquals(c, TextFx.typewriter(c, 12));
        assertEquals("Placed: HT3!", plain(TextFx.typewriter(c, 1.0)));
        assertEquals("", plain(TextFx.typewriter(c, 0.0)));
        assertEquals("Placed", plain(TextFx.typewriter(c, 0.5)));
    }

    @Test
    void nonTextComponentsCountAsOneUnit() {
        Component c = Component.text("a").append(Component.translatable("item.minecraft.mace")).append(Component.text("b"));
        assertEquals(3, TextFx.length(c));
        assertEquals(1, TextFx.typewriter(c, 2).children().size());
    }

    @Test
    void shimmerSweepsABrightBand() {
        Component start = TextFx.shimmer("HT3", RED, TextFx.WHITE, 0, 1.5);
        assertEquals("HT3", plain(start));
        assertEquals(3, start.children().size());
        // before the sweep only the first letter is touched a little, after it all are the base colour
        for (Component ch : TextFx.shimmer("HT3", RED, TextFx.WHITE, 1, 1.5).children()) {
            assertEquals(RED, ch.color());
        }
        // mid sweep the middle letter is the brightest
        Component mid = TextFx.shimmer("HT3", RED, TextFx.WHITE, 0.5, 1.5);
        int g0 = ((TextComponent) mid.children().get(0)).color().green();
        int g1 = ((TextComponent) mid.children().get(1)).color().green();
        int g2 = ((TextComponent) mid.children().get(2)).color().green();
        assertEquals(255, g1);
        assertTrue(g0 < g1 && g2 < g1);
        assertEquals(1, TextFx.glow(1, 3, 0.5, 1.5), 1e-9);
        assertEquals(0, TextFx.glow(0, 3, 1, 1.5), 1e-9);
    }
}
