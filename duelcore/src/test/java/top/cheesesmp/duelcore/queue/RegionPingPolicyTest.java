package top.cheesesmp.duelcore.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The latency policy: region, the soft same-country preference, ping difference, and all of it fading out. */
class RegionPingPolicyTest {

    /** Country preference only (60 points), relaxing over 30 s. */
    private static final RegionPingPolicy COUNTRY = new RegionPingPolicy(false, 250, true, 60, false, 0.75, 300, 30);

    private static QueueEntry entry(String name, double rating, @Nullable String country, boolean prefer, int ping) {
        return new QueueEntry(UUID.randomUUID(), name, "sword", QueueMode.RANKED, rating, 0, "EU", country, prefer, ping, 0,
            List.of());
    }

    @Test
    void crossCountryCostsTheSoftPenalty() {
        QueueEntry de = entry("de", 1000, "DE", true, 30);
        QueueEntry fr = entry("fr", 1000, "FR", true, 30);
        assertEquals(60, COUNTRY.cost(de, fr, 0), 1e-9);
        assertEquals(60, COUNTRY.cost(fr, de, 0), 1e-9, "symmetric");
        assertEquals(0, COUNTRY.cost(de, entry("de2", 1000, "de", true, 30), 0), 1e-9, "same country, any case");
    }

    @Test
    void onlyWhenBothKnowTheirCountryAndBothPreferIt() {
        QueueEntry de = entry("de", 1000, "DE", true, 30);
        assertEquals(0, COUNTRY.cost(de, entry("unknown", 1000, null, true, 30), 0), 1e-9);
        assertEquals(0, COUNTRY.cost(entry("unknown", 1000, null, true, 30), de, 0), 1e-9);
        assertEquals(0, COUNTRY.cost(de, entry("fr-off", 1000, "FR", false, 30), 0), 1e-9, "the other turned it off");
        assertEquals(0, COUNTRY.cost(entry("de-off", 1000, "DE", false, 30), entry("fr", 1000, "FR", true, 30), 0), 1e-9);
        assertFalse(RegionPingPolicy.crossCountry(de, entry("fr-off", 1000, "FR", false, 30)));
        assertTrue(RegionPingPolicy.crossCountry(de, entry("fr", 1000, "FR", true, 30)));
        RegionPingPolicy off = new RegionPingPolicy(false, 250, false, 60, false, 0.75, 300, 30);
        assertEquals(0, off.cost(de, entry("fr", 1000, "FR", true, 30), 0), 1e-9, "matchmaking.country.enabled: false");
        // the constructor from before the country preference has none
        RegionPingPolicy old = new RegionPingPolicy(false, 250, false, 0.75, 300, 30);
        assertEquals(0, old.cost(de, entry("fr", 1000, "FR", true, 30), 0), 1e-9);
    }

    private static QueueEntry full(String region, String country, long joinedAt, int ping, int maxPing) {
        return new QueueEntry(UUID.randomUUID(), country, "sword", QueueMode.RANKED, 1000, joinedAt, region, country, true,
            ping, maxPing, List.of());
    }

    @Test
    void everyPenaltyFadesToZeroAfterTheRelaxTime() {
        RegionPingPolicy all = new RegionPingPolicy(true, 250, true, 60, true, 0.75, 300, 30);
        QueueEntry a = full("EU", "DE", 0, 20, 50);
        QueueEntry b = full("NA", "US", 0, 120, 0);
        double total = 250 + 60 + 100 * 0.75 + 300; // region + country + ping difference + b above a's max ping
        assertEquals(total, all.cost(a, b, 0), 1e-9);
        assertEquals(total / 2, all.cost(a, b, 15_000), 1e-9, "half way");
        assertEquals(0, all.cost(a, b, 30_000), 1e-9);
        assertEquals(0, all.cost(a, b, 600_000), 1e-9);
        // the longer waiter decides: a newcomer doesn't bring the penalties back
        assertEquals(0, all.cost(a, full("NA", "US", 29_000, 120, 0), 30_000), 1e-9);
    }

    @Test
    void closerPingIsPreferred() {
        RegionPingPolicy ping = new RegionPingPolicy(false, 250, false, 60, true, 0.75, 300, 30);
        QueueEntry a = entry("a", 1000, null, false, 40);
        assertEquals(0, ping.cost(a, entry("same", 1000, null, false, 40), 0), 1e-9);
        assertEquals(45, ping.cost(a, entry("far", 1000, null, false, 100), 0), 1e-9, "60 ms apart × 0.75");
    }

    @Test
    void sameCountryWinsSmallRatingDifferencesButNotBigOnes() {
        Matchmaker mm = new Matchmaker(new Matchmaker.Window(1000, 0, 1000), COUNTRY);
        QueueEntry de = entry("de", 1000, "DE", true, 30);
        QueueEntry fr = entry("fr", 1010, "FR", true, 30);
        // a compatriot 40 points away beats a foreigner 10 points away (40 < 10 + 60)
        QueueEntry near = entry("near", 1040, "DE", true, 30);
        assertEquals("near", mm.pair(List.of(de, fr, near), 1_000).getFirst().b().name());
        // only a nudge: a foreigner 10 points away beats a compatriot 100 points away (10 + 60 < 100)
        QueueEntry far = entry("far", 1100, "DE", true, 30);
        assertEquals("fr", mm.pair(List.of(de, fr, far), 1_000).getFirst().b().name());
        // after the relax time the closest rating wins
        assertEquals("fr", mm.pair(List.of(de, fr, near), 30_000).getFirst().b().name());
    }

    @Test
    void neverKeepsAPairApart() {
        // the penalty only picks between opponents: with just a foreigner there, the match is made at once
        Matchmaker mm = new Matchmaker(new Matchmaker.Window(50, 10, 500), COUNTRY);
        assertEquals(1, mm.pair(List.of(entry("de", 1000, "DE", true, 30), entry("fr", 1000, "FR", true, 30)), 0).size());
    }
}
