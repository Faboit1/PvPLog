package top.cheesesmp.duelcore.ui.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class ProgressBarTest {

    private final ProgressBar bar = ProgressBar.standard();

    @Test
    void segmentsAtExactValues() {
        assertEquals("▱▱▱▱▱▱▱▱▱▱", bar.plain(0));
        assertEquals("▰▰▰▰▱▱▱▱▱▱", bar.plain(0.4));
        assertEquals("▰▰▰▰▰▰▰▰▰▰", bar.plain(1));
        assertEquals("▰▰▰▰▰▰▰▰▰▰", bar.plain(1.7));
        assertEquals(4, bar.full(0.4));
        assertEquals(6, bar.full(0.6)); // 0.6 * 10 is 5.999… in doubles
        for (ProgressBar.Segment s : bar.segments(0.6)) assertTrue(s.kind() != ProgressBar.Kind.HEAD);
    }

    @Test
    void partlyFilledSegmentIsTheHead() {
        List<ProgressBar.Segment> segs = bar.segments(0.45);
        assertEquals(ProgressBar.Kind.FULL, segs.get(3).kind());
        assertEquals(ProgressBar.Kind.HEAD, segs.get(4).kind());
        assertEquals(0.5, segs.get(4).fill(), 1e-9);
        assertEquals(ProgressBar.Kind.EMPTY, segs.get(5).kind());
        assertEquals("▰▰▰▰▰▱▱▱▱▱", bar.plain(0.45));
        Component c = bar.render(0.45);
        assertEquals("▰▰▰▰▰▱▱▱▱▱", PlainTextComponentSerializer.plainText().serialize(c));
        assertEquals(bar.filledColor(), c.children().get(3).color());
        assertEquals(bar.emptyColor(), c.children().get(5).color());
        assertTrue(!c.children().get(4).color().equals(bar.filledColor()) && !c.children().get(4).color().equals(bar.emptyColor()));
    }

    @Test
    void customSegmentsAndClamping() {
        ProgressBar five = new ProgressBar(5, "#", "-", bar.filledColor(), bar.emptyColor(), bar.headColor());
        assertEquals("##---", five.plain(0.4));
        assertEquals(1, new ProgressBar(0, "#", "-", bar.filledColor(), bar.emptyColor(), bar.headColor()).count());
    }
}
