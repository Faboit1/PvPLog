package top.cheesesmp.duelcore.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.match.Match;

/**
 * Who is listed in whose tab list, and the tab status icons.
 *
 * <p>Someone in a match (fighting or spectating) only sees the players of that match in the tab list: the fighters
 * and its spectators (who look like spectator-mode entries, see {@link TagService}). Everyone else sees everyone.
 * Players are only hidden from the list, not from the world. The status shown next to a name in the tab list (in a
 * match, queueing, nothing in the lobby) is refreshed from here whenever it changes. Runs every 10 ticks.
 */
public final class TabListing implements Runnable, Listener {

    /** What a player is doing, for the tab status icon. */
    public enum Status { LOBBY, QUEUE, MATCH }

    private final DuelCorePlugin plugin;
    private final Map<UUID, Status> shown = new HashMap<>();

    public TabListing(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** The match a player is fighting in (not over yet) or watching; null in the lobby. */
    private @Nullable Match context(Player p) {
        Match m = plugin.matches().match(p.getUniqueId());
        if (m != null && !m.isOver()) return m;
        return plugin.spectate().spectating(p.getUniqueId());
    }

    public Status status(Player p) {
        Match m = plugin.matches().match(p.getUniqueId());
        if (m != null && !m.isOver()) return Status.MATCH;
        if (plugin.queue().isQueued(p.getUniqueId())) return Status.QUEUE;
        return Status.LOBBY;
    }

    @Override
    public void run() {
        var online = Bukkit.getOnlinePlayers();
        Map<UUID, Match> contexts = new HashMap<>();
        for (Player p : online) {
            Match c = context(p);
            if (c != null) contexts.put(p.getUniqueId(), c);
        }
        for (Player viewer : online) {
            Match vc = contexts.get(viewer.getUniqueId());
            for (Player other : online) {
                if (other == viewer) continue;
                boolean listed = vc == null || Objects.equals(vc, contexts.get(other.getUniqueId()));
                if (listed != viewer.isListed(other)) {
                    if (listed) viewer.listPlayer(other);
                    else viewer.unlistPlayer(other);
                }
            }
        }
        for (Player p : online) {
            Status s = status(p);
            if (shown.put(p.getUniqueId(), s) != s) plugin.tags().update(p);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        shown.remove(event.getPlayer().getUniqueId());
    }
}
