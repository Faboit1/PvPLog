package top.cheesesmp.duelcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.cheesesmp.duelcore.queue.MatchPolicy;
import top.cheesesmp.duelcore.queue.Matchmaker;
import top.cheesesmp.duelcore.queue.QueueEntry;
import top.cheesesmp.duelcore.queue.QueueMode;
import top.cheesesmp.duelcore.queue.RegionPingPolicy;

class MatchmakerTest {

    private static QueueEntry entry(String name, double rating, long joinedAt, String region, int ping) {
        return new QueueEntry(UUID.randomUUID(), name, "sword", QueueMode.RANKED, rating, joinedAt, region, ping, 0, List.of());
    }

    private final Matchmaker mm = new Matchmaker(new Matchmaker.Window(50, 10, 400), MatchPolicy.NONE);

    @Test
    void windowWidensWithWait() {
        QueueEntry a = entry("a", 1000, 0, null, 20);
        QueueEntry b = entry("b", 1200, 0, null, 20);
        assertTrue(mm.pair(List.of(a, b), 1_000).isEmpty(), "200 gap must not fit 60 window");
        assertTrue(mm.pair(List.of(a, b), 14_000).isEmpty(), "190 < 200 at 14s");
        assertEquals(1, mm.pair(List.of(a, b), 15_000).size(), "window reaches 200 at 15s");
        assertEquals(400, mm.windowFor(a, 1_000_000), 1e-9);
    }

    @Test
    void bothWindowsMustFit() {
        QueueEntry old = entry("old", 1000, 0, null, 20);
        QueueEntry fresh = entry("fresh", 1150, 30_000, null, 20);
        // at 31s: old window = 360, fresh window = 60 → gap 150 fits old but not fresh
        assertTrue(mm.pair(List.of(old, fresh), 31_000).isEmpty());
        // at 40s: fresh waited 10s → 150 window → fits both
        assertEquals(1, mm.pair(List.of(old, fresh), 40_000).size());
    }

    @Test
    void neverSelfAndClosestPartnerWins() {
        UUID same = UUID.randomUUID();
        QueueEntry a1 = new QueueEntry(same, "a", "sword", QueueMode.RANKED, 1000, 0, null, 0, 0, List.of());
        QueueEntry a2 = new QueueEntry(same, "a", "sword", QueueMode.RANKED, 1000, 1, null, 0, 0, List.of());
        assertTrue(mm.pair(List.of(a1, a2), 100_000).isEmpty());

        QueueEntry a = entry("a", 1000, 0, null, 0);
        QueueEntry far = entry("far", 1040, 1, null, 0);
        QueueEntry near = entry("near", 1010, 2, null, 0);
        List<Matchmaker.Pair> pairs = mm.pair(List.of(a, far, near), 3);
        assertEquals(1, pairs.size());
        assertEquals("near", pairs.get(0).b().name());
    }

    @Test
    void manyPlayersAllPairedOnce() {
        List<QueueEntry> entries = new ArrayList<>();
        for (int i = 0; i < 12; i++) entries.add(entry("p" + i, 1000 + i * 5, i, null, 0));
        List<Matchmaker.Pair> pairs = mm.pair(entries, 100);
        assertEquals(6, pairs.size());
        long distinct = pairs.stream().flatMap(p -> java.util.stream.Stream.of(p.a().player(), p.b().player())).distinct().count();
        assertEquals(12, distinct);
        pairs.forEach(p -> assertNotEquals(p.a().player(), p.b().player()));
    }

    @Test
    void unrankedIgnoresRating() {
        QueueEntry a = new QueueEntry(UUID.randomUUID(), "a", "sword", QueueMode.UNRANKED, 500, 0, null, 0, 0, List.of());
        QueueEntry b = new QueueEntry(UUID.randomUUID(), "b", "sword", QueueMode.UNRANKED, 2500, 0, null, 0, 0, List.of());
        assertEquals(1, mm.pair(List.of(a, b), 0).size());
    }

    @Test
    void regionPreferenceThenRelax() {
        Matchmaker regional = new Matchmaker(new Matchmaker.Window(1000, 0, 1000),
            new RegionPingPolicy(true, 300, true, 1.0, 200, 20));
        QueueEntry eu = entry("eu", 1000, 0, "EU", 30);
        QueueEntry na = entry("na", 1000, 0, "NA", 120);
        QueueEntry eu2 = entry("eu2", 1100, 0, "EU", 35);
        // same-region partner preferred despite larger rating gap
        List<Matchmaker.Pair> pairs = regional.pair(List.of(eu, na, eu2), 1_000);
        assertEquals("eu2", pairs.get(0).b().name());
        RegionPingPolicy p = new RegionPingPolicy(true, 300, true, 1.0, 200, 20);
        assertTrue(p.cost(eu, na, 1_000) > 300);
        assertEquals(0, p.cost(eu, na, 20_000), 1e-9);
    }
}
