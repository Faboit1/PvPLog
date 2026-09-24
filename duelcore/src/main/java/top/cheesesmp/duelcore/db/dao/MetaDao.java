package top.cheesesmp.duelcore.db.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.db.Dialect;

/** Kit id mapping and seasons. */
public final class MetaDao {

    public record Season(int id, String name, long startedAt, @Nullable Long endedAt, boolean legacy) {
    }

    private MetaDao() {
    }

    /** Ensures every kit key has a stable small id; returns the full key → id map. */
    public static Map<String, Integer> syncKits(Connection c, Collection<String> keys) throws SQLException {
        Map<String, Integer> map = new HashMap<>();
        int max = 0;
        try (PreparedStatement ps = c.prepareStatement("SELECT id, kit_key FROM dc_kits");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.put(rs.getString(2), rs.getInt(1));
                max = Math.max(max, rs.getInt(1));
            }
        }
        for (String key : keys) {
            if (map.containsKey(key)) continue;
            int id = ++max;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_kits (id, kit_key) VALUES (?, ?)")) {
                ps.setInt(1, id);
                ps.setString(2, key);
                ps.executeUpdate();
            }
            map.put(key, id);
        }
        return map;
    }

    /** Current (non-ended) season, creating season 1 on a fresh database. */
    public static Season currentSeason(Connection c, String defaultName, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, name, started_at, ended_at, legacy FROM dc_seasons WHERE ended_at IS NULL ORDER BY id DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return new Season(rs.getInt(1), rs.getString(2), rs.getLong(3), null, rs.getInt(5) != 0);
        }
        return createSeason(c, nextSeasonId(c), defaultName, now);
    }

    public static Season startNewSeason(Connection c, String name, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE dc_seasons SET ended_at = ?, legacy = 1 WHERE ended_at IS NULL")) {
            ps.setLong(1, now);
            ps.executeUpdate();
        }
        return createSeason(c, nextSeasonId(c), name, now);
    }

    public static @Nullable Season previousSeason(Connection c, int currentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, name, started_at, ended_at, legacy FROM dc_seasons WHERE id < ? ORDER BY id DESC LIMIT 1")) {
            ps.setInt(1, currentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long ended = rs.getLong(4);
                return new Season(rs.getInt(1), rs.getString(2), rs.getLong(3), rs.wasNull() ? null : ended, rs.getInt(5) != 0);
            }
        }
    }

    private static int nextSeasonId(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(id), 0) FROM dc_seasons");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1) + 1;
        }
    }

    private static Season createSeason(Connection c, int id, String name, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO dc_seasons (id, name, started_at, ended_at, legacy) VALUES (?, ?, ?, NULL, 0)")) {
            ps.setInt(1, id);
            ps.setString(2, name);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
        return new Season(id, name, now, null, false);
    }

    public static String get(Connection c, String key, String def) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT v FROM dc_meta WHERE k = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : def;
            }
        }
    }

    public static void set(Connection c, Dialect d, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_meta (k, v) VALUES (?, ?)" + d.upsert("k", "v"))) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }
}
