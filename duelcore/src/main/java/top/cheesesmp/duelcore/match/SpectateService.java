package top.cheesesmp.duelcore.match;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import top.cheesesmp.duelcore.arena.ArenaInstance;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.kit.KitManager;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;

/**
 * Watching live matches. Spectators are in spectator mode: the fighters can't see them in the world (or hit, or
 * target them), but they stay in the tab list as spectators. They can fly through the arena, watch through a
 * fighter's eyes (left-click them), and can't touch anything. The spectator menu's teleports and flying off are
 * kept inside their match's arena. They keep their queue entries.
 */
public final class SpectateService implements Listener, Runnable {

    /** How far (blocks) spectators may fly past the arena's sides, top and floor before they're brought back. */
    private static final double MARGIN = 24;

    public enum Result { OK, NOT_IN_MATCH, SELF, BUSY, DISALLOWED, NO_ARENA }

    private final DuelCorePlugin plugin;
    private final Map<UUID, Match> spectating = new HashMap<>();

    public SpectateService(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    public @Nullable Match spectating(UUID uuid) {
        return spectating.get(uuid);
    }

    public int count() {
        return spectating.size();
    }

    public Result spectate(Player viewer, Player target) {
        if (viewer.equals(target)) return Result.SELF;
        Match match = plugin.matches().match(target.getUniqueId());
        if (match == null || match.isOver()) return Result.NOT_IN_MATCH;
        PlayerProfile targetProfile = plugin.profiles().get(target);
        if (targetProfile != null && !targetProfile.setting(Setting.ALLOW_SPECTATORS)
            && !viewer.hasPermission("duelcore.spectate.bypass")) {
            return Result.DISALLOWED;
        }
        return spectate(viewer, match, target.getLocation());
    }

    public Result spectate(Player viewer, Match match, @Nullable Location focus) {
        if (plugin.matches().match(viewer.getUniqueId()) != null) return Result.BUSY;
        if (match.arena() == null) return Result.NO_ARENA;
        Match previous = spectating.remove(viewer.getUniqueId());
        if (previous != null) previous.spectators().remove(viewer.getUniqueId());
        spectating.put(viewer.getUniqueId(), match);
        match.spectators().add(viewer.getUniqueId());
        plugin.queueMusic().stop(viewer.getUniqueId()); // they stay queued, but the match is what they hear now
        plugin.tags().update(viewer); // grey, italic and last in the tab list
        Location to = focus != null && match.arena().contains(focus) ? focus.clone().add(0, 3, 0) : match.arena().center().add(0, 6, 0);
        KitManager.resetState(viewer, 20);
        viewer.setGameMode(GameMode.SPECTATOR);
        viewer.setInvulnerable(true);
        viewer.setCollidable(false);
        viewer.teleportAsync(to).thenRun(() -> {
            if (!viewer.isOnline() || spectating.get(viewer.getUniqueId()) != match) return;
            plugin.hub().giveSpectatorItems(viewer);
            plugin.visibility().refresh(viewer);
            plugin.sidebar().refresh(viewer);
        });
        if (isFreeForAll(match)) {
            plugin.messages().send(viewer, "spectate.started-ffa", Messages.num("players", match.participants().size()),
                Messages.num("alive", match.alive()), Messages.comp("kit", match.kit().displayName()));
        } else {
            plugin.messages().send(viewer, "spectate.started", Messages.text("red", match.teamName(0)),
                Messages.text("blue", match.teamName(1)), Messages.comp("kit", match.kit().displayName()));
        }
        return Result.OK;
    }

    /** A Party FFA (or any match of more than two teams): shown as players/alive instead of "red vs blue". */
    public static boolean isFreeForAll(Match match) {
        return match.ffa() || match.teamCount() > 2;
    }

    /** Stops spectating; sends to the hub when {@code toHub}. */
    public boolean leave(Player player, boolean toHub) {
        Match match = spectating.remove(player.getUniqueId());
        if (match == null) return false;
        match.spectators().remove(player.getUniqueId());
        plugin.tags().update(player);
        if (player.getGameMode() == GameMode.SPECTATOR) player.setGameMode(GameMode.ADVENTURE);
        player.setCollidable(true);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvulnerable(false);
        plugin.visibility().refresh(player);
        if (toHub) plugin.hub().send(player);
        return true;
    }

    /** The spectator menu (number keys) teleports to any player on the server: only within the watched arena. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpectatorTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE) return;
        Match match = spectating.get(event.getPlayer().getUniqueId());
        if (match == null) return;
        ArenaInstance arena = match.arena();
        if (arena == null || !arena.contains(event.getTo())) event.setCancelled(true);
    }

    /** Every second: spectators who flew far off their arena (spectator mode passes through anything) go back. */
    @Override
    public void run() {
        for (Map.Entry<UUID, Match> e : spectating.entrySet()) {
            Player player = plugin.getServer().getPlayer(e.getKey());
            ArenaInstance arena = e.getValue().arena();
            if (player == null || arena == null) continue;
            Location loc = player.getLocation();
            boolean away = loc.getWorld() != arena.world() || !arena.containsXZ(loc.getX(), loc.getZ(), MARGIN)
                || loc.getY() < arena.floorY() - MARGIN || loc.getY() > arena.floorY() + arena.template().sizeY() + MARGIN;
            if (!away) continue;
            if (player.getSpectatorTarget() != null) player.setSpectatorTarget(null);
            player.teleportAsync(arena.center().add(0, 6, 0));
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        Match match = spectating.remove(event.getPlayer().getUniqueId());
        if (match != null) match.spectators().remove(event.getPlayer().getUniqueId());
        event.getPlayer().setCollidable(true);
    }
}
