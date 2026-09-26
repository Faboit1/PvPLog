package top.cheesesmp.duelcore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.cheesesmp.duelcore.db.Database;
import top.cheesesmp.duelcore.db.Migrations;
import top.cheesesmp.duelcore.db.dao.LeaderboardDao;
import top.cheesesmp.duelcore.db.dao.PlayerDao;
import top.cheesesmp.duelcore.db.dao.RatingDao;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.rating.Tier;

/** Test bots (names starting with dcbot, any case) never show on a board and are not counted in ranks. */
class LeaderboardDaoTest {

    @TempDir
    Path dir;

    @Test
    void botsAreLeftOutOfBoardsAndRanks() throws Exception {
        Database db = Database.sqlite(new File(dir.toFile(), "l.db"), Logger.getLogger("test"), () -> false);
        try {
            db.submit(c -> Migrations.migrate(c, db.dialect())).join();
            String[] names = {"Alpha", "dcbot_a", "DCBot2", "Bravo", "notdcbot"};
            double[] ratings = {900, 2000, 1500, 800, 700};
            db.submit(c -> {
                for (int i = 0; i < names.length; i++) {
                    int id = PlayerDao.loadOrCreate(c, UUID.randomUUID(), names[i], 1L, 0).id();
                    KitStats s = new KitStats(ratings[i], 350, 0.06);
                    s.games = 10;
                    RatingDao.upsert(c, db.dialect(), 1, id, 1, s);
                    RatingDao.upsertStanding(c, db.dialect(), 1, id, (int) ratings[i], Tier.LT5);
                }
                return null;
            }).join();

            List<LeaderboardDao.Row> kit = db.submit(c -> LeaderboardDao.kit(c, 1, 1, 5, null, null, 10)).join();
            assertEquals(List.of("Alpha", "Bravo", "notdcbot"), kit.stream().map(LeaderboardDao.Row::name).toList());
            assertEquals(List.of(1, 2, 3), kit.stream().map(LeaderboardDao.Row::rank).toList());

            List<LeaderboardDao.Row> overall = db.submit(c -> LeaderboardDao.overall(c, 1, null, null, 10)).join();
            assertEquals(List.of("Alpha", "Bravo", "notdcbot"), overall.stream().map(LeaderboardDao.Row::name).toList());

            assertEquals(1, (int) db.submit(c -> LeaderboardDao.kitRank(c, 1, 1, 5, 900)).join());
            assertEquals(2, (int) db.submit(c -> LeaderboardDao.overallRank(c, 1, 800)).join());
            assertEquals(0, db.failures());
        } finally {
            db.close();
        }
    }
}
