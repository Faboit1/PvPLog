package top.cheesesmp.duelcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.rating.EloRating;
import top.cheesesmp.duelcore.rating.Glicko2Rating;
import top.cheesesmp.duelcore.rating.RatingSystem;
import top.cheesesmp.duelcore.rating.Tier;
import top.cheesesmp.duelcore.rating.TierLadder;
import top.cheesesmp.duelcore.rating.TierService;

class RatingTest {

    @Test
    void eloEqualPlayersSplitK() {
        EloRating elo = new EloRating(32, 48, 5, 0);
        KitStats a = new KitStats(1000, 350, 0.06);
        KitStats b = new KitStats(1000, 350, 0.06);
        a.games = 10;
        b.games = 10;
        RatingSystem.Result r = elo.rate(a, b, 1);
        assertEquals(1016, r.a().rating(), 1e-9);
        assertEquals(984, r.b().rating(), 1e-9);
    }

    @Test
    void eloProvisionalBoostOnlyForNewPlayer() {
        EloRating elo = new EloRating(32, 48, 5, 0);
        KitStats rookie = new KitStats(1000, 350, 0.06);
        KitStats vet = new KitStats(1000, 350, 0.06);
        vet.games = 50;
        RatingSystem.Result r = elo.rate(rookie, vet, 1);
        assertEquals(1024, r.a().rating(), 1e-9);
        assertEquals(984, r.b().rating(), 1e-9);
    }

    @Test
    void glickoMatchesPaperExample() throws Exception {
        // Glickman's Glicko-2 paper example: 1500/200/0.06 vs 1400/30 (W), 1550/100 (L), 1700/300 (L), tau 0.5
        Glicko2Rating g = new Glicko2Rating(0.5, 1500, 30, 350, 0);
        Method period = Glicko2Rating.class.getDeclaredMethod("period", double.class, double.class, double.class,
            double[].class, double[].class, double[].class);
        period.setAccessible(true);
        double[] r = (double[]) period.invoke(g, 1500, 200, 0.06,
            new double[] {1400, 1550, 1700}, new double[] {30, 100, 300}, new double[] {1, 0, 0});
        assertEquals(1464.06, r[0], 0.01);
        assertEquals(151.52, r[1], 0.01);
        assertEquals(0.05999, r[2], 0.00001);
    }

    @Test
    void glickoWinnerGainsLoserLoses() {
        Glicko2Rating g = new Glicko2Rating(0.5, 1000, 30, 350, 0);
        KitStats a = new KitStats(1000, 350, 0.06);
        KitStats b = new KitStats(1000, 350, 0.06);
        RatingSystem.Result r = g.rate(a, b, 1);
        assertTrue(r.a().rating() > 1000);
        assertTrue(r.b().rating() < 1000);
        assertEquals(r.a().rating() - 1000, 1000 - r.b().rating(), 1e-6);
        assertTrue(r.a().rd() < 350);
    }

    @Test
    void tierLadderDefaults() {
        TierLadder ladder = TierLadder.defaults();
        assertEquals(Tier.LT5, ladder.kitTier("sword", 1000));
        assertEquals(Tier.MT5, ladder.kitTier("sword", 1050));
        assertEquals(Tier.HT1, ladder.kitTier("sword", 2400));
        assertEquals(3, Tier.HT3.level());
        assertEquals("MT", Tier.MT4.band());
    }

    @Test
    void overallEloAveragesPlacedKits() {
        TierService tiers = new TierService(TierLadder.defaults(), "???", java.util.Map.of(), "<tier>");
        java.util.Map<String, KitStats> stats = new java.util.HashMap<>();
        PlayerProfile p = new PlayerProfile(1, java.util.UUID.randomUUID(), "p", 0, null, null, 0, stats);
        tiers.refresh(p);
        assertEquals(0, p.elo());
        assertNull(p.overall());
        KitStats sword = new KitStats(1500, 350, 0.06);
        sword.games = 7;
        KitStats axe = new KitStats(1300, 350, 0.06);
        axe.games = 5;
        KitStats mace = new KitStats(2400, 350, 0.06);
        mace.games = 2; // still in placement: ignored
        stats.put("sword", sword);
        stats.put("axe", axe);
        stats.put("mace", mace);
        p = new PlayerProfile(1, p.uuid(), "p", 0, null, null, 0, stats); // the profile copies the map
        tiers.refresh(p);
        assertEquals(1400, p.elo());
        assertEquals(Tier.LT3, p.overall()); // 1350 <= 1400 < 1410
    }
}
