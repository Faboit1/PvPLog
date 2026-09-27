package top.cheesesmp.duelcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;
import top.cheesesmp.duelcore.rating.Tier;
import top.cheesesmp.duelcore.rating.TierLadder;
import top.cheesesmp.duelcore.rating.TierService;

/**
 * Test bots (names starting with dcbot, any case) never show on a board and are not counted in ranks; a country
 * board ranks its own players; the overall board ranks by points (the Elo of every placed kit added up).
 */
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
                    RatingDao.upsertStanding(c, db.dialect(), 1, id, (int) ratings[i], (int) ratings[i], Tier.LT5);
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

    /** A country board lists that country's players only and ranks them among themselves (the "You · #2" line). */
    @Test
    void countryBoardsRankWithinTheCountry() throws Exception {
        Database db = Database.sqlite(new File(dir.toFile(), "c.db"), Logger.getLogger("test"), () -> false);
        try {
            db.submit(c -> Migrations.migrate(c, db.dialect())).join();
            String[] names = {"Anna", "Bert", "Carl", "Dora"};
            String[] countries = {"DE", "FR", "DE", null};
            double[] ratings = {900, 1200, 700, 1500};
            db.submit(c -> {
                for (int i = 0; i < names.length; i++) {
                    int id = PlayerDao.loadOrCreate(c, UUID.randomUUID(), names[i], 1L, 0).id();
                    PlayerDao.saveSettings(c, id, 0, "EU", countries[i], 0);
                    KitStats s = new KitStats(ratings[i], 350, 0.06);
                    s.games = 10;
                    RatingDao.upsert(c, db.dialect(), 1, id, 1, s);
                    RatingDao.upsertStanding(c, db.dialect(), 1, id, (int) ratings[i], (int) ratings[i], Tier.LT5);
                }
                return null;
            }).join();

            List<LeaderboardDao.Row> kit = db.submit(c -> LeaderboardDao.kit(c, 1, 1, 5, null, "DE", 10)).join();
            assertEquals(List.of("Anna", "Carl"), kit.stream().map(LeaderboardDao.Row::name).toList());
            assertEquals(List.of(1, 2), kit.stream().map(LeaderboardDao.Row::rank).toList());
            List<LeaderboardDao.Row> overall = db.submit(c -> LeaderboardDao.overall(c, 1, null, "DE", 10)).join();
            assertEquals(List.of("Anna", "Carl"), overall.stream().map(LeaderboardDao.Row::name).toList());
            assertEquals(2, overall.get(1).rank());
            assertEquals(List.of("Bert"), db.submit(c -> LeaderboardDao.kit(c, 1, 1, 5, null, "FR", 10)).join().stream()
                .map(LeaderboardDao.Row::name).toList());
            assertEquals(4, db.submit(c -> LeaderboardDao.kit(c, 1, 1, 5, null, null, 10)).join().size());
            assertEquals(0, db.failures());
        } finally {
            db.close();
        }
    }

    /**
     * Players with "Show my flag" off aren't on their country's board (it would give their country away), but stay on
     * the global and region boards; rows carry the settings bits so the dialog can hide their flag there too.
     */
    @Test
    void hiddenFlagsStayOffCountryBoards() throws Exception {
        Database db = Database.sqlite(new File(dir.toFile(), "h.db"), Logger.getLogger("test"), () -> false);
        try {
            db.submit(c -> Migrations.migrate(c, db.dialect())).join();
            int hidden = Setting.SHOW_MY_FLAG.write(Setting.defaults(), false);
            String[] names = {"Open", "Private"};
            int[] settings = {Setting.defaults(), hidden};
            db.submit(c -> {
                for (int i = 0; i < names.length; i++) {
                    int id = PlayerDao.loadOrCreate(c, UUID.randomUUID(), names[i], 1L, 0).id();
                    PlayerDao.saveSettings(c, id, settings[i], "EU", "NL", 0);
                    KitStats s = new KitStats(1000 - i * 100, 350, 0.06);
                    s.games = 10;
                    RatingDao.upsert(c, db.dialect(), 1, id, 1, s);
                    RatingDao.upsertStanding(c, db.dialect(), 1, id, 1000 - i * 100, 1000 - i * 100, Tier.LT5);
                }
                return null;
            }).join();

            List<LeaderboardDao.Row> country = db.submit(c -> LeaderboardDao.kit(c, 1, 1, 5, null, "NL", 10)).join();
            assertEquals(List.of("Open"), country.stream().map(LeaderboardDao.Row::name).toList());
            assertEquals(List.of("Open"), db.submit(c -> LeaderboardDao.overall(c, 1, null, "NL", 10)).join().stream()
                .map(LeaderboardDao.Row::name).toList());
            List<LeaderboardDao.Row> global = db.submit(c -> LeaderboardDao.kit(c, 1, 1, 5, null, null, 10)).join();
            assertEquals(List.of("Open", "Private"), global.stream().map(LeaderboardDao.Row::name).toList());
            assertEquals(false, Setting.SHOW_MY_FLAG.read(global.get(1).settings()));
            assertEquals(true, Setting.SHOW_MY_FLAG.read(global.get(0).settings()));
            assertEquals(0, db.failures());
        } finally {
            db.close();
        }
    }

    private static KitStats kit(double rating, int games) {
        KitStats s = new KitStats(rating, 350, 0.06);
        s.games = games;
        return s;
    }

    /** Stores a player's kits and the standing TierService computes for them (as a finished match does). */
    private static PlayerProfile store(Connection c, Database db, TierService tiers, int season, String name,
                                       Map<Integer, KitStats> kits) throws SQLException {
        int id = PlayerDao.loadOrCreate(c, UUID.randomUUID(), name, 1L, 0).id();
        Map<String, KitStats> byKey = new HashMap<>();
        for (Map.Entry<Integer, KitStats> e : kits.entrySet()) {
            RatingDao.upsert(c, db.dialect(), season, id, e.getKey(), e.getValue());
            byKey.put("kit" + e.getKey(), e.getValue());
        }
        PlayerProfile p = new PlayerProfile(id, new UUID(0, 0), name, 0, null, null, 0, byKey);
        tiers.refresh(p);
        RatingDao.upsertStanding(c, db.dialect(), season, id, p.elo(), p.points(), p.overall());
        return p;
    }

    /**
     * Overall points = the sum of the placed kits' Elo (kits still in placement don't count); the overall board and
     * overall rank order by them, while the overall tier still comes from the average Elo.
     */
    @Test
    void overallBoardRanksByPointsSum() throws Exception {
        Database db = Database.sqlite(new File(dir.toFile(), "p.db"), Logger.getLogger("test"), () -> false);
        TierService tiers = new TierService(TierLadder.defaults(), "???", Map.of(), "<tier>");
        try {
            db.submit(c -> Migrations.migrate(c, db.dialect())).join();
            Map<String, PlayerProfile> stored = db.submit(c -> {
                Map<String, PlayerProfile> m = new HashMap<>();
                m.put("Solo", store(c, db, tiers, 1, "Solo", Map.of(1, kit(1500, 10))));
                m.put("Wide", store(c, db, tiers, 1, "Wide", Map.of(1, kit(1000.4, 10), 2, kit(1100.6, 6))));
                // the 2400 kit is still in placement (2 of 5 games): not counted
                m.put("Fresh", store(c, db, tiers, 1, "Fresh", Map.of(1, kit(1200, 5), 2, kit(2400, 2))));
                m.put("Unranked", store(c, db, tiers, 1, "Unranked", Map.of(1, kit(1900, 3))));
                m.put("dcbot_z", store(c, db, tiers, 1, "dcbot_z", Map.of(1, kit(1400, 9), 2, kit(1400, 9))));
                return m;
            }).join();

            assertEquals(1500, stored.get("Solo").points());
            assertEquals(2101, stored.get("Wide").points()); // 1000 + 1101
            assertEquals(1051, stored.get("Wide").elo()); // the average (1050.5) still sets the tier
            assertEquals(tiers.ladder().kitTier("overall", 1051), stored.get("Wide").overall());
            assertEquals(1200, stored.get("Fresh").points());
            assertEquals(0, stored.get("Unranked").points());
            assertNull(stored.get("Unranked").overall());

            List<LeaderboardDao.Row> overall = db.submit(c -> LeaderboardDao.overall(c, 1, null, null, 10)).join();
            assertEquals(List.of("Wide", "Solo", "Fresh"), overall.stream().map(LeaderboardDao.Row::name).toList());
            assertEquals(List.of(2101.0, 1500.0, 1200.0), overall.stream().map(LeaderboardDao.Row::value).toList());
            assertEquals(stored.get("Wide").overall(), overall.get(0).tier());
            assertEquals(1, (int) db.submit(c -> LeaderboardDao.overallRank(c, 1, 2101)).join());
            assertEquals(2, (int) db.submit(c -> LeaderboardDao.overallRank(c, 1, 1500)).join());
            assertEquals(3, (int) db.submit(c -> LeaderboardDao.overallRank(c, 1, 1300)).join());
            assertEquals(0, db.failures());
        } finally {
            db.close();
        }
    }

    /**
     * Schema v9 adds dc_standings.points to a database that only had the overall Elo; the one-time backfill fills them
     * in for every season from the stored ratings (placed kits only), matching TierService.overallPoints.
     */
    @Test
    void migrationBackfillsPointsOfEveryStanding() throws Exception {
        Database db = Database.sqlite(new File(dir.toFile(), "m.db"), Logger.getLogger("test"), () -> false);
        TierService tiers = new TierService(TierLadder.defaults(), "???", Map.of(), "<tier>");
        try {
            db.submit(c -> Migrations.migrate(c, db.dialect())).join();
            // back to a v8 database: dc_standings without points
            db.submit(c -> {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("DROP INDEX dc_standings_points");
                    st.executeUpdate("ALTER TABLE dc_standings DROP COLUMN points");
                    st.executeUpdate("UPDATE dc_meta SET v = '8' WHERE k = 'schema_version'");
                }
                return null;
            }).join();
            db.submit(c -> {
                int a = PlayerDao.loadOrCreate(c, UUID.randomUUID(), "Anna", 1L, 0).id();
                int b = PlayerDao.loadOrCreate(c, UUID.randomUUID(), "Bert", 1L, 0).id();
                RatingDao.upsert(c, db.dialect(), 1, a, 1, kit(1000.6, 10));
                RatingDao.upsert(c, db.dialect(), 1, a, 2, kit(999.4, 5));
                RatingDao.upsert(c, db.dialect(), 1, a, 3, kit(2000, 4)); // in placement
                RatingDao.upsert(c, db.dialect(), 1, b, 1, kit(1600, 20));
                RatingDao.upsert(c, db.dialect(), 2, a, 1, kit(1300, 8)); // another season
                RatingDao.upsert(c, db.dialect(), 2, b, 1, kit(1100, 1));
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("INSERT INTO dc_standings (season_id, player_id, elo, overall_tier) VALUES "
                        + "(1, " + a + ", 1000, 0), (1, " + b + ", 1600, 0), (2, " + a + ", 1300, 0), (2, " + b + ", 0, NULL)");
                }
                return null;
            }).join();

            assertEquals(Migrations.LATEST, (int) db.submit(c -> Migrations.migrate(c, db.dialect())).join());
            assertEquals(0, (int) db.submit(c -> points(c, 1, "Anna")).join()); // the new column starts at 0
            assertEquals(4, (int) db.submit(c -> RatingDao.backfillPoints(c, db.dialect(), tiers.placementMatches())).join());

            assertEquals(2000, (int) db.submit(c -> points(c, 1, "Anna")).join()); // 1001 + 999, the 2000 kit not placed
            assertEquals(1600, (int) db.submit(c -> points(c, 1, "Bert")).join());
            assertEquals(1300, (int) db.submit(c -> points(c, 2, "Anna")).join());
            assertEquals(0, (int) db.submit(c -> points(c, 2, "Bert")).join());
            // the same as the live computation
            Map<String, KitStats> anna = new HashMap<>(Map.of("a", kit(1000.6, 10), "b", kit(999.4, 5), "c", kit(2000, 4)));
            assertEquals(2000, tiers.overallPoints(new PlayerProfile(1, new UUID(0, 0), "Anna", 0, null, null, 0, anna)));

            List<LeaderboardDao.Row> overall = db.submit(c -> LeaderboardDao.overall(c, 1, null, null, 10)).join();
            assertEquals(List.of("Anna", "Bert"), overall.stream().map(LeaderboardDao.Row::name).toList());
            assertEquals(0, db.failures());
        } finally {
            db.close();
        }
    }

    private static int points(Connection c, int season, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT s.points FROM dc_standings s JOIN dc_players p "
            + "ON p.id = s.player_id WHERE s.season_id = ? AND p.name = ?")) {
            ps.setInt(1, season);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
