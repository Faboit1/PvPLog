package top.cheesesmp.duelcore.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import top.cheesesmp.duelcore.ui.anim.Sfx;

class MatchFxMathTest {

    private static final TextColor RED = TextColor.color(0xFF0000);
    private static final TextColor YELLOW = TextColor.color(0xFFFF00);
    private static final TextColor GREEN = TextColor.color(0x00FF00);
    private static final TextColor WHITE = TextColor.color(0xFFFFFF);

    @Test
    void countdownColourBySecondsLeft() {
        List<TextColor> colors = List.of(RED, YELLOW, GREEN);
        assertEquals(RED, MatchFxMath.countdownColor(colors, 1, WHITE));
        assertEquals(YELLOW, MatchFxMath.countdownColor(colors, 2, WHITE));
        assertEquals(GREEN, MatchFxMath.countdownColor(colors, 3, WHITE));
        assertEquals(GREEN, MatchFxMath.countdownColor(colors, 9, WHITE));
        assertEquals(RED, MatchFxMath.countdownColor(colors, 0, WHITE));
        assertEquals(WHITE, MatchFxMath.countdownColor(List.of(), 2, WHITE));
    }

    @Test
    void countdownPitchRisesEachSecond() {
        assertEquals(0, MatchFxMath.countdownSemitones(4, 4));
        assertEquals(2, MatchFxMath.countdownSemitones(3, 4));
        assertEquals(6, MatchFxMath.countdownSemitones(1, 4));
        assertEquals(0, MatchFxMath.countdownSemitones(1, 1));
    }

    @Test
    void sweepKeepsTextAndColoursEveryLetter() {
        Component c = MatchFxMath.sweep("FIGHT!", List.of(YELLOW, RED), WHITE, 0.5, 1.5, TextDecoration.BOLD);
        assertEquals("FIGHT!", PlainTextComponentSerializer.plainText().serialize(c));
        assertEquals(6, c.children().size());
        assertTrue(c.hasDecoration(TextDecoration.BOLD));
        // band off the right end: plain gradient from the first stop to the last
        Component done = MatchFxMath.sweep("AB", List.of(YELLOW, RED), WHITE, 1, 0.5);
        assertEquals(YELLOW, done.children().get(0).color());
        assertEquals(RED, done.children().get(1).color());
    }

    @Test
    void gradientStops() {
        assertEquals(YELLOW, MatchFxMath.gradientAt(List.of(RED, YELLOW, GREEN), 0.5, WHITE));
        assertEquals(GREEN, MatchFxMath.gradientAt(List.of(RED, YELLOW, GREEN), 1, WHITE));
        assertEquals(RED, MatchFxMath.gradientAt(List.of(RED), 0.7, WHITE));
        assertEquals(WHITE, MatchFxMath.gradientAt(List.of(), 0.7, WHITE));
    }

    @Test
    void matchPoint() {
        assertEquals(List.of(0), MatchFxMath.matchPoint(new int[] {2, 1}, 3));
        assertEquals(List.of(0, 1), MatchFxMath.matchPoint(new int[] {2, 2}, 3));
        assertEquals(List.of(), MatchFxMath.matchPoint(new int[] {1, 0}, 3));
        assertEquals(List.of(), MatchFxMath.matchPoint(new int[] {0, 0}, 1)); // single round: no match point
    }

    @Test
    void heartbeatFasterWhenLower() {
        assertEquals(32, MatchFxMath.heartbeatInterval(6, 6));
        assertEquals(16, MatchFxMath.heartbeatInterval(0, 6));
        assertTrue(MatchFxMath.heartbeatInterval(2, 6) < MatchFxMath.heartbeatInterval(5, 6));
        assertTrue(MatchFxMath.lowHealth(5.9, 6));
        assertTrue(MatchFxMath.lowHealth(6, 6));
        assertFalse(MatchFxMath.lowHealth(6.1, 6));
        assertFalse(MatchFxMath.lowHealth(0, 6));
    }

    @Test
    void pulseIsLubDub() {
        assertEquals(1, MatchFxMath.pulse(0), 1e-9);
        assertTrue(MatchFxMath.pulse(4) < MatchFxMath.pulse(6)); // the "dub" brightens again
        assertEquals(0.6, MatchFxMath.pulse(6), 1e-9);
        assertEquals(0, MatchFxMath.pulse(12), 1e-9);
    }

    @Test
    void phrasesStayInRange() {
        for (Sfx.Note n : MatchFxMath.victoryJingle()) assertTrue(n.pitch() >= 0.5f && n.pitch() <= 2f);
        assertTrue(Sfx.length(MatchFxMath.victoryJingle()) <= 20);
        List<Sfx.Note> defeat = MatchFxMath.defeatPhrase();
        for (int i = 1; i < defeat.size(); i++) assertTrue(defeat.get(i).pitch() < defeat.get(i - 1).pitch());
    }
}
