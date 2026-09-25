package top.cheesesmp.duelcore.ui.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

class BlinkFadeTest {

    private static final TextColor GRAY = TextColor.color(0x6B7078);
    private static final TextColor GREEN = TextColor.color(0x7FD99A);

    @Test
    void blinksThenFadesThenEnds() {
        BlinkFade bf = new BlinkFade(3, 4, 60, TextFx.WHITE, GRAY);
        assertEquals(84, bf.length());
        assertTrue(bf.flashing(0));
        assertTrue(bf.flashing(3));
        assertFalse(bf.flashing(4));
        assertTrue(bf.flashing(8));
        assertFalse(bf.flashing(23));
        assertFalse(bf.flashing(24));
        assertEquals(0, bf.fade(20));
        assertEquals(0, bf.fade(24));
        assertEquals(0.5, bf.fade(54), 1e-9);
        assertEquals(1, bf.fade(84), 1e-9);
        assertFalse(bf.done(83));
        assertTrue(bf.done(84));
    }

    @Test
    void appliesColours() {
        BlinkFade bf = new BlinkFade(2, 2, 10, TextFx.WHITE, GRAY);
        Component text = Component.text("+18 Elo", GREEN);
        assertEquals(TextFx.WHITE, bf.apply(text, 0).color());
        assertEquals(GREEN, bf.apply(text, 2).color());
        assertEquals(GREEN, bf.apply(text, 8).color());
        TextColor nearlyGray = bf.apply(text, 17).color();
        assertTrue(Math.abs(nearlyGray.green() - GRAY.green()) < 8, nearlyGray.asHexString());
        assertNull(bf.apply(text, 18));
        // uncoloured text fades from white
        TextColor half = new BlinkFade(0, 1, 10, TextFx.WHITE, TextColor.color(0)).apply(Component.text("x"), 5).color();
        assertTrue(half.red() > 100 && half.red() < 155, half.asHexString());
    }
}
