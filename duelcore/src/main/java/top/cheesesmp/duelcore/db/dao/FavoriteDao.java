package top.cheesesmp.duelcore.db.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import top.cheesesmp.duelcore.db.Dialect;

/** Favourite kits per player (dc_favorites, kit ids from dc_kits). */
public final class FavoriteDao {

    private FavoriteDao() {
    }

    /** The kit ids a player starred. */
    public static List<Integer> load(Connection c, int playerId) throws SQLException {
        List<Integer> kits = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT kit_id FROM dc_favorites WHERE player_id = ?")) {
            ps.setInt(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) kits.add(rs.getInt(1));
            }
        }
        return kits;
    }

    public static void add(Connection c, Dialect d, int playerId, int kitId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            d.insertIgnore() + " INTO dc_favorites (player_id, kit_id) VALUES (?, ?)")) {
            ps.setInt(1, playerId);
            ps.setInt(2, kitId);
            ps.executeUpdate();
        }
    }

    public static void remove(Connection c, int playerId, int kitId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM dc_favorites WHERE player_id = ? AND kit_id = ?")) {
            ps.setInt(1, playerId);
            ps.setInt(2, kitId);
            ps.executeUpdate();
        }
    }
}
