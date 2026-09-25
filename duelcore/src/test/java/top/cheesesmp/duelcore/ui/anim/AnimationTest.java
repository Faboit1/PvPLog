package top.cheesesmp.duelcore.ui.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Drives the building blocks by hand, like AnimationService does (the player isn't needed by these parts). */
class AnimationTest {

    @Test
    void sequenceRunsPartsInOrderWithTheirOwnTicks() {
        List<String> log = new ArrayList<>();
        Animation seq = Animation.sequence(
            Animation.timed(4, (p, t) -> log.add("a" + t)),
            Animation.delay(3),
            Animation.once(p -> log.add("once")),
            Animation.timed(4, (p, t) -> log.add("b" + t)).onEnd((p, why) -> log.add("end-b " + why)));
        int tick = 0;
        while (seq.frame(null, tick)) tick++;
        assertEquals(List.of("a0", "a2", "once", "b0", "b2", "end-b FINISHED"), log);
        assertEquals(11, tick);
    }

    @Test
    void endingASequenceEndsTheRunningPart() {
        List<String> log = new ArrayList<>();
        Animation seq = Animation.sequence(Animation.timed(100, (p, t) -> { }).onEnd((p, why) -> log.add(why.name())));
        assertTrue(seq.frame(null, 0));
        seq.end(null, Animation.End.REPLACED);
        assertEquals(List.of("REPLACED"), log);
        assertFalse(seq.frame(null, 2));
    }
}
