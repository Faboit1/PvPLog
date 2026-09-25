package top.cheesesmp.duelcore.kit.editor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.db.Dialect;
import top.cheesesmp.duelcore.db.OrderedWrites;
import top.cheesesmp.duelcore.db.dao.KitLayoutDao;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.kit.KitManager;
import top.cheesesmp.duelcore.profile.PlayerProfile;

/**
 * Players' own kit layouts (dc_kit_layouts): loaded async on join and cached while the player is online, dropped on
 * quit, saved async in order. A layout remembers the fingerprint of the kit it was made for; when an admin changes the
 * kit's items it no longer fits, is deleted and the player is told once. Main thread only.
 */
public final class KitLayouts implements Listener {

    /** A saved layout: the arrangement and the {@link KitLayout#hash} of the kit it was made for. */
    record Saved(int[] map, int kitHash) {
    }

    /** A failed load: no new attempt before {@code after} (epoch ms); {@code failures} in a row so far. */
    record Retry(long after, int failures) {
    }

    /** Wait after the first failed load; it doubles with every further failure up to {@link #MAX_BACKOFF_MS}. */
    static final long FIRST_BACKOFF_MS = 30_000;
    static final long MAX_BACKOFF_MS = 300_000;

    /** How long to wait after {@code failures} failed loads in a row (30 s, 60 s, 120 s, 240 s, then 5 min). */
    static long backoff(int failures) {
        int doublings = Math.clamp(failures - 1, 0, 10);
        return Math.min(MAX_BACKOFF_MS, FIRST_BACKOFF_MS << doublings);
    }

    private final DuelCorePlugin plugin;
    private final Map<UUID, Map<String, Saved>> cache = new HashMap<>();
    private final Map<UUID, CompletableFuture<Void>> loading = new HashMap<>();
    /** Players whose last load failed: when to try again (backing off) and how many loads failed in a row. */
    private final Map<UUID, Retry> retries = new HashMap<>();
    private @Nullable OrderedWrites ordered;
    private @Nullable BukkitTask loader;

    public KitLayouts(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    void enable() {
        // loads, saves and deletes in the order they were made, also on the MySQL pool (a quick save then a join
        // must see the save)
        ordered = new OrderedWrites(plugin.database()::submit);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // players who were online when the plugin enabled, or whose profile wasn't there yet at join
        loader = Bukkit.getScheduler().runTaskTimer(plugin, this::loadMissing, 40L, 40L);
    }

    void disable() {
        if (loader != null) loader.cancel();
        cache.clear();
        loading.clear();
        retries.clear();
    }

    // ------------------------------------------------------------------ loading

    /** True once the player's layouts are in memory. */
    public boolean loaded(UUID uuid) {
        return cache.containsKey(uuid);
    }

    /** True while the player's layouts are being loaded, or can't be loaded (the last load failed). */
    public boolean pending(UUID uuid) {
        return !cache.containsKey(uuid) && (loading.containsKey(uuid) || retries.containsKey(uuid));
    }

    /**
     * Loads the player's layouts once (async); the future completes on the main thread. Stale layouts (the kit
     * changed) are dropped with a message. Completes right away when they are loaded already, and (without loading)
     * while a failed load is backing off.
     */
    public CompletableFuture<Void> load(Player player) {
        UUID uuid = player.getUniqueId();
        if (cache.containsKey(uuid)) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> running = loading.get(uuid);
        if (running != null) return running;
        Retry retry = retries.get(uuid);
        if (retry != null && System.currentTimeMillis() < retry.after()) return CompletableFuture.completedFuture(null);
        PlayerProfile profile = plugin.profiles().get(player);
        OrderedWrites writes = ordered;
        if (profile == null || writes == null || !plugin.profiles().ready()) return CompletableFuture.completedFuture(null);
        int playerId = profile.id();
        Map<Integer, String> keys = plugin.profiles().kitKeys();
        CompletableFuture<Void> result = new CompletableFuture<>();
        loading.put(uuid, result);
        writes.submit(c -> KitLayoutDao.load(c, playerId)).whenComplete((rows, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                loading.remove(uuid, result);
                if (error != null) {
                    failed(player, error);
                } else if (player.isOnline() && plugin.profiles().get(uuid) == profile) {
                    retries.remove(uuid);
                    Map<String, Saved> mine = new HashMap<>();
                    for (KitLayoutDao.Row row : rows) {
                        String key = keys.get(row.kitId());
                        int[] map = KitLayout.decode(row.layout());
                        if (key != null && map != null) mine.put(key, new Saved(map, row.kitHash()));
                    }
                    cache.put(uuid, mine);
                    dropStale(player);
                }
                result.complete(null);
            });
        });
        return result;
    }

    /**
     * A load failed (the database is down, the table broken): back off before the next attempt, and log the stack
     * trace only for the first failure in a row (the next ones as one line) so an outage doesn't flood the console.
     */
    private void failed(Player player, Throwable error) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline()) return; // (onQuit forgot them already)
        Retry last = retries.get(uuid);
        int failures = last == null ? 1 : last.failures() + 1;
        long wait = backoff(failures);
        retries.put(uuid, new Retry(System.currentTimeMillis() + wait, failures));
        String what = "Could not load the kit layouts of " + player.getName() + " (attempt " + failures
            + ", next in " + wait / 1000 + " s)";
        if (failures == 1) plugin.getLogger().log(Level.WARNING, what, error);
        else plugin.getLogger().warning(what + ": " + error);
    }

    private void loadMissing() {
        if (!plugin.profiles().ready()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();
            // (load() itself skips players whose failed load is still backing off)
            if (!cache.containsKey(uuid) && !loading.containsKey(uuid) && plugin.profiles().get(p) != null) load(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cache.remove(event.getPlayer().getUniqueId());
        loading.remove(event.getPlayer().getUniqueId());
        retries.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ reading

    /**
     * The player's layout for this kit (a copy), or null for the default: none saved, not loaded yet, or stale. A
     * layout made for a different version of the kit is deleted and the player is told, unless it fits the kit as it
     * is loaded now and only this (running match's) copy is older.
     */
    public int @Nullable [] layout(Player player, Kit kit) {
        Map<String, Saved> mine = cache.get(player.getUniqueId());
        if (mine == null) {
            load(player); // next time
            return null;
        }
        Saved saved = mine.get(kit.id());
        if (saved == null) return null;
        if (fits(saved, kit)) return saved.map().clone();
        Kit current = plugin.kits().get(kit.id());
        if (current != null && current != kit && fits(saved, current)) return null;
        drop(player, kit);
        return null;
    }

    /** True when the player has a working layout of their own for the kit as it is now (the picker's marker). */
    public boolean custom(UUID uuid, Kit kit) {
        Map<String, Saved> mine = cache.get(uuid);
        Saved saved = mine == null ? null : mine.get(kit.id());
        return saved != null && fits(saved, kit);
    }

    private static boolean fits(Saved saved, Kit kit) {
        String[] parts = KitManager.fingerprint(kit);
        return saved.kitHash() == KitLayout.hash(parts) && KitLayout.valid(saved.map(), KitLayout.filled(parts));
    }

    /** Drops every layout of the player that no longer fits its (current) kit, telling them once per kit. */
    void dropStale(Player player) {
        Map<String, Saved> mine = cache.get(player.getUniqueId());
        if (mine == null) return;
        for (String key : java.util.List.copyOf(mine.keySet())) {
            Kit kit = plugin.kits().get(key);
            if (kit != null && !fits(mine.get(key), kit)) drop(player, kit);
        }
    }

    private void drop(Player player, Kit kit) {
        Map<String, Saved> mine = cache.get(player.getUniqueId());
        if (mine == null || mine.remove(kit.id()) == null) return;
        delete(player, kit);
        plugin.messages().send(player, "kit-editor.layout-reset", Messages.comp("kit", kit.displayName()),
            Messages.comp("kit_icon", kit.sprite()));
    }

    // ------------------------------------------------------------------ writing

    /**
     * Saves the player's layout for the kit (async). The default arrangement deletes the saved row instead. Returns
     * false (nothing saved) when the layout isn't a complete permutation of the kit's items, or the player's
     * layouts or the kit's database id aren't there.
     */
    public boolean save(Player player, Kit kit, int[] map) {
        String[] parts = KitManager.fingerprint(kit);
        boolean[] filled = KitLayout.filled(parts);
        Map<String, Saved> mine = cache.get(player.getUniqueId());
        PlayerProfile profile = plugin.profiles().get(player);
        OrderedWrites writes = ordered;
        if (mine == null || profile == null || writes == null || !KitLayout.valid(map, filled)) return false;
        int kitId;
        try {
            kitId = plugin.profiles().kitId(kit.id());
        } catch (IllegalStateException e) {
            return false;
        }
        int playerId = profile.id();
        if (KitLayout.isIdentity(map, filled)) {
            mine.remove(kit.id());
            delete(player, kit);
            return true;
        }
        int hash = KitLayout.hash(parts);
        mine.put(kit.id(), new Saved(map.clone(), hash));
        String encoded = KitLayout.encode(map);
        Dialect dialect = plugin.database().dialect();
        long now = System.currentTimeMillis();
        writes.submit(c -> {
            KitLayoutDao.save(c, dialect, playerId, kitId, encoded, hash, now);
            return null;
        }).exceptionally(e -> {
            plugin.getLogger().log(Level.WARNING, "Could not save the " + kit.id() + " layout of " + player.getName(), e);
            return null;
        });
        return true;
    }

    /** Forgets the player's layout for the kit (async). Returns true when they had one. */
    public boolean clear(Player player, Kit kit) {
        Map<String, Saved> mine = cache.get(player.getUniqueId());
        boolean had = mine != null && mine.remove(kit.id()) != null;
        if (had) delete(player, kit);
        return had;
    }

    private void delete(Player player, Kit kit) {
        PlayerProfile profile = plugin.profiles().get(player);
        OrderedWrites writes = ordered;
        if (profile == null || writes == null) return;
        int kitId;
        try {
            kitId = plugin.profiles().kitId(kit.id());
        } catch (IllegalStateException e) {
            return;
        }
        int playerId = profile.id();
        writes.submit(c -> {
            KitLayoutDao.delete(c, playerId, kitId);
            return null;
        }).exceptionally(e -> {
            plugin.getLogger().log(Level.WARNING, "Could not delete the " + kit.id() + " layout of " + player.getName(), e);
            return null;
        });
    }

    /** Players with layouts in memory (for /duelcore debug style checks and tests). */
    public int cacheSize() {
        return cache.size();
    }
}
