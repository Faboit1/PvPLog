package top.cheesesmp.duelcore.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SearchingFeedbackTest {

    @Test
    void bossBarFillsWithTheWindow() {
        assertEquals(0.02f, SearchingFeedback.bossProgress(0, 0));
        assertEquals(0.5f, SearchingFeedback.bossProgress(0.5, 123));
        assertEquals(0.37f, SearchingFeedback.bossProgress(0.3712, 7));
    }

    @Test
    void bossBarSweepsOnceWide() {
        assertEquals(0f, SearchingFeedback.bossProgress(1, 0));
        assertEquals(1f, SearchingFeedback.bossProgress(1, 40));
        assertEquals(0f, SearchingFeedback.bossProgress(1, 80));
        assertEquals(SearchingFeedback.bossProgress(1, 20), SearchingFeedback.bossProgress(1, 60));
        for (int t = 0; t < 200; t += 4) {
            float v = SearchingFeedback.bossProgress(1, t);
            assertTrue(v >= 0 && v <= 1, "tick " + t);
            assertEquals(v, Math.round(v * 100) / 100f, "rounded to 1% at tick " + t);
        }
    }
}
