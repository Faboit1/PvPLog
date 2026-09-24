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
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.kit.KitManager;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;

/**
 * Watching live matches. Spectators fly in adventure mode, are invulnerable, invisible to the fighters, and can't
 * touch anything; projectiles pass through them. They keep their queue entries.
 */
public final class SpectateService implements Listener {

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
        Location to = focus != null && match.arena().contains(focus) ? focus.clone().add(0, 3, 0) : match.arena().center().add(0, 6, 0);
        KitManager.resetState(viewer, 20);
        viewer.setGameMode(GameMode.ADVENTURE);
        viewer.setInvulnerable(true);
        viewer.setCollidable(false);
        viewer.teleportAsync(to).thenRun(() -> {
            if (!viewer.isOnline() || spectating.get(viewer.getUniqueId()) != match) return;
            viewer.setAllowFlight(true);
            viewer.setFlying(true);
            plugin.hub().giveSpectatorItems(viewer);
            plugin.visibility().refresh(viewer);
            plugin.sidebar().refresh(viewer);
        });
        plugin.messages().send(viewer, "spectate.started", top.cheesesmp.duelcore.config.Messages.text("red", match.teamName(0)),
            top.cheesesmp.duelcore.config.Messages.text("blue", match.teamName(1)),
            top.cheesesmp.duelcore.config.Messages.comp("kit", match.kit().displayName()));
        return Result.OK;
    }

    /** Stops spectating; sends to the hub when {@code toHub}. */
    public boolean leave(Player player, boolean toHub) {
        Match match = spectating.remove(player.getUniqueId());
        if (match == null) return false;
        match.spectators().remove(player.getUniqueId());
        player.setCollidable(true);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvulnerable(false);
        plugin.visibility().refresh(player);
        if (toHub) plugin.hub().send(player);
        return true;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        Match match = spectating.remove(event.getPlayer().getUniqueId());
        if (match != null) match.spectators().remove(event.getPlayer().getUniqueId());
        event.getPlayer().setCollidable(true);
    }
}
