package top.cheesesmp.duelcore.match;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.Messages;

/**
 * Disconnect saves: a ranked match that ends because a player's connection dropped (not because they left) changes
 * nobody's Elo, at most {@code match.disconnect-saves-per-day} times per player per (UTC) day; after that a drop
 * counts as a forfeit again.
 *
 * <p>A quit is a connection drop when the server says so ({@code TIMED_OUT}, {@code ERRONEOUS_STATE}) or, behind a
 * proxy (which closes the backend connection normally when the client times out), when the player sent no movement,
 * look or input for {@code match.disconnect-idle-seconds} before the quit: a real drop goes silent for the proxy's
 * read timeout first, someone clicking Disconnect mid-fight was still moving. Kicks never count.
 *
 * <p>Used saves are kept in {@code dc_disconnect_saves} (loaded on join) so a restart doesn't hand out new ones.
 */
public final class DisconnectSaves implements Listener {

    private record Day(long day, int used) {
    }

    private final DuelCorePlugin plugin;
    private final Map<UUID, Day> used = new ConcurrentHashMap<>();
    /** Last movement, look or input per player (ms). */
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    /** Players to tell, on their next join, that a drop was saved (and how many saves are left). */
    private final Map<UUID, Integer> notices = new ConcurrentHashMap<>();

    public DisconnectSaves(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    private static long today() {
        return LocalDate.now(ZoneOffset.UTC).toEpochDay();
    }

    private int perDay() {
        return plugin.settings().disconnectSavesPerDay;
    }

    /** Saves used today (0 until the join-time load finished). */
    public int usedToday(UUID player) {
        Day d = used.get(player);
        return d == null || d.day() != today() ? 0 : d.used();
    }

    /** Whether this quit is a dropped connection (see the class doc). */
    public boolean connectionLost(Player player, PlayerQuitEvent.QuitReason reason) {
        return switch (reason) {
            case TIMED_OUT, ERRONEOUS_STATE -> true;
            case KICKED -> false;
            case DISCONNECTED -> {
                Long last = lastActive.get(player.getUniqueId());
                long idleMs = plugin.settings().disconnectIdleSeconds * 1000L;
                yield idleMs > 0 && last != null && System.currentTimeMillis() - last >= idleMs;
            }
        };
    }

    /** Uses one of today's saves; false when none are left (or saves are off). */
    public boolean tryUse(UUID player) {
        int max = perDay();
        if (max <= 0) return false;
        long day = today();
        int now = usedToday(player);
        if (now >= max) return false;
        int after = now + 1;
        used.put(player, new Day(day, after));
        notices.put(player, Math.max(0, max - after));
        var d = plugin.database().dialect();
        plugin.database().submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO dc_disconnect_saves (uuid, day, used) VALUES (?, ?, ?)" + d.upsert("uuid, day", "used"))) {
                ps.setString(1, player.toString());
                ps.setLong(2, day);
                ps.setInt(3, after);
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(e -> {
            plugin.getLogger().warning("Could not save a disconnect save for " + player + ": " + e.getMessage());
            return null;
        });
        return true;
    }

    public int remaining(UUID player) {
        return Math.max(0, perDay() - usedToday(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastActive.put(id, System.currentTimeMillis());
        long day = today();
        plugin.database().submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT used FROM dc_disconnect_saves WHERE uuid = ? AND day = ?")) {
                ps.setString(1, id.toString());
                ps.setLong(2, day);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }).thenAccept(n -> used.merge(id, new Day(day, n), (old, fresh) -> old.day() == day && old.used() > fresh.used() ? old : fresh))
            .exceptionally(e -> {
                plugin.getLogger().warning("Could not load disconnect saves for " + id + ": " + e.getMessage());
                return null;
            });
        Integer left = notices.remove(id);
        if (left != null) {
            Player p = event.getPlayer();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) plugin.messages().send(p, "match.connection-saved", Messages.num("left", left),
                    Messages.num("per_day", perDay()));
            }, 40L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        lastActive.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInput(PlayerInputEvent event) {
        lastActive.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event) {
        lastActive.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    /** Runs after the match listener's quit handling (MONITOR), which reads the activity first. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastActive.remove(event.getPlayer().getUniqueId());
    }
}
