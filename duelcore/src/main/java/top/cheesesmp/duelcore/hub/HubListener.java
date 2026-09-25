package top.cheesesmp.duelcore.hub;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.EquipmentSlot;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.queue.QueueMode;

/** Protects the hub and turns hotbar clicks into menus. */
public final class HubListener implements Listener {

    public static final String BUILD_PERMISSION = "duelcore.hub.build";

    private final DuelCorePlugin plugin;
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public HubListener(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** True when the player is in the lobby (not fighting, not spectating) and hub rules apply. */
    private boolean inHub(Player player) {
        return plugin.matches().match(player.getUniqueId()) == null
            && plugin.spectate().spectating(player.getUniqueId()) == null
            && plugin.hub().isHubWorld(player.getWorld());
    }

    private boolean builder(Player player) {
        return player.getGameMode() == GameMode.CREATIVE && player.hasPermission(BUILD_PERMISSION);
    }

    /** Everyone logs in at the hub (never inside a stale arena slot from their last session). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnLocation(io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent event) {
        org.bukkit.Location spawn = plugin.hub().spawn();
        if (spawn.isWorldLoaded()) event.setSpawnLocation(spawn);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String join = plugin.messages().raw("hub.join-message");
        event.joinMessage(join.isBlank() ? null : plugin.messages().parse(join,
            top.cheesesmp.duelcore.config.Messages.text("player", player.getName())));
        if (plugin.profiles().get(player) == null) {
            // plugin was (re)loaded while the player was online
            plugin.profiles().loadOnline(player).thenRun(() -> org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) plugin.hub().send(player);
            }));
        }
        plugin.hub().send(player);
        plugin.messages().send(player, "hub.welcome", top.cheesesmp.duelcore.config.Messages.text("player", player.getName()));
        if (plugin.settings().animJoinTitle) {
            player.showTitle(net.kyori.adventure.title.Title.title(plugin.messages().get("hub.join-title"),
                plugin.messages().get("hub.join-subtitle"), net.kyori.adventure.title.Title.Times.times(
                    java.time.Duration.ofMillis(500), java.time.Duration.ofMillis(1800), java.time.Duration.ofMillis(700))));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        String quit = plugin.messages().raw("hub.quit-message");
        event.quitMessage(quit.isBlank() ? null : plugin.messages().parse(quit,
            top.cheesesmp.duelcore.config.Messages.text("player", event.getPlayer().getName())));
        lastUse.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (plugin.matches().match(player.getUniqueId()) == null) {
            event.setRespawnLocation(plugin.hub().spawn());
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) plugin.hub().prepare(player);
            });
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (plugin.spectate().spectating(player.getUniqueId()) != null || inHub(player)) {
            event.setCancelled(true);
            if (event.getCause() == EntityDamageEvent.DamageCause.VOID) player.teleportAsync(plugin.hub().spawn());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && (inHub(player)
            || plugin.spectate().spectating(player.getUniqueId()) != null)) {
            event.setCancelled(true);
            player.setFoodLevel(20);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent event) {
        if (inHub(event.getPlayer()) && !builder(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPlace(BlockPlaceEvent event) {
        if (inHub(event.getPlayer()) && !builder(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if ((inHub(player) && !builder(player)) || plugin.hub().action(event.getItemDrop().getItemStack()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && (inHub(player) && !builder(player)
            || plugin.spectate().spectating(player.getUniqueId()) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        boolean hubItem = plugin.hub().action(event.getCurrentItem()) != null || plugin.hub().action(event.getCursor()) != null;
        if (event.getHotbarButton() >= 0) {
            hubItem |= plugin.hub().action(player.getInventory().getItem(event.getHotbarButton())) != null;
        }
        if (hubItem || (inHub(player) && !builder(player)) || plugin.spectate().spectating(player.getUniqueId()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && ((inHub(player) && !builder(player))
            || plugin.hub().action(event.getOldCursor()) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if ((inHub(player) && !builder(player)) || plugin.hub().action(event.getMainHandItem()) != null
            || plugin.hub().action(event.getOffHandItem()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        String action = plugin.hub().action(event.getItem());
        if (action == null) {
            if (inHub(player) && !builder(player) && event.getAction() != Action.LEFT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_AIR) {
                event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            }
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        long now = System.currentTimeMillis();
        Long last = lastUse.get(player.getUniqueId());
        if (last != null && now - last < 300) return;
        lastUse.put(player.getUniqueId(), now);
        runAction(player, action);
    }

    public void runAction(Player player, String action) {
        switch (action) {
            case "queue" -> plugin.dialogs().queue(player, QueueMode.RANKED, false);
            case "leave-queue" -> plugin.queue().leave(player, true);
            case "leaderboard" -> plugin.dialogs().leaderboard(player, "overall", null);
            case "profile" -> {
                PlayerProfile own = plugin.profiles().get(player);
                if (own != null) plugin.dialogs().profile(player, own, false);
            }
            case "settings" -> plugin.dialogs().settings(player);
            case "spectate" -> plugin.dialogs().spectate(player);
            case "stop-spectating" -> plugin.spectate().leave(player, true);
            default -> {
                java.util.function.Consumer<Player> extra = plugin.hub().extraAction(action);
                if (extra != null) extra.accept(player);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) return;
        Player player = event.getPlayer();
        if (event.getTo().getY() < plugin.settings().hubVoidY && inHub(player)) {
            player.teleportAsync(plugin.hub().spawn());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent event) {
        if (event.toWeatherState() && plugin.settings().hubLockWeather && plugin.hub().isHubWorld(event.getWorld())) {
            event.setCancelled(true);
        }
    }
}
