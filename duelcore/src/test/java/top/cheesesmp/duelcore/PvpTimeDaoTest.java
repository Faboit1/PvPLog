package top.cheesesmp.duelcore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.cheesesmp.duelcore.db.Database;
import top.cheesesmp.duelcore.db.Migrations;
import top.cheesesmp.duelcore.db.dao.MatchDao;
import top.cheesesmp.duelcore.db.dao.MetaDao;
import top.cheesesmp.duelcore.db.dao.PlayerDao;

/** The all-time PvP time is summed from the existing match history (so old matches count without a backfill). */
class PvpTimeDaoTest {

    @TempDir
    Path dir;

    private static MatchDao.ParticipantRecord part(int player, int team) {
        return new MatchDao.ParticipantRecord(player, team, 0, 0, 0f, 0f, 1000f, 1000f);
    }

    @Test
    void sumsMatchDurationsOfEveryMatchPlayed() throws Exception {
        Database db = Database.sqlite(new File(dir.toFile(), "t.db"), Logger.getLogger("test"), () -> false);
        try {
            db.submit(c -> Migrations.migrate(c, db.dialect())).join();
            int a = db.submit(c -> PlayerDao.loadOrCreate(c, UUID.randomUUID(), "Alpha", 1L, 0)).join().id();
            int b = db.submit(c -> PlayerDao.loadOrCreate(c, UUID.randomUUID(), "Bravo", 1L, 0)).join().id();
            int cc = db.submit(c -> PlayerDao.loadOrCreate(c, UUID.randomUUID(), "Charlie", 1L, 0)).join().id();
            int d = db.submit(c -> PlayerDao.loadOrCreate(c, UUID.randomUUID(), "Delta", 1L, 0)).join().id();
            Map<String, Integer> kits = db.submit(c -> MetaDao.syncKits(c, List.of("sword", "axe"))).join();
            int s1 = db.submit(c -> MetaDao.currentSeason(c, "S1", 1L)).join().id();

            assertEquals(0L, db.submit(c -> MatchDao.pvpTimeMs(c, a)).join());

            // 1v1 in season 1
            db.transaction(c -> MatchDao.insert(c, new MatchDao.MatchRecord(s1, kits.get("sword"), true, "box", 10L,
                90_000, 0, 0, 3, "0", List.of(part(a, 0), part(b, 1))))).join();
            // new season: still all-time
            int s2 = db.transaction(c -> MetaDao.startNewSeason(c, "S2", 50L)).join().id();
            db.transaction(c -> MatchDao.insert(c, new MatchDao.MatchRecord(s2, kits.get("axe"), false, "box", 60L,
                45_500, 0, 1, 1, "1", List.of(part(a, 0), part(cc, 1))))).join();
            // party FFA: every participant gets the whole duration
            List<MatchDao.ParticipantRecord> ffa = new ArrayList<>(List.of(part(a, 0), part(b, 1), part(cc, 2)));
            db.transaction(c -> MatchDao.insert(c, new MatchDao.MatchRecord(s2, kits.get("sword"), false, "box", 70L,
                300_000, 0, 2, 1, "2", ffa))).join();
            // a match that never started fighting stores 0
            db.transaction(c -> MatchDao.insert(c, new MatchDao.MatchRecord(s2, kits.get("sword"), false, "box", 80L,
                0, 0, -1, 1, "", List.of(part(a, 0), part(b, 1))))).join();

            assertEquals(90_000L + 45_500 + 300_000, db.submit(c -> MatchDao.pvpTimeMs(c, a)).join());
            assertEquals(90_000L + 300_000, db.submit(c -> MatchDao.pvpTimeMs(c, b)).join());
            assertEquals(45_500L + 300_000, db.submit(c -> MatchDao.pvpTimeMs(c, cc)).join());
            assertEquals(0L, db.submit(c -> MatchDao.pvpTimeMs(c, d)).join());

            // large totals do not overflow an int (sum is read as a long)
            List<MatchDao.ParticipantRecord> both = List.of(part(d, 0), part(b, 1));
            for (int i = 0; i < 3; i++) {
                db.transaction(c -> MatchDao.insert(c, new MatchDao.MatchRecord(s2, kits.get("sword"), false, "box",
                    90L, Integer.MAX_VALUE, 0, 0, 1, "0", both))).join();
            }
            assertEquals(3L * Integer.MAX_VALUE, db.submit(c -> MatchDao.pvpTimeMs(c, d)).join());

            assertEquals(0, db.mainThreadExecutions());
            assertEquals(0, db.failures());
        } finally {
            db.close();
        }
    }
}
