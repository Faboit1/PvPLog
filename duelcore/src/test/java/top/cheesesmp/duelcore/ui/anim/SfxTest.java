package top.cheesesmp.duelcore.ui.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SfxTest {

    @Test
    void pitchStepsBySemitonesAndClamps() {
        assertEquals(2f, Sfx.pitch(1f, 12), 1e-6);
        assertEquals(0.5f, Sfx.pitch(1f, -12), 1e-6);
        assertEquals(2f, Sfx.pitch(1.5f, 12), 1e-6);
        assertEquals(0.5f, Sfx.pitch(0.6f, -24), 1e-6);
        assertEquals(1.2599f, Sfx.pitch(1f, 4), 1e-3);
    }

    @Test
    void arpeggioRisesWithSpacing() {
        List<Sfx.Note> notes = Sfx.arpeggio(Sfx.PLING, 4, 1f, 4, 2, 0.5f);
        assertEquals(4, notes.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(i * 2, notes.get(i).delay());
            assertEquals(Sfx.PLING, notes.get(i).key());
            if (i > 0) assertTrue(notes.get(i).pitch() > notes.get(i - 1).pitch());
        }
        assertEquals(6, Sfx.length(notes));
        assertTrue(Sfx.arpeggio(Sfx.PLING, 0, 1f, 4, 2, 0.5f).isEmpty());
    }

    @Test
    void ticksWalkAnOctave() {
        assertEquals(0.8f, Sfx.tick(0, 10, true).pitch(), 1e-6);
        assertEquals(1.6f, Sfx.tick(9, 10, true).pitch(), 1e-6);
        assertEquals(1.6f, Sfx.tick(99, 10, true).pitch(), 1e-6);
        assertTrue(Sfx.tick(5, 10, false).pitch() < Sfx.tick(1, 10, false).pitch());
        assertEquals(0.8f, Sfx.tick(0, 1, true).pitch(), 1e-6);
    }

    @Test
    void phrases() {
        List<Sfx.Note> f = Sfx.flourish();
        assertTrue(f.stream().anyMatch(n -> n.key().equals(Sfx.LEVEL_UP)));
        assertTrue(f.stream().anyMatch(n -> n.key().equals(Sfx.AMETHYST)));
        assertTrue(Sfx.length(f) <= 20, "short");
        for (List<Sfx.Note> phrase : List.of(f, Sfx.settle(true), Sfx.settle(false), Sfx.demotion())) {
            for (Sfx.Note n : phrase) assertTrue(n.pitch() >= 0.5f && n.pitch() <= 2f && n.key().startsWith("minecraft:"));
        }
    }
}
