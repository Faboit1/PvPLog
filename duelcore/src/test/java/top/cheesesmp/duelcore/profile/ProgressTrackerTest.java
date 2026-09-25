package top.cheesesmp.duelcore.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.cheesesmp.duelcore.profile.ProgressTracker.Reveal;
import top.cheesesmp.duelcore.rating.Tier;

class ProgressTrackerTest {

    private static KitStats stats(double rating, int games) {
        KitStats s = new KitStats(rating, 0, 0);
        s.games = games;
        return s;
    }

    @Test
    void revealDerivedValues() {
        Reveal placement = Reveal.of("sword", stats(1000, 1), stats(1024, 2), 5, null, null, false);
        assertTrue(placement.inPlacement());
        assertFalse(placement.placedNow());
        assertEquals(0.2, placement.oldProgress(), 1e-9);
        assertEquals(0.4, placement.newProgress(), 1e-9);
        assertEquals(24, placement.eloDelta());

        Reveal placed = Reveal.of("sword", stats(1400, 4), stats(1490, 5), 5, null, Tier.HT3, false);
        assertTrue(placed.placedNow());
        assertFalse(placed.tierUp()); // a first tier is "placed", not "tier up"

        Reveal up = Reveal.of("sword", stats(1470, 9), stats(1488, 10), 5, Tier.MT3, Tier.HT3, false);
        assertTrue(up.tierUp());
        assertFalse(up.tierDown());
        assertFalse(up.inPlacement());
        Reveal down = Reveal.of("sword", stats(1488, 10), stats(1470, 11), 5, Tier.HT3, Tier.MT3, false);
        assertTrue(down.tierDown());
        assertEquals(-18, down.eloDelta());

        KitStats pinned = stats(1000, 0);
        pinned.tierOverride = Tier.LT2;
        assertFalse(Reveal.of("sword", pinned, pinned, 5, Tier.LT2, Tier.LT2, false).inPlacement());
        assertEquals(1, Reveal.of("sword", stats(1000, 0), stats(1000, 1), 0, null, null, false).newProgress());
    }

    @Test
    void consumersAreIndependent() {
        ProgressTracker tracker = new ProgressTracker();
        UUID a = UUID.randomUUID();
        Reveal r = Reveal.of("sword", stats(1000, 1), stats(1024, 2), 5, null, null, false);
        tracker.record(a, r);
        assertEquals(r, tracker.pendingReveal(a, "sword"));
        assertNull(tracker.pendingReveal(a, "axe"));
        assertEquals(r, tracker.takeLatest(a));
        assertNull(tracker.takeLatest(a));
        // the queue menu still has it until it consumes it
        assertEquals(r, tracker.pendingReveal(a, "sword"));
        assertEquals(List.of("sword"), tracker.pendingKits(a));
        tracker.consume(a, "sword");
        assertNull(tracker.pendingReveal(a, "sword"));
        assertEquals(0, tracker.size());
    }

    @Test
    void severalMatchesMergeForTheMenu() {
        ProgressTracker tracker = new ProgressTracker();
        UUID a = UUID.randomUUID();
        tracker.record(a, Reveal.of("sword", stats(1000, 3), stats(1020, 4), 5, null, null, false));
        Reveal second = Reveal.of("sword", stats(1020, 4), stats(1490, 5), 5, null, Tier.HT3, false);
        tracker.record(a, second);
        Reveal merged = tracker.pendingReveal(a, "sword");
        assertNotNull(merged);
        assertEquals(3, merged.oldGames());
        assertEquals(5, merged.newGames());
        assertEquals(1000, merged.oldElo());
        assertEquals(1490, merged.newElo());
        assertNull(merged.oldTier());
        assertEquals(Tier.HT3, merged.newTier());
        assertTrue(merged.placedNow());
        assertEquals(second, tracker.takeLatest(a)); // the hub shows the last match only
    }

    @Test
    void boundedAndForgotten() {
        ProgressTracker tracker = new ProgressTracker();
        UUID a = UUID.randomUUID();
        for (int i = 0; i < ProgressTracker.MAX_KITS + 5; i++) {
            tracker.record(a, Reveal.of("kit" + i, stats(1000, 0), stats(1010, 1), 5, null, null, false));
        }
        assertEquals(ProgressTracker.MAX_KITS, tracker.pendingKits(a).size());
        assertNull(tracker.pendingReveal(a, "kit0"));
        assertNotNull(tracker.pendingReveal(a, "kit" + (ProgressTracker.MAX_KITS + 4)));
        for (int i = 0; i < ProgressTracker.MAX_PLAYERS + 3; i++) {
            tracker.record(UUID.randomUUID(), Reveal.of("sword", stats(1000, 0), stats(1010, 1), 5, null, null, false));
        }
        assertEquals(ProgressTracker.MAX_PLAYERS, tracker.size());
        assertNull(tracker.pendingReveal(a, "kit" + (ProgressTracker.MAX_KITS + 4)), "least recent player evicted");
        UUID b = UUID.randomUUID();
        tracker.record(b, Reveal.of("sword", stats(1000, 0), stats(1010, 1), 5, null, null, false));
        tracker.forget(b);
        assertNull(tracker.pendingReveal(b, "sword"));
        assertNull(tracker.takeLatest(b));
    }
}
