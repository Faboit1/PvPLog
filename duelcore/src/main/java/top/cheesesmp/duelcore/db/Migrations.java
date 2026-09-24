package top.cheesesmp.duelcore.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Versioned schema. Append new versions; never edit a released one. */
public final class Migrations {

    public static final int LATEST = 3;

    private Migrations() {
    }

    public static int migrate(Connection c, Dialect d) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS dc_meta (k VARCHAR(32) NOT NULL PRIMARY KEY, v VARCHAR(255) NOT NULL)" + d.tableSuffix());
        }
        int current = currentVersion(c);
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            for (int v = current + 1; v <= LATEST; v++) {
                try (Statement st = c.createStatement()) {
                    for (String sql : statements(v, d)) st.executeUpdate(sql);
                }
                setVersion(c, d, v);
            }
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
        return currentVersion(c);
    }

    /** Schema version before migrating (0 on a fresh database). */
    public static int installedVersion(Connection c) {
        try {
            return currentVersion(c);
        } catch (SQLException e) {
            return 0; // no dc_meta table yet
        }
    }

    private static int currentVersion(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT v FROM dc_meta WHERE k = 'schema_version'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
        }
    }

    private static void setVersion(Connection c, Dialect d, int v) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO dc_meta (k, v) VALUES ('schema_version', ?)" + d.upsert("k", "v"))) {
            ps.setString(1, Integer.toString(v));
            ps.executeUpdate();
        }
    }

    private static List<String> statements(int version, Dialect d) {
        List<String> s = new ArrayList<>();
        String uuid = d.uuidType();
        switch (version) {
            case 1 -> {
                s.add("CREATE TABLE IF NOT EXISTS dc_players ("
                    + d.autoIncrementPk("id", false) + ", "
                    + "uuid " + uuid + " NOT NULL UNIQUE, "
                    + "name VARCHAR(16) NOT NULL, "
                    + "name_lower VARCHAR(16) NOT NULL, "
                    + "region VARCHAR(8) NULL, "
                    + "country CHAR(2) NULL, "
                    + "settings INT NOT NULL DEFAULT 0, "
                    + "max_ping SMALLINT NOT NULL DEFAULT 0, "
                    + "first_seen BIGINT NOT NULL, "
                    + "last_seen BIGINT NOT NULL)" + d.tableSuffix());
                s.add("CREATE INDEX " + ifNotExists(d) + "dc_players_name ON dc_players (name_lower)");
                s.add("CREATE TABLE IF NOT EXISTS dc_kits ("
                    + "id SMALLINT NOT NULL PRIMARY KEY, "
                    + "kit_key VARCHAR(32) NOT NULL UNIQUE)" + d.tableSuffix());
                s.add("CREATE TABLE IF NOT EXISTS dc_seasons ("
                    + "id SMALLINT NOT NULL PRIMARY KEY, "
                    + "name VARCHAR(32) NOT NULL, "
                    + "started_at BIGINT NOT NULL, "
                    + "ended_at BIGINT NULL, "
                    + "legacy TINYINT NOT NULL DEFAULT 0)" + d.tableSuffix());
                s.add("CREATE TABLE IF NOT EXISTS dc_ratings ("
                    + "season_id SMALLINT NOT NULL, "
                    + "player_id INT NOT NULL, "
                    + "kit_id SMALLINT NOT NULL, "
                    + "rating DOUBLE NOT NULL, "
                    + "rd DOUBLE NOT NULL, "
                    + "vol DOUBLE NOT NULL, "
                    + "games INT NOT NULL DEFAULT 0, "
                    + "wins INT NOT NULL DEFAULT 0, "
                    + "losses INT NOT NULL DEFAULT 0, "
                    + "streak SMALLINT NOT NULL DEFAULT 0, "
                    + "best_streak SMALLINT NOT NULL DEFAULT 0, "
                    + "peak DOUBLE NOT NULL, "
                    + "tier_override TINYINT NULL, "
                    + "updated_at BIGINT NOT NULL, "
                    + "PRIMARY KEY (season_id, player_id, kit_id))" + d.clustered());
                s.add("CREATE INDEX " + ifNotExists(d) + "dc_ratings_board ON dc_ratings (season_id, kit_id, rating)");
                s.add("CREATE TABLE IF NOT EXISTS dc_standings ("
                    + "season_id SMALLINT NOT NULL, "
                    + "player_id INT NOT NULL, "
                    + "points SMALLINT NOT NULL, "
                    + "overall_tier TINYINT NULL, "
                    + "PRIMARY KEY (season_id, player_id))" + d.clustered());
                s.add("CREATE INDEX " + ifNotExists(d) + "dc_standings_board ON dc_standings (season_id, points)");
                s.add("CREATE TABLE IF NOT EXISTS dc_matches ("
                    + d.autoIncrementPk("id", true) + ", "
                    + "season_id SMALLINT NOT NULL, "
                    + "kit_id SMALLINT NOT NULL, "
                    + "ranked TINYINT NOT NULL, "
                    + "arena VARCHAR(32) NOT NULL, "
                    + "started_at BIGINT NOT NULL, "
                    + "duration_ms INT NOT NULL, "
                    + "end_reason TINYINT NOT NULL, "
                    + "winner_team TINYINT NOT NULL, "
                    + "first_to TINYINT NOT NULL, "
                    + "rounds VARCHAR(64) NOT NULL)" + d.tableSuffix());
                s.add("CREATE TABLE IF NOT EXISTS dc_match_players ("
                    + "match_id BIGINT NOT NULL, "
                    + "player_id INT NOT NULL, "
                    + "team TINYINT NOT NULL, "
                    + "rounds_won TINYINT NOT NULL, "
                    + "hits INT NOT NULL, "
                    + "damage_dealt FLOAT NOT NULL, "
                    + "damage_taken FLOAT NOT NULL, "
                    + "rating_before FLOAT NOT NULL, "
                    + "rating_after FLOAT NOT NULL, "
                    + "PRIMARY KEY (match_id, player_id))" + d.clustered());
                s.add("CREATE INDEX " + ifNotExists(d) + "dc_match_players_history ON dc_match_players (player_id, match_id)");
            }
            case 2 -> {
                // Phase 2: tournaments log and parties do not need tables beyond this results log.
                s.add("CREATE TABLE IF NOT EXISTS dc_tournaments ("
                    + d.autoIncrementPk("id", false) + ", "
                    + "name VARCHAR(48) NOT NULL, "
                    + "kit_id SMALLINT NOT NULL, "
                    + "created_at BIGINT NOT NULL, "
                    + "finished_at BIGINT NULL, "
                    + "winner_id INT NULL, "
                    + "bracket TEXT NULL)" + d.tableSuffix());
            }
            case 3 -> {
                // the overall standing is the overall Elo now (tier points were removed)
                s.add(d == Dialect.SQLITE
                    ? "ALTER TABLE dc_standings RENAME COLUMN points TO elo"
                    : "ALTER TABLE dc_standings CHANGE points elo SMALLINT NOT NULL");
            }
            default -> throw new IllegalStateException("unknown schema version " + version);
        }
        return s;
    }

    private static String ifNotExists(Dialect d) {
        // MySQL has no CREATE INDEX IF NOT EXISTS; migrations only run once per version, so it is not needed there.
        return d == Dialect.SQLITE ? "IF NOT EXISTS " : "";
    }
}
