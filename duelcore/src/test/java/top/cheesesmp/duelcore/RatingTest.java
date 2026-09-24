package top.cheesesmp.duelcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.rating.EloRating;
import top.cheesesmp.duelcore.rating.Glicko2Rating;
import top.cheesesmp.duelcore.rating.RatingSystem;
import top.cheesesmp.duelcore.rating.Tier;
import top.cheesesmp.duelcore.rating.TierLadder;

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
        assertEquals(Tier.HT1, ladder.overallTier(400));
        assertEquals(Tier.MT1, ladder.overallTier(399));
        assertEquals(Tier.MT5, ladder.overallTier(1));
        assertEquals(Tier.LT5, ladder.overallTier(0));
        assertEquals(60, ladder.points(Tier.HT1));
        assertEquals(0, ladder.points(null));
        assertEquals(3, Tier.HT3.level());
        assertEquals("MT", Tier.MT4.band());
    }
}
