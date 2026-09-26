package top.cheesesmp.duelcore.hub;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;

/**
 * Who sees whom. Hub players can hide each other (setting or config); spectators are hidden from fighters by being in
 * spectator mode (see SpectateService).
 * Other cases are left to the game (different worlds / far apart slots are never rendered anyway).
 */
public final class VisibilityService {

    private final DuelCorePlugin plugin;

    public VisibilityService(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** Recomputes visibility between this player and everyone else, both directions. */
    public void refresh(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other == player) continue;
            apply(player, other);
            apply(other, player);
        }
    }

    private void apply(Player viewer, Player target) {
        boolean show = canSee(viewer, target);
        if (show && !viewer.canSee(target)) viewer.showPlayer(plugin, target);
        else if (!show && viewer.canSee(target)) viewer.hidePlayer(plugin, target);
    }

    private boolean canSee(Player viewer, Player target) {
        // spectators are in spectator mode: the game already hides them from the fighters, and hiding them here
        // would also take them out of the tab list
        if (plugin.spectate().spectating(target.getUniqueId()) != null) return true;
        boolean viewerInHub = plugin.matches().match(viewer.getUniqueId()) == null
            && plugin.spectate().spectating(viewer.getUniqueId()) == null;
        boolean targetInHub = plugin.matches().match(target.getUniqueId()) == null;
        if (viewerInHub && targetInHub && plugin.hub().isHubWorld(viewer.getWorld())) {
            if (!plugin.settings().hubShowPlayers) return false;
            PlayerProfile profile = plugin.profiles().get(viewer);
            return profile == null || !profile.setting(Setting.HIDE_HUB_PLAYERS);
        }
        return true;
    }
}
