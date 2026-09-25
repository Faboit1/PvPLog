package top.cheesesmp.duelcore.db.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import top.cheesesmp.duelcore.db.Dialect;

/** Players' own kit layouts from the kit editor (dc_kit_layouts, kit ids from dc_kits). */
public final class KitLayoutDao {

    /** One saved layout: the encoded arrangement and the fingerprint of the kit it was made for. */
    public record Row(int kitId, String layout, int kitHash, long updatedAt) {
    }

    private KitLayoutDao() {
    }

    /** Every layout a player saved. */
    public static List<Row> load(Connection c, int playerId) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT kit_id, layout, kit_hash, updated_at FROM dc_kit_layouts WHERE player_id = ?")) {
            ps.setInt(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new Row(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getLong(4)));
            }
        }
        return rows;
    }

    /** Inserts or replaces the layout of one kit. */
    public static void save(Connection c, Dialect d, int playerId, int kitId, String layout, int kitHash, long now)
        throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO dc_kit_layouts (player_id, kit_id, layout, kit_hash, updated_at) VALUES (?, ?, ?, ?, ?)"
                + d.upsert("player_id, kit_id", "layout", "kit_hash", "updated_at"))) {
            ps.setInt(1, playerId);
            ps.setInt(2, kitId);
            ps.setString(3, layout);
            ps.setInt(4, kitHash);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    /** Forgets the layout of one kit (back to the default). */
    public static void delete(Connection c, int playerId, int kitId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM dc_kit_layouts WHERE player_id = ? AND kit_id = ?")) {
            ps.setInt(1, playerId);
            ps.setInt(2, kitId);
            ps.executeUpdate();
        }
    }
}
