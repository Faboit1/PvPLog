package top.cheesesmp.duelcore.db.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.db.Uuids;

public final class PlayerDao {

    public record PlayerRow(int id, UUID uuid, String name, @Nullable String region, @Nullable String country,
                            int settings, int maxPing, long firstSeen, long lastSeen) {
    }

    private static final String COLUMNS = "id, uuid, name, region, country, settings, max_ping, first_seen, last_seen";

    private PlayerDao() {
    }

    /** Loads the player row, creating it on first join, and refreshes name + last_seen. */
    public static PlayerRow loadOrCreate(Connection c, UUID uuid, String name, long now, int defaultSettings) throws SQLException {
        Optional<PlayerRow> existing = findByUuid(c, uuid);
        if (existing.isPresent()) {
            PlayerRow row = existing.get();
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE dc_players SET name = ?, name_lower = ?, last_seen = ? WHERE id = ?")) {
                ps.setString(1, name);
                ps.setString(2, name.toLowerCase(Locale.ROOT));
                ps.setLong(3, now);
                ps.setInt(4, row.id());
                ps.executeUpdate();
            }
            return new PlayerRow(row.id(), uuid, name, row.region(), row.country(), row.settings(), row.maxPing(),
                row.firstSeen(), now);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO dc_players (uuid, name, name_lower, settings, max_ping, first_seen, last_seen) VALUES (?, ?, ?, ?, 0, ?, ?)",
            Statement.RETURN_GENERATED_KEYS)) {
            ps.setBytes(1, Uuids.toBytes(uuid));
            ps.setString(2, name);
            ps.setString(3, name.toLowerCase(Locale.ROOT));
            ps.setInt(4, defaultSettings);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("no generated id for player " + uuid);
                return new PlayerRow(keys.getInt(1), uuid, name, null, null, defaultSettings, 0, now, now);
            }
        }
    }

    public static Optional<PlayerRow> findByUuid(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM dc_players WHERE uuid = ?")) {
            ps.setBytes(1, Uuids.toBytes(uuid));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    public static Optional<PlayerRow> findByName(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT " + COLUMNS + " FROM dc_players WHERE name_lower = ? ORDER BY last_seen DESC LIMIT 1")) {
            ps.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    public static void saveSettings(Connection c, int id, int settings, @Nullable String region, @Nullable String country,
                                    int maxPing) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE dc_players SET settings = ?, region = ?, country = ?, max_ping = ? WHERE id = ?")) {
            ps.setInt(1, settings);
            ps.setString(2, region);
            ps.setString(3, country);
            ps.setInt(4, maxPing);
            ps.setInt(5, id);
            ps.executeUpdate();
        }
    }

    public static void touch(Connection c, int id, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE dc_players SET last_seen = ? WHERE id = ?")) {
            ps.setLong(1, now);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    private static PlayerRow read(ResultSet rs) throws SQLException {
        return new PlayerRow(rs.getInt(1), Uuids.fromBytes(rs.getBytes(2)), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getLong(8), rs.getLong(9));
    }
}
