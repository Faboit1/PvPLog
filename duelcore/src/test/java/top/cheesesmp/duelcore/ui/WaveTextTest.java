package top.cheesesmp.duelcore.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class WaveTextTest {

    private static final TextColor A = TextColor.color(0x000000);
    private static final TextColor B = TextColor.color(0xFFFFFF);

    @Test
    void waveIsPeriodicAndBounded() {
        for (int i = 0; i < 10; i++) {
            for (double p = 0; p < 1; p += 0.13) {
                double v = WaveText.at(i, 10, p);
                assertTrue(v >= 0 && v <= 1, "at " + i + " " + p);
                assertEquals(v, WaveText.at(i, 10, p + 1), 1e-9);
                assertEquals(v, WaveText.at(i, 10, p - 1), 1e-9);
            }
        }
        assertEquals(0, WaveText.at(0, 10, 0), 1e-9);
        assertEquals(1, WaveText.at(5, 10, 0), 1e-9); // the far side of the cycle
        assertEquals(0, WaveText.at(5, 10, 0.5), 1e-9); // half a cycle later it is there
    }

    @Test
    void everyCharacterIsColouredAndStylesKept() {
        Component c = WaveText.wave("Cheese ✦", A, B, 0.25, TextDecoration.BOLD);
        assertEquals("Cheese ✦", PlainTextComponentSerializer.plainText().serialize(c));
        assertEquals(8, c.children().size());
        assertEquals(TextDecoration.State.TRUE, c.decoration(TextDecoration.BOLD));
        for (Component child : c.children()) assertNotNull(child.color());
        assertEquals(0, WaveText.wave("", A, B, 0).children().size());
    }
}
