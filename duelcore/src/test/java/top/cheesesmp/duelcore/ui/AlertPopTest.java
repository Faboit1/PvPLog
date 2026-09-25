package top.cheesesmp.duelcore.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import top.cheesesmp.duelcore.ui.anim.Sfx;

class AlertPopTest {

    @Test
    void alertSoundsAreShortAndSoft() {
        for (AlertPop.Kind kind : AlertPop.Kind.values()) {
            List<Sfx.Note> notes = AlertPop.notes(kind);
            assertFalse(notes.isEmpty(), kind.name());
            assertTrue(notes.size() <= 3, kind.name());
            assertEquals(0, notes.getFirst().delay(), kind + " starts right away");
            assertTrue(Sfx.length(notes) <= 6, kind + " is short");
            for (Sfx.Note n : notes) {
                assertTrue(n.pitch() >= 0.5f && n.pitch() <= 2f, kind + " pitch");
                assertTrue(n.volume() > 0 && n.volume() <= 0.7f, kind + " volume");
            }
        }
    }
}
