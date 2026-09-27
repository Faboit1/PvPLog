package top.cheesesmp.duelcore.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LastOpponentsTest {

    private static final Matchmaker.Window WINDOW = new Matchmaker.Window(50, 10, 500);

    private static QueueEntry entry(String name, double rating, long joinedAt) {
        return new QueueEntry(UUID.nameUUIDFromBytes(name.getBytes()), name, "sword", QueueMode.RANKED, rating, joinedAt,
            null, 50, 0, List.of());
    }

    @Test
    void theLastOpponentIsSkippedWhileAnyoneElseFits() {
        LastOpponents last = new LastOpponents(true, 450, WINDOW);
        QueueEntry a = entry("a", 1000, 0), b = entry("b", 1000, 0), c = entry("c", 1100, 0);
        last.record(a.player(), b.player());
        Matchmaker mm = new Matchmaker(WINDOW, last);
        // 20 s: windows 250 — b is the closer rating but was the last opponent; c is picked
        List<Matchmaker.Pair> pairs = mm.pair(List.of(a, b, c), 20_000);
        assertEquals(1, pairs.size());
        assertEquals(c.player(), pairs.getFirst().b().player().equals(a.player()) ? pairs.getFirst().a().player() : pairs.getFirst().b().player());
    }

    @Test
    void aRematchOnlyOnceBothWindowsReachTheThreshold() {
        LastOpponents last = new LastOpponents(true, 450, WINDOW);
        QueueEntry a = entry("a", 1000, 0), b = entry("b", 1000, 0);
        last.record(a.player(), b.player());
        Matchmaker mm = new Matchmaker(WINDOW, last);
        assertEquals(0, mm.pair(List.of(a, b), 39_000).size()); // window 440
        assertEquals(1, mm.pair(List.of(a, b), 40_000).size()); // window 450: nobody else, so they play again
    }

    @Test
    void offMeansNoEffect() {
        LastOpponents last = new LastOpponents(false, 450, WINDOW);
        QueueEntry a = entry("a", 1000, 0), b = entry("b", 1000, 0);
        last.record(a.player(), b.player());
        assertEquals(0.0, last.cost(a, b, 0));
    }
}
