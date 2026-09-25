package top.cheesesmp.duelcore.db.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.db.Dialect;
import top.cheesesmp.duelcore.db.Uuids;

/**
 * Persistent parties (dc_parties, dc_party_members) and the notices of players who were removed from a party while
 * offline (dc_meta rows {@code party-notice:<player id>}).
 */
public final class PartyDao {

    public record MemberRow(int playerId, UUID uuid, String name, long joinedAt, boolean chat) {
    }

    public record PartyRow(String id, int leaderId, boolean open, @Nullable String password, long createdAt,
                           List<MemberRow> members) {
    }

    /** A message waiting for a player's next join. */
    public record Notice(int playerId, UUID uuid, String text) {
    }

    private static final String NOTICE = "party-notice:";

    private PartyDao() {
    }

    /**
     * Removes rows that can't belong to a party any more: members of deleted parties or players, and parties without
     * members. Returns the number of rows removed.
     */
    public static int cleanup(Connection c) throws SQLException {
        int n = update(c, "DELETE FROM dc_party_members WHERE party_id NOT IN (SELECT id FROM dc_parties)");
        n += update(c, "DELETE FROM dc_party_members WHERE player_id NOT IN (SELECT id FROM dc_players)");
        n += update(c, "DELETE FROM dc_parties WHERE id NOT IN (SELECT party_id FROM dc_party_members)");
        return n;
    }

    /** Every party with its members (oldest member first). */
    public static List<PartyRow> loadAll(Connection c) throws SQLException {
        Map<String, List<MemberRow>> members = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT m.party_id, m.player_id, p.uuid, p.name, m.joined_at, m.chat FROM dc_party_members m "
                + "JOIN dc_players p ON p.id = m.player_id ORDER BY m.joined_at, m.player_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                members.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new MemberRow(rs.getInt(2),
                    Uuids.fromBytes(rs.getBytes(3)), rs.getString(4), rs.getLong(5), rs.getInt(6) != 0));
            }
        }
        List<PartyRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, leader_id, open, password, created_at FROM dc_parties ORDER BY created_at, id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString(1);
                out.add(new PartyRow(id, rs.getInt(2), rs.getInt(3) != 0, rs.getString(4), rs.getLong(5),
                    List.copyOf(members.getOrDefault(id, List.of()))));
            }
        }
        return out;
    }

    public static void insertParty(Connection c, String id, int leaderId, boolean open, @Nullable String password,
                                   long createdAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO dc_parties (id, leader_id, open, password, created_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setInt(2, leaderId);
            ps.setInt(3, open ? 1 : 0);
            ps.setString(4, password);
            ps.setLong(5, createdAt);
            ps.executeUpdate();
        }
    }

    public static void updateParty(Connection c, String id, int leaderId, boolean open, @Nullable String password)
        throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE dc_parties SET leader_id = ?, open = ?, password = ? WHERE id = ?")) {
            ps.setInt(1, leaderId);
            ps.setInt(2, open ? 1 : 0);
            ps.setString(3, password);
            ps.setString(4, id);
            ps.executeUpdate();
        }
    }

    /** Deletes a party and its member rows. */
    public static void deleteParty(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM dc_party_members WHERE party_id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM dc_parties WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    /** Adds (or moves) a player into a party; a player is in at most one party (player_id is the key). */
    public static void addMember(Connection c, Dialect d, String partyId, int playerId, long joinedAt, boolean chat)
        throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO dc_party_members (player_id, party_id, joined_at, chat) VALUES (?, ?, ?, ?)"
                + d.upsert("player_id", "party_id", "joined_at", "chat"))) {
            ps.setInt(1, playerId);
            ps.setString(2, partyId);
            ps.setLong(3, joinedAt);
            ps.setInt(4, chat ? 1 : 0);
            ps.executeUpdate();
        }
    }

    public static void removeMember(Connection c, int playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM dc_party_members WHERE player_id = ?")) {
            ps.setInt(1, playerId);
            ps.executeUpdate();
        }
    }

    public static void setChat(Connection c, int playerId, boolean chat) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE dc_party_members SET chat = ? WHERE player_id = ?")) {
            ps.setInt(1, chat ? 1 : 0);
            ps.setInt(2, playerId);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ offline notices

    public static List<Notice> loadNotices(Connection c) throws SQLException {
        Map<Integer, String> texts = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT k, v FROM dc_meta WHERE k LIKE ?")) {
            ps.setString(1, NOTICE + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        texts.put(Integer.parseInt(rs.getString(1).substring(NOTICE.length())), rs.getString(2));
                    } catch (NumberFormatException ignored) {
                        // not ours
                    }
                }
            }
        }
        List<Notice> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid FROM dc_players WHERE id = ?")) {
            for (Map.Entry<Integer, String> e : texts.entrySet()) {
                ps.setInt(1, e.getKey());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) out.add(new Notice(e.getKey(), Uuids.fromBytes(rs.getBytes(1)), e.getValue()));
                }
            }
        }
        return out;
    }

    public static void setNotice(Connection c, Dialect d, int playerId, String text) throws SQLException {
        MetaDao.set(c, d, NOTICE + playerId, text.length() > 255 ? text.substring(0, 255) : text);
    }

    public static void clearNotice(Connection c, int playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM dc_meta WHERE k = ?")) {
            ps.setString(1, NOTICE + playerId);
            ps.executeUpdate();
        }
    }

    private static int update(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            return ps.executeUpdate();
        }
    }
}
