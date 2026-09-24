package top.cheesesmp.duelcore.db.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.db.Uuids;
import top.cheesesmp.duelcore.rating.Tier;

public final class LeaderboardDao {

    /**
     * One leaderboard line. For kit boards {@code value} is the rating and {@code tier} the pinned tier (if any);
     * for the overall board {@code value} is global points and {@code tier} the overall tier.
     */
    public record Row(int rank, UUID uuid, String name, double value, @Nullable Tier tier, int wins, int losses,
                      @Nullable String region, @Nullable String country) {
    }

    private LeaderboardDao() {
    }

    public static List<Row> kit(Connection c, int seasonId, int kitId, int placementGames, @Nullable String region,
                                @Nullable String country, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder(
            "SELECT p.uuid, p.name, r.rating, r.tier_override, r.wins, r.losses, p.region, p.country "
                + "FROM dc_ratings r JOIN dc_players p ON p.id = r.player_id "
                + "WHERE r.season_id = ? AND r.kit_id = ? AND (r.games >= ? OR r.tier_override IS NOT NULL)");
        if (region != null) sql.append(" AND p.region = ?");
        if (country != null) sql.append(" AND p.country = ?");
        sql.append(" ORDER BY r.rating DESC, r.wins DESC LIMIT ?");
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setInt(i++, seasonId);
            ps.setInt(i++, kitId);
            ps.setInt(i++, placementGames);
            if (region != null) ps.setString(i++, region);
            if (country != null) ps.setString(i++, country);
            ps.setInt(i, limit);
            return read(ps);
        }
    }

    public static List<Row> overall(Connection c, int seasonId, @Nullable String region, @Nullable String country, int limit)
        throws SQLException {
        StringBuilder sql = new StringBuilder(
            "SELECT p.uuid, p.name, s.points, s.overall_tier, "
                + "(SELECT COALESCE(SUM(r.wins), 0) FROM dc_ratings r WHERE r.season_id = s.season_id AND r.player_id = s.player_id), "
                + "(SELECT COALESCE(SUM(r.losses), 0) FROM dc_ratings r WHERE r.season_id = s.season_id AND r.player_id = s.player_id), "
                + "p.region, p.country "
                + "FROM dc_standings s JOIN dc_players p ON p.id = s.player_id "
                + "WHERE s.season_id = ? AND s.overall_tier IS NOT NULL");
        if (region != null) sql.append(" AND p.region = ?");
        if (country != null) sql.append(" AND p.country = ?");
        sql.append(" ORDER BY s.points DESC, p.name ASC LIMIT ?");
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setInt(i++, seasonId);
            if (region != null) ps.setString(i++, region);
            if (country != null) ps.setString(i++, country);
            ps.setInt(i, limit);
            return read(ps);
        }
    }

    /** 1-based rank of a rating on a kit board (ties share the better rank). */
    public static int kitRank(Connection c, int seasonId, int kitId, int placementGames, double rating) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT COUNT(*) FROM dc_ratings WHERE season_id = ? AND kit_id = ? AND rating > ? "
                + "AND (games >= ? OR tier_override IS NOT NULL)")) {
            ps.setInt(1, seasonId);
            ps.setInt(2, kitId);
            ps.setDouble(3, rating);
            ps.setInt(4, placementGames);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) + 1;
            }
        }
    }

    public static int overallRank(Connection c, int seasonId, int points) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT COUNT(*) FROM dc_standings WHERE season_id = ? AND points > ? AND overall_tier IS NOT NULL")) {
            ps.setInt(1, seasonId);
            ps.setInt(2, points);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) + 1;
            }
        }
    }

    private static List<Row> read(PreparedStatement ps) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            int rank = 0;
            while (rs.next()) {
                int tierId = rs.getInt(4);
                Tier tier = rs.wasNull() ? null : Tier.byId(tierId);
                rows.add(new Row(++rank, Uuids.fromBytes(rs.getBytes(1)), rs.getString(2), rs.getDouble(3), tier,
                    rs.getInt(5), rs.getInt(6), rs.getString(7), rs.getString(8)));
            }
        }
        return rows;
    }
}
