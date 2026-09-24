package top.cheesesmp.duelcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.cheesesmp.duelcore.db.Database;
import top.cheesesmp.duelcore.db.Migrations;
import top.cheesesmp.duelcore.db.dao.LeaderboardDao;
import top.cheesesmp.duelcore.db.dao.MatchDao;
import top.cheesesmp.duelcore.db.dao.MetaDao;
import top.cheesesmp.duelcore.db.dao.PlayerDao;
import top.cheesesmp.duelcore.db.dao.RatingDao;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.rating.Tier;

class DatabaseTest {

    @TempDir
    Path dir;

    @Test
    void fullRoundTrip() throws Exception {
        Database db = Database.sqlite(new File(dir.toFile(), "t.db"), Logger.getLogger("test"), () -> false);
        try {
            int version = db.submit(c -> Migrations.migrate(c, db.dialect())).join();
            assertEquals(Migrations.LATEST, version);
            // idempotent
            assertEquals(Migrations.LATEST, db.submit(c -> Migrations.migrate(c, db.dialect())).join());

            Map<String, Integer> kits = db.submit(c -> MetaDao.syncKits(c, List.of("sword", "mace"))).join();
            Map<String, Integer> kits2 = db.submit(c -> MetaDao.syncKits(c, List.of("mace", "sword", "pot"))).join();
            assertEquals(kits.get("sword"), kits2.get("sword"));
            assertEquals(3, kits2.size());

            MetaDao.Season s1 = db.submit(c -> MetaDao.currentSeason(c, "Season 1", 1L)).join();
            assertEquals(1, s1.id());

            UUID ua = UUID.randomUUID();
            UUID ub = UUID.randomUUID();
            PlayerDao.PlayerRow a = db.submit(c -> PlayerDao.loadOrCreate(c, ua, "Alpha", 10L, 7)).join();
            PlayerDao.PlayerRow b = db.submit(c -> PlayerDao.loadOrCreate(c, ub, "Bravo", 10L, 7)).join();
            PlayerDao.PlayerRow a2 = db.submit(c -> PlayerDao.loadOrCreate(c, ua, "AlphaRenamed", 20L, 7)).join();
            assertEquals(a.id(), a2.id());
            assertEquals(7, a2.settings());
            assertEquals("AlphaRenamed", db.submit(c -> PlayerDao.findByName(c, "alpharenamed")).join().orElseThrow().name());
            db.submit(c -> { PlayerDao.saveSettings(c, b.id(), 3, "EU", "NL", 80); return null; }).join();

            int sword = kits2.get("sword");
            KitStats sa = new KitStats(1016, 350, 0.06);
            sa.games = 6; sa.wins = 5; sa.losses = 1; sa.streak = 3; sa.bestStreak = 4; sa.peak = 1030; sa.updatedAt = 99;
            KitStats sb = new KitStats(984, 350, 0.06);
            sb.games = 6; sb.wins = 1; sb.losses = 5; sb.tierOverride = Tier.HT3;
            db.transaction(c -> {
                RatingDao.upsert(c, db.dialect(), s1.id(), a.id(), sword, sa);
                RatingDao.upsert(c, db.dialect(), s1.id(), b.id(), sword, sb);
                RatingDao.upsertStanding(c, db.dialect(), s1.id(), a.id(), 1, Tier.MT5);
                RatingDao.upsertStanding(c, db.dialect(), s1.id(), b.id(), 22, Tier.LT4);
                return null;
            }).join();
            sa.rating = 1040;
            db.submit(c -> { RatingDao.upsert(c, db.dialect(), s1.id(), a.id(), sword, sa); return null; }).join();
            KitStats loaded = db.submit(c -> RatingDao.load(c, s1.id(), a.id())).join().get(sword);
            assertEquals(1040, loaded.rating, 1e-9);
            assertEquals(4, loaded.bestStreak);
            assertEquals(Tier.HT3, db.submit(c -> RatingDao.load(c, s1.id(), b.id())).join().get(sword).tierOverride);

            long matchId = db.transaction(c -> MatchDao.insert(c, new MatchDao.MatchRecord(s1.id(), sword, true, "box", 1000L,
                60_000, 0, 0, 3, "0010", List.of(
                    new MatchDao.ParticipantRecord(a.id(), 0, 3, 40, 80.5f, 60f, 1016f, 1040f),
                    new MatchDao.ParticipantRecord(b.id(), 1, 1, 30, 60f, 80.5f, 984f, 960f))))).join();
            assertTrue(matchId > 0);
            List<MatchDao.HistoryEntry> hist = db.submit(c -> MatchDao.history(c, a.id(), 5, Map.of(sword, "sword"))).join();
            assertEquals(1, hist.size());
            assertTrue(hist.get(0).won());
            assertEquals("Bravo", hist.get(0).opponentName());
            assertEquals(24f, hist.get(0).ratingDelta(), 1e-3);
            assertEquals(3, hist.get(0).roundsWon());

            List<LeaderboardDao.Row> board = db.submit(c -> LeaderboardDao.kit(c, s1.id(), sword, 5, null, null, 10)).join();
            assertEquals(2, board.size());
            assertEquals("AlphaRenamed", board.get(0).name());
            List<LeaderboardDao.Row> eu = db.submit(c -> LeaderboardDao.kit(c, s1.id(), sword, 5, "EU", null, 10)).join();
            assertEquals(1, eu.size());
            assertEquals("Bravo", eu.get(0).name());
            List<LeaderboardDao.Row> overall = db.submit(c -> LeaderboardDao.overall(c, s1.id(), null, "NL", 10)).join();
            assertEquals(1, overall.size());
            assertEquals(22, overall.get(0).value(), 1e-9);
            assertEquals(1, overall.get(0).wins());
            assertEquals(2, db.submit(c -> LeaderboardDao.kitRank(c, s1.id(), sword, 5, 984)).join());
            assertEquals(1, db.submit(c -> LeaderboardDao.overallRank(c, s1.id(), 22)).join());

            MetaDao.Season s2 = db.submit(c -> MetaDao.startNewSeason(c, "Season 2", 5000L)).join();
            assertNotEquals(s1.id(), s2.id());
            assertEquals(s2.id(), db.submit(c -> MetaDao.currentSeason(c, "x", 6000L)).join().id());
            assertTrue(db.submit(c -> RatingDao.load(c, s2.id(), a.id())).join().isEmpty());
            assertEquals(1040, db.submit(c -> RatingDao.load(c, s1.id(), a.id())).join().get(sword).rating, 1e-9);
            assertTrue(db.submit(c -> MetaDao.previousSeason(c, s2.id())).join().legacy());
            assertEquals(0, db.mainThreadExecutions());
            assertEquals(0, db.failures());
        } finally {
            db.close();
        }
    }
}
