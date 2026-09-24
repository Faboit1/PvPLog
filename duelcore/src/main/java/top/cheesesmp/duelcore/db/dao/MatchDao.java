package top.cheesesmp.duelcore.db.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import top.cheesesmp.duelcore.db.Uuids;

public final class MatchDao {

    public record ParticipantRecord(int playerId, int team, int roundsWon, int hits, float damageDealt, float damageTaken,
                                    float ratingBefore, float ratingAfter) {
    }

    public record MatchRecord(int seasonId, int kitId, boolean ranked, String arena, long startedAt, int durationMs,
                              int endReason, int winnerTeam, int firstTo, String rounds, List<ParticipantRecord> participants) {
    }

    /** One line of a player's match history, from that player's point of view. */
    public record HistoryEntry(long matchId, String kitKey, boolean ranked, long startedAt, int durationMs, int endReason,
                               boolean won, int roundsWon, int roundsLost, float ratingDelta, String opponentName,
                               UUID opponentUuid) {
    }

    private MatchDao() {
    }

    public static long insert(Connection c, MatchRecord m) throws SQLException {
        long id;
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO dc_matches (season_id, kit_id, ranked, arena, started_at, duration_ms, end_reason, winner_team, "
                + "first_to, rounds) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, m.seasonId());
            ps.setInt(2, m.kitId());
            ps.setInt(3, m.ranked() ? 1 : 0);
            ps.setString(4, truncate(m.arena(), 32));
            ps.setLong(5, m.startedAt());
            ps.setInt(6, m.durationMs());
            ps.setInt(7, m.endReason());
            ps.setInt(8, m.winnerTeam());
            ps.setInt(9, m.firstTo());
            ps.setString(10, truncate(m.rounds(), 64));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("no generated match id");
                id = keys.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO dc_match_players (match_id, player_id, team, rounds_won, hits, damage_dealt, damage_taken, "
                + "rating_before, rating_after) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (ParticipantRecord p : m.participants()) {
                ps.setLong(1, id);
                ps.setInt(2, p.playerId());
                ps.setInt(3, p.team());
                ps.setInt(4, p.roundsWon());
                ps.setInt(5, p.hits());
                ps.setFloat(6, p.damageDealt());
                ps.setFloat(7, p.damageTaken());
                ps.setFloat(8, p.ratingBefore());
                ps.setFloat(9, p.ratingAfter());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return id;
    }

    /** Most recent matches of a player (1v1 view: the opponent is the first player on another team). */
    public static List<HistoryEntry> history(Connection c, int playerId, int limit, Map<Integer, String> kitKeys) throws SQLException {
        List<HistoryEntry> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT m.id, m.kit_id, m.ranked, m.started_at, m.duration_ms, m.end_reason, m.winner_team, "
                + "me.team, me.rounds_won, me.rating_before, me.rating_after, "
                + "op.rounds_won, p.name, p.uuid "
                + "FROM dc_match_players me "
                + "JOIN dc_matches m ON m.id = me.match_id "
                + "JOIN dc_match_players op ON op.match_id = me.match_id AND op.team <> me.team "
                + "JOIN dc_players p ON p.id = op.player_id "
                + "WHERE me.player_id = ? ORDER BY me.match_id DESC LIMIT ?")) {
            ps.setInt(1, playerId);
            ps.setInt(2, limit * 4);
            try (ResultSet rs = ps.executeQuery()) {
                long lastMatch = -1;
                while (rs.next() && out.size() < limit) {
                    long matchId = rs.getLong(1);
                    if (matchId == lastMatch) continue; // team games: one row per opponent, keep the first
                    lastMatch = matchId;
                    boolean won = rs.getInt(7) == rs.getInt(8);
                    out.add(new HistoryEntry(matchId, kitKeys.getOrDefault(rs.getInt(2), "?"), rs.getInt(3) != 0,
                        rs.getLong(4), rs.getInt(5), rs.getInt(6), won, rs.getInt(9), rs.getInt(12),
                        rs.getFloat(11) - rs.getFloat(10), rs.getString(13), Uuids.fromBytes(rs.getBytes(14))));
                }
            }
        }
        return out;
    }

    public static long count(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM dc_matches");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
