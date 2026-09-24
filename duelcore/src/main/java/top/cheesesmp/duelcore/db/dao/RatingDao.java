package top.cheesesmp.duelcore.db.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.db.Dialect;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.rating.Tier;

public final class RatingDao {

    private RatingDao() {
    }

    /** kit id → stats for one player in one season. */
    public static Map<Integer, KitStats> load(Connection c, int seasonId, int playerId) throws SQLException {
        Map<Integer, KitStats> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT kit_id, rating, rd, vol, games, wins, losses, streak, best_streak, peak, tier_override, updated_at "
                + "FROM dc_ratings WHERE season_id = ? AND player_id = ?")) {
            ps.setInt(1, seasonId);
            ps.setInt(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getInt(1), read(rs, 2));
            }
        }
        return out;
    }

    /** Streams every rating row of a season (used by standings recalculation). */
    public static void forEach(Connection c, int seasonId, BiConsumer<int[], KitStats> consumer) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT player_id, kit_id, rating, rd, vol, games, wins, losses, streak, best_streak, peak, tier_override, updated_at "
                + "FROM dc_ratings WHERE season_id = ? ORDER BY player_id")) {
            ps.setInt(1, seasonId);
            ps.setFetchSize(500);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) consumer.accept(new int[] {rs.getInt(1), rs.getInt(2)}, read(rs, 3));
            }
        }
    }

    public static void upsert(Connection c, Dialect d, int seasonId, int playerId, int kitId, KitStats s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO dc_ratings (season_id, player_id, kit_id, rating, rd, vol, games, wins, losses, streak, best_streak, "
                + "peak, tier_override, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + d.upsert("season_id, player_id, kit_id", "rating", "rd", "vol", "games", "wins", "losses", "streak",
                "best_streak", "peak", "tier_override", "updated_at"))) {
            ps.setInt(1, seasonId);
            ps.setInt(2, playerId);
            ps.setInt(3, kitId);
            ps.setDouble(4, s.rating);
            ps.setDouble(5, s.rd);
            ps.setDouble(6, s.volatility);
            ps.setInt(7, s.games);
            ps.setInt(8, s.wins);
            ps.setInt(9, s.losses);
            ps.setInt(10, s.streak);
            ps.setInt(11, s.bestStreak);
            ps.setDouble(12, s.peak);
            if (s.tierOverride == null) ps.setNull(13, Types.TINYINT);
            else ps.setInt(13, s.tierOverride.id());
            ps.setLong(14, s.updatedAt);
            ps.executeUpdate();
        }
    }

    public static void upsertStanding(Connection c, Dialect d, int seasonId, int playerId, int points, @Nullable Tier overall)
        throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO dc_standings (season_id, player_id, points, overall_tier) VALUES (?, ?, ?, ?)"
                + d.upsert("season_id, player_id", "points", "overall_tier"))) {
            ps.setInt(1, seasonId);
            ps.setInt(2, playerId);
            ps.setInt(3, points);
            if (overall == null) ps.setNull(4, Types.TINYINT);
            else ps.setInt(4, overall.id());
            ps.executeUpdate();
        }
    }

    private static KitStats read(ResultSet rs, int col) throws SQLException {
        KitStats s = new KitStats(rs.getDouble(col), rs.getDouble(col + 1), rs.getDouble(col + 2));
        s.games = rs.getInt(col + 3);
        s.wins = rs.getInt(col + 4);
        s.losses = rs.getInt(col + 5);
        s.streak = rs.getInt(col + 6);
        s.bestStreak = rs.getInt(col + 7);
        s.peak = rs.getDouble(col + 8);
        int override = rs.getInt(col + 9);
        s.tierOverride = rs.wasNull() ? null : Tier.byId(override);
        s.updatedAt = rs.getLong(col + 10);
        return s;
    }
}
