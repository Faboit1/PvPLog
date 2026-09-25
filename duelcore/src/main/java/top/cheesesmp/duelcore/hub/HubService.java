package top.cheesesmp.duelcore.hub;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.GuiConfig;
import top.cheesesmp.duelcore.kit.KitManager;

/** The lobby: spawn point, locked hotbar, fixed time and weather. Protection lives in {@link HubListener}. */
public final class HubService {

    private final DuelCorePlugin plugin;
    private final NamespacedKey itemKey;
    private @Nullable Location spawn;

    public HubService(DuelCorePlugin plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "hub_item");
    }

    public NamespacedKey itemKey() {
        return itemKey;
    }

    /** Resolves the hub world and spawn, builds a starter platform if configured, applies time and weather. */
    public void enable() {
        World world = world();
        YamlConfiguration cfg = readConfig();
        if (cfg.isConfigurationSection("hub.spawn")) {
            World w = Bukkit.getWorld(cfg.getString("hub.spawn.world", world.getName()));
            spawn = new Location(w == null ? world : w, cfg.getDouble("hub.spawn.x"), cfg.getDouble("hub.spawn.y"),
                cfg.getDouble("hub.spawn.z"), (float) cfg.getDouble("hub.spawn.yaw"), (float) cfg.getDouble("hub.spawn.pitch"));
        } else {
            spawn = new Location(world, 0.5, 100, 0.5, 0, 0);
            if (plugin.settings().hubGeneratePlatform && world.getBlockAt(0, 99, 0).getType().isAir()) {
                buildPlatform(world);
                plugin.getLogger().info("No hub spawn set: built a starter platform at 0 100 0 in " + world.getName()
                    + ". Use /duelcore sethub to change it.");
            }
        }
        applyWorldRules(spawn.getWorld());
    }

    public void applyWorldRules(World world) {
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setTime(plugin.settings().hubTime);
        if (plugin.settings().hubLockWeather) {
            world.setGameRule(GameRules.ADVANCE_WEATHER, false);
            world.setStorm(false);
            world.setThundering(false);
            world.setClearWeatherDuration(Integer.MAX_VALUE);
        }
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.SPAWN_MONSTERS, false);
        world.setGameRule(GameRules.SHOW_DEATH_MESSAGES, false);
        world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRules.LOCATOR_BAR, false);
    }

    private void buildPlatform(World world) {
        int r = 9;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > r + 0.3) continue;
                Material m = d > r - 1 ? Material.POLISHED_ANDESITE : (x + z) % 2 == 0 ? Material.SMOOTH_STONE : Material.SMOOTH_QUARTZ;
                world.getBlockAt(x, 99, z).setType(m, false);
            }
        }
        world.getBlockAt(0, 99, 0).setType(Material.SEA_LANTERN, false);
    }

    public World world() {
        if (spawn != null && spawn.isWorldLoaded()) return spawn.getWorld();
        World w = Bukkit.getWorld(plugin.settings().hubWorld);
        return w != null ? w : Bukkit.getWorlds().getFirst();
    }

    public Location spawn() {
        if (spawn == null || !spawn.isWorldLoaded()) return world().getSpawnLocation();
        return spawn.clone();
    }

    public boolean isHubWorld(World world) {
        return world.equals(world());
    }

    public void setSpawn(Location location) {
        this.spawn = location.clone();
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration cfg = readConfig();
        cfg.set("hub.spawn.world", location.getWorld().getName());
        cfg.set("hub.spawn.x", location.getX());
        cfg.set("hub.spawn.y", location.getY());
        cfg.set("hub.spawn.z", location.getZ());
        cfg.set("hub.spawn.yaw", (double) location.getYaw());
        cfg.set("hub.spawn.pitch", (double) location.getPitch());
        try {
            cfg.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not save hub spawn: " + e.getMessage());
        }
        applyWorldRules(location.getWorld());
    }

    private YamlConfiguration readConfig() {
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.load(new File(plugin.getDataFolder(), "config.yml"));
        } catch (Exception ignored) {
            // defaults
        }
        return cfg;
    }

    /** Sends a player to the hub in a clean state with the hub hotbar. */
    public void send(Player player) {
        prepare(player);
        player.teleportAsync(spawn()).thenRun(() -> {
            if (!player.isOnline()) return;
            player.setWorldBorder(null);
            plugin.sidebar().refresh(player);
            plugin.results().showPending(player);
        });
    }

    /** Resets state and gives hub items without teleporting. */
    public void prepare(Player player) {
        KitManager.resetState(player, 20);
        plugin.matches().unfreeze(player);
        player.setGameMode(GameMode.ADVENTURE);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        giveItems(player);
        plugin.visibility().refresh(player);
        plugin.tags().update(player);
    }

    /** Hub items added by features (party, friends, …): gui.yml key → what a right click does. */
    private final java.util.Map<String, java.util.function.Consumer<Player>> extraItems = new java.util.LinkedHashMap<>();

    /**
     * Adds a hub hotbar item: it is given with the others whenever gui.yml has a {@code hotbar.<key>} entry, and a
     * right click runs {@code onUse}.
     */
    public void registerItem(String key, java.util.function.Consumer<Player> onUse) {
        extraItems.put(key, onUse);
    }

    /** The action of a feature-registered hub item, or null. */
    public java.util.function.@Nullable Consumer<Player> extraAction(String key) {
        return extraItems.get(key);
    }

    /** Hub hotbar, reflecting queue state. */
    public void giveItems(Player player) {
        player.getInventory().clear();
        GuiConfig gui = plugin.gui();
        boolean queued = plugin.queue().isQueued(player.getUniqueId());
        List<String> keys = new ArrayList<>(List.of("leaderboard", "profile", "settings", "spectate"));
        keys.addFirst(queued ? "leave-queue" : "queue");
        keys.addAll(extraItems.keySet());
        for (String key : keys) {
            GuiConfig.HotbarItem def = gui.item(key);
            if (def == null) continue;
            player.getInventory().setItem(def.slot(), build(player, key, def));
        }
        player.getInventory().setHeldItemSlot(0);
    }

    public void giveSpectatorItems(Player player) {
        player.getInventory().clear();
        GuiConfig.HotbarItem def = plugin.gui().item("stop-spectating");
        if (def != null) player.getInventory().setItem(def.slot(), build(player, "stop-spectating", def));
    }

    public ItemStack build(Player player, String action, GuiConfig.HotbarItem def) {
        ItemStack stack = ItemStack.of(def.material());
        ItemMeta meta = stack.getItemMeta();
        meta.itemName(plugin.messages().parse(def.name()));
        List<Component> lore = new ArrayList<>();
        for (String line : def.lore()) lore.add(plugin.messages().parse(line).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, action);
        if (meta instanceof SkullMeta skull) skull.setPlayerProfile(player.getPlayerProfile());
        stack.setItemMeta(meta);
        return stack;
    }

    /** The hub action of an item, or null. */
    public @Nullable String action(@Nullable ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }
}
