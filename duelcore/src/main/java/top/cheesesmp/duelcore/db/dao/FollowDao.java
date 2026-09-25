package top.cheesesmp.duelcore.db.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import top.cheesesmp.duelcore.db.Dialect;
import top.cheesesmp.duelcore.db.Uuids;

/** One-way follows (dc_follows). Two players who follow each other are friends. */
public final class FollowDao {

    /** The other side of a follow: their player id, uuid, last known name and when the follow was made. */
    public record Person(int id, UUID uuid, String name, long since) {
    }

    /** Everyone a player follows and everyone who follows them. */
    public record Relations(List<Person> following, List<Person> followers) {
    }

    private FollowDao() {
    }

    /** Adds a follow; returns false when it already existed. */
    public static boolean follow(Connection c, Dialect d, int followerId, int followedId, long now) throws SQLException {
        if (followerId == followedId) return false;
        try (PreparedStatement ps = c.prepareStatement(
            d.insertIgnore() + " INTO dc_follows (follower_id, followed_id, created_at) VALUES (?, ?, ?)")) {
            ps.setInt(1, followerId);
            ps.setInt(2, followedId);
            ps.setLong(3, now);
            return ps.executeUpdate() > 0;
        }
    }

    /** Removes a follow; returns false when there was none. */
    public static boolean unfollow(Connection c, int followerId, int followedId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "DELETE FROM dc_follows WHERE follower_id = ? AND followed_id = ?")) {
            ps.setInt(1, followerId);
            ps.setInt(2, followedId);
            return ps.executeUpdate() > 0;
        }
    }

    public static boolean isFollowing(Connection c, int followerId, int followedId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT 1 FROM dc_follows WHERE follower_id = ? AND followed_id = ?")) {
            ps.setInt(1, followerId);
            ps.setInt(2, followedId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Players {@code playerId} follows. */
    public static List<Person> following(Connection c, int playerId) throws SQLException {
        return people(c, "SELECT p.id, p.uuid, p.name, f.created_at FROM dc_follows f "
            + "JOIN dc_players p ON p.id = f.followed_id WHERE f.follower_id = ?", playerId);
    }

    /** Players who follow {@code playerId}. */
    public static List<Person> followers(Connection c, int playerId) throws SQLException {
        return people(c, "SELECT p.id, p.uuid, p.name, f.created_at FROM dc_follows f "
            + "JOIN dc_players p ON p.id = f.follower_id WHERE f.followed_id = ?", playerId);
    }

    public static Relations load(Connection c, int playerId) throws SQLException {
        return new Relations(following(c, playerId), followers(c, playerId));
    }

    private static List<Person> people(Connection c, String sql, int playerId) throws SQLException {
        List<Person> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Person(rs.getInt(1), Uuids.fromBytes(rs.getBytes(2)), rs.getString(3), rs.getLong(4)));
                }
            }
        }
        return out;
    }
}
