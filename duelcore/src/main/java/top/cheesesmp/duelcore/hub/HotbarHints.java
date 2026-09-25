package top.cheesesmp.duelcore.hub;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.GuiConfig;

/**
 * Action bar hints for the hub hotbar: holding a hub item shows what it does (gui.yml {@code hotbar.<key>.action-bar}),
 * once, when it comes into the hand (slot switch, join, new items). Switching to an empty slot or another item clears
 * a hint that may still be on screen. While queued the queue's "searching" bar has priority and no hint is sent.
 */
public final class HotbarHints implements Listener {

    /** How long the client shows an action bar (60 ticks plus fade-out); after that there's nothing to clear. */
    private static final long VISIBLE_MS = 3500;

    private final DuelCorePlugin plugin;
    /** When a hint was last shown, per player. */
    private final Map<UUID, Long> shownAt = new HashMap<>();

    public HotbarHints(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** Shows the hint for what the player is holding now (after items were given or the slot was set). */
    public void refresh(Player player) {
        show(player, player.getInventory().getItemInMainHand());
    }

    private void show(Player player, @Nullable ItemStack item) {
        UUID uuid = player.getUniqueId();
        if (plugin.matches().match(uuid) != null) {
            shownAt.remove(uuid); // match action bars (round results, bounds) must never be cleared by us
            return;
        }
        if (plugin.settings().queueSearchingActionBar && plugin.queue().isQueued(uuid)) return;
        String action = plugin.hub().action(item);
        GuiConfig.HotbarItem def = action == null ? null : plugin.gui().item(action);
        long now = System.currentTimeMillis();
        if (def != null && !def.actionBar().isBlank()) {
            player.sendActionBar(plugin.messages().parse(def.actionBar()));
            shownAt.put(uuid, now);
            return;
        }
        Long at = shownAt.remove(uuid);
        if (at != null && now - at < VISIBLE_MS) player.sendActionBar(Component.empty());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        show(player, player.getInventory().getItem(event.getNewSlot()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        shownAt.remove(event.getPlayer().getUniqueId());
    }
}
