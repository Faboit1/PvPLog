package top.cheesesmp.duelcore.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import top.cheesesmp.duelcore.queue.MusicTracks;

class MatchMusicTest {

    private static final MusicTracks HIGH = MusicTracks.parse(List.of("music_disc.pigstep 148", "music_disc.cat 185"));
    private static final MusicTracks MEDIUM = MusicTracks.parse(List.of("music_disc.far 174"));

    @Test
    void mediumTracksComeUpAboutAsOftenAsTheirChance() {
        Random random = new Random(1);
        int medium = 0;
        for (int i = 0; i < 4000; i++) {
            if (MatchMusic.pick(HIGH, MEDIUM, 0.25, random, null).key().equals("minecraft:music_disc.far")) medium++;
        }
        assertTrue(medium > 800 && medium < 1200, "medium " + medium);
    }

    @Test
    void neverTheSameTrackTwiceInARow() {
        Random random = new Random(2);
        MusicTracks.Track prev = null;
        for (int i = 0; i < 500; i++) {
            MusicTracks.Track t = MatchMusic.pick(HIGH, MEDIUM, 0.25, random, prev);
            assertNotEquals(prev, t);
            prev = t;
        }
    }

    @Test
    void anEmptyMediumPoolMeansHighOnly() {
        Random random = new Random(3);
        for (int i = 0; i < 100; i++) {
            assertTrue(MatchMusic.pick(HIGH, MusicTracks.EMPTY, 1.0, random, null).key().contains("disc"));
        }
        assertEquals(null, MatchMusic.pick(MusicTracks.EMPTY, MusicTracks.EMPTY, 0.25, random, null));
    }

    @Test
    void theBorderClosesInLinearlyToItsTarget() {
        assertEquals(182, MatchService.borderSize(182, 10, 270, 0));
        assertEquals(96, MatchService.borderSize(182, 10, 270, 135 * 20));
        assertEquals(10, MatchService.borderSize(182, 10, 270, 270 * 20));
        assertEquals(10, MatchService.borderSize(182, 10, 270, 999 * 20));
        assertEquals(182, MatchService.borderSize(182, 10, 0, 5000));
    }
}
