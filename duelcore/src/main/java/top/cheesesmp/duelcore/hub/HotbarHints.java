package top.cheesesmp.duelcore.hub;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.GuiConfig;

/**
 * Action-bar hints for the hub hotbar: holding an item shows its gui.yml {@code action-bar} text ("Right-click to
 * play"), and switching to an empty slot in the hub clears the bar. Matches and the queue's "searching" bar keep the
 * action bar to themselves.
 */
public final class HotbarHints implements Listener {

    private final DuelCorePlugin plugin;

    public HotbarHints(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        show(player, player.getInventory().getItem(event.getNewSlot()));
    }

    /** The hub hotbar is given on join with the first slot selected: show its hint once it's there. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) show(player, player.getInventory().getItemInMainHand());
        }, 10L);
    }

    /** Shows the hint of {@code held}, or clears the action bar when it has none (hub only). */
    public void show(Player player, @Nullable ItemStack held) {
        if (plugin.matches().match(player.getUniqueId()) != null) return;
        if (plugin.settings().queueSearchingActionBar && plugin.queue().isQueued(player.getUniqueId())) return;
        String action = plugin.hub().action(held);
        if (action == null && (plugin.spectate().spectating(player.getUniqueId()) != null
            || !plugin.hub().isHubWorld(player.getWorld()))) {
            return;
        }
        GuiConfig.HotbarItem def = action == null ? null : plugin.gui().item(action);
        String hint = def == null ? "" : def.actionBar();
        player.sendActionBar(hint.isBlank() ? Component.empty() : plugin.messages().parse(hint));
    }
}
