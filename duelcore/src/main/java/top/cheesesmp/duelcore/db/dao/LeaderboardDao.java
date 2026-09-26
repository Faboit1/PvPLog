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
     * for the overall board {@code value} is the overall Elo and {@code tier} the overall tier. Test bots
     * ({@link #BOT_PREFIX}) are never listed, so ranks count real players only.
     */
    public record Row(int rank, UUID uuid, String name, double value, @Nullable Tier tier, int wins, int losses,
                      @Nullable String region, @Nullable String country) {
    }

    /**
     * Name prefix of the test bots (dcbot_a, DCBot2, ...), matched case-insensitively. They never show on a board and
     * are not counted in ranks.
     */
    public static final String BOT_PREFIX = "dcbot";

    /** SQL condition (on {@code dc_players p}) that leaves out the test bots. */
    static final String NOT_BOT = "p.name_lower NOT LIKE '" + BOT_PREFIX + "%'";

    private LeaderboardDao() {
    }

    public static List<Row> kit(Connection c, int seasonId, int kitId, int placementGames, @Nullable String region,
                                @Nullable String country, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder(
            "SELECT p.uuid, p.name, r.rating, r.tier_override, r.wins, r.losses, p.region, p.country "
                + "FROM dc_ratings r JOIN dc_players p ON p.id = r.player_id "
                + "WHERE r.season_id = ? AND r.kit_id = ? AND (r.games >= ? OR r.tier_override IS NOT NULL) AND " + NOT_BOT);
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
            "SELECT p.uuid, p.name, s.elo, s.overall_tier, "
                + "(SELECT COALESCE(SUM(r.wins), 0) FROM dc_ratings r WHERE r.season_id = s.season_id AND r.player_id = s.player_id), "
                + "(SELECT COALESCE(SUM(r.losses), 0) FROM dc_ratings r WHERE r.season_id = s.season_id AND r.player_id = s.player_id), "
                + "p.region, p.country "
                + "FROM dc_standings s JOIN dc_players p ON p.id = s.player_id "
                + "WHERE s.season_id = ? AND s.overall_tier IS NOT NULL AND " + NOT_BOT);
        if (region != null) sql.append(" AND p.region = ?");
        if (country != null) sql.append(" AND p.country = ?");
        sql.append(" ORDER BY s.elo DESC, p.name ASC LIMIT ?");
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setInt(i++, seasonId);
            if (region != null) ps.setString(i++, region);
            if (country != null) ps.setString(i++, country);
            ps.setInt(i, limit);
            return read(ps);
        }
    }

    /** 1-based rank of a rating on a kit board (ties share the better rank; test bots are not counted). */
    public static int kitRank(Connection c, int seasonId, int kitId, int placementGames, double rating) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT COUNT(*) FROM dc_ratings r JOIN dc_players p ON p.id = r.player_id "
                + "WHERE r.season_id = ? AND r.kit_id = ? AND r.rating > ? "
                + "AND (r.games >= ? OR r.tier_override IS NOT NULL) AND " + NOT_BOT)) {
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

    /** 1-based rank of an overall Elo on the overall board (ties share the better rank; test bots are not counted). */
    public static int overallRank(Connection c, int seasonId, int elo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT COUNT(*) FROM dc_standings s JOIN dc_players p ON p.id = s.player_id "
                + "WHERE s.season_id = ? AND s.elo > ? AND s.overall_tier IS NOT NULL AND " + NOT_BOT)) {
            ps.setInt(1, seasonId);
            ps.setInt(2, elo);
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
