package top.cheesesmp.duelcore.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class MusicTracksTest {

    @Test
    void parsesSecondsAndMinutes() {
        MusicTracks t = MusicTracks.parse(List.of("music_disc.pigstep 148", "minecraft:music_disc.relic 3:38", " ", "bad"));
        assertEquals(List.of(new MusicTracks.Track("minecraft:music_disc.pigstep", 148),
            new MusicTracks.Track("minecraft:music_disc.relic", 218)), t.tracks());
        assertEquals(1, t.problems().size());
    }

    @Test
    void rejectsBrokenLines() {
        assertThrows(IllegalArgumentException.class, () -> MusicTracks.track("music_disc.cat"));
        assertThrows(IllegalArgumentException.class, () -> MusicTracks.track("music_disc.cat abc"));
        assertThrows(IllegalArgumentException.class, () -> MusicTracks.track("music_disc.cat 2"));
        assertThrows(IllegalArgumentException.class, () -> MusicTracks.track("Bad Key! 100"));
    }

    @Test
    void neverRepeatsTheSameTrackRightAway() {
        MusicTracks t = MusicTracks.parse(List.of("a 10", "b 10", "c 10"));
        SplittableRandom r = new SplittableRandom(1);
        MusicTracks.Track previous = null;
        for (int i = 0; i < 200; i++) {
            MusicTracks.Track next = t.pick(r, previous);
            assertNotEquals(previous, next);
            previous = next;
        }
        MusicTracks one = MusicTracks.parse(List.of("a 10"));
        assertEquals(one.tracks().getFirst(), one.pick(r, one.tracks().getFirst()));
        assertNull(MusicTracks.EMPTY.pick(r, null));
    }

    @Test
    void filterKeepsProblems() {
        MusicTracks t = MusicTracks.parse(List.of("a 10", "b 10", "oops"));
        MusicTracks kept = t.filter(track -> track.key().endsWith("a"));
        assertEquals(1, kept.tracks().size());
        assertEquals(1, kept.problems().size());
    }

    @org.junit.jupiter.api.Test
    void speedHalvesThePlayTime() {
        MusicTracks t = MusicTracks.parse(List.of("music_disc.tears 175 2x", "music_disc.cat 185 1.5", "bad 100 3x"));
        assertEquals(2, t.tracks().size());
        assertEquals(2f, t.tracks().get(0).speed());
        assertEquals(87_500L, t.tracks().get(0).playMillis());
        assertEquals(1.5f, t.tracks().get(1).speed());
        assertEquals(1, t.problems().size());
    }
}
