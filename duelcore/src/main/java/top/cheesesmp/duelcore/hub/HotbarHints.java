package top.cheesesmp.duelcore.hub;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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
 * Action bar hints for the hub hotbar: while a hub item is held its gui.yml {@code action-bar} text shows (e.g.
 * "Right-click to play"), switching to another item shows that one's, and switching to an empty slot clears it.
 * While queued the "searching" action bar has priority, except for a moment right after switching items.
 */
public final class HotbarHints implements Listener, Runnable {

    /** How long a hint wins over the queue's "searching" action bar after switching items. */
    private static final long SWITCH_PRIORITY_MS = 2500;

    private final DuelCorePlugin plugin;
    private final Map<UUID, Long> switchedAt = new HashMap<>();
    /** Players whose action bar currently shows a hint (cleared once when they stop holding a hub item). */
    private final Set<UUID> showing = new HashSet<>();

    public HotbarHints(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** True for a moment after the player switched hotbar slots to a hub item with a hint. */
    public boolean recent(UUID uuid) {
        Long at = switchedAt.get(uuid);
        return at != null && System.currentTimeMillis() - at < SWITCH_PRIORITY_MS;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        Component hint = hint(player, player.getInventory().getItem(event.getNewSlot()));
        if (hint != null) {
            switchedAt.put(player.getUniqueId(), System.currentTimeMillis());
            showing.add(player.getUniqueId());
            player.sendActionBar(hint);
        } else {
            switchedAt.remove(player.getUniqueId());
            showing.remove(player.getUniqueId());
            // an empty hand (or an item without a hint) in the lobby clears the bar; match and queue bars stay
            if (inLobby(player) && !searching(player)) player.sendActionBar(Component.empty());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        switchedAt.remove(event.getPlayer().getUniqueId());
        showing.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Keeps the hint of the held item visible in the lobby (action bar text fades after a few seconds). Spectators
     * only get it when switching, so round results in their action bar aren't overwritten.
     */
    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (!inLobby(player)) {
                showing.remove(uuid);
                continue;
            }
            Component hint = hint(player, player.getInventory().getItemInMainHand());
            if (hint == null) {
                if (showing.remove(uuid) && !searching(player)) player.sendActionBar(Component.empty());
                continue;
            }
            // the queue's searching bar takes over again once the switch moment has passed
            if (searching(player) && !recent(uuid)) {
                showing.remove(uuid);
                continue;
            }
            showing.add(uuid);
            player.sendActionBar(hint);
        }
        switchedAt.values().removeIf(at -> System.currentTimeMillis() - at > SWITCH_PRIORITY_MS);
    }

    private @Nullable Component hint(Player player, @Nullable ItemStack stack) {
        String action = plugin.hub().action(stack);
        if (action == null) return null;
        GuiConfig.HotbarItem def = plugin.gui().item(action);
        if (def == null || def.actionBar().isBlank()) return null;
        return plugin.messages().parse(def.actionBar());
    }

    /** True when the queue shows its searching action bar to this player. */
    private boolean searching(Player player) {
        return plugin.settings().queueSearchingActionBar && plugin.queue().isQueued(player.getUniqueId());
    }

    private boolean inLobby(Player player) {
        UUID uuid = player.getUniqueId();
        return plugin.matches().match(uuid) == null && plugin.spectate().spectating(uuid) == null
            && plugin.hub().isHubWorld(player.getWorld());
    }
}
