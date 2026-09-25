package top.cheesesmp.duelcore.queue;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.db.Dialect;
import top.cheesesmp.duelcore.db.OrderedWrites;
import top.cheesesmp.duelcore.db.dao.FavoriteDao;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.profile.PlayerProfile;

/**
 * Per-player queue menu state: favourite kits (dc_favorites, loaded on first use and cached while online) and the
 * last open tab. Main thread only; forgotten on quit.
 */
public final class QueuePrefs {

    private final DuelCorePlugin plugin;
    private final Map<UUID, Set<String>> favorites = new HashMap<>();
    private final Map<UUID, CompletableFuture<Set<String>>> loading = new HashMap<>();
    private final Map<UUID, String> tabs = new HashMap<>();
    /** Stars, unstars and loads in the order they were made, also on the MySQL pool (a quick rejoin sees the last toggle). */
    private final OrderedWrites ordered;

    QueuePrefs(DuelCorePlugin plugin) {
        this.plugin = plugin;
        this.ordered = new OrderedWrites(plugin.database()::submit);
    }

    /** Favourite kit ids of an online player, or null while they are not loaded yet. */
    public @Nullable Set<String> favorites(UUID uuid) {
        return favorites.get(uuid);
    }

    /** Loads the favourites once (async); the future completes on the main thread. */
    public CompletableFuture<Set<String>> load(Player player) {
        UUID uuid = player.getUniqueId();
        Set<String> cached = favorites.get(uuid);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        CompletableFuture<Set<String>> running = loading.get(uuid);
        if (running != null) return running;
        PlayerProfile profile = plugin.profiles().get(player);
        if (profile == null || !plugin.profiles().ready()) return CompletableFuture.completedFuture(Set.of());
        int playerId = profile.id();
        Map<Integer, String> keys = plugin.profiles().kitKeys();
        CompletableFuture<Set<String>> result = new CompletableFuture<>();
        loading.put(uuid, result);
        ordered.submit(c -> FavoriteDao.load(c, playerId)).whenComplete((ids, error) ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                loading.remove(uuid, result);
                Set<String> set = new LinkedHashSet<>();
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING, "Could not load favourite kits of " + player.getName(), error);
                } else {
                    for (int id : ids) {
                        String key = keys.get(id);
                        if (key != null) set.add(key);
                    }
                }
                if (error == null && player.isOnline() && plugin.profiles().get(uuid) == profile) favorites.put(uuid, set);
                result.complete(set);
            }));
        return result;
    }

    /** Stars or unstars a kit (saved async). Returns true when the kit is a favourite now. */
    public boolean toggle(Player player, Kit kit) {
        PlayerProfile profile = plugin.profiles().get(player);
        Set<String> set = favorites.get(player.getUniqueId());
        if (profile == null || set == null) return false;
        boolean starred = !set.remove(kit.id());
        if (starred) set.add(kit.id());
        int kitId;
        try {
            kitId = plugin.profiles().kitId(kit.id());
        } catch (IllegalStateException e) {
            return starred; // kit has no database id yet: keep it for this session only
        }
        int playerId = profile.id();
        Dialect dialect = plugin.database().dialect();
        ordered.submit(c -> {
            if (starred) FavoriteDao.add(c, dialect, playerId, kitId);
            else FavoriteDao.remove(c, playerId, kitId);
            return null;
        }).exceptionally(e -> {
            plugin.getLogger().log(Level.WARNING, "Could not save favourite kit " + kit.id() + " of " + player.getName(), e);
            return null;
        });
        return starred;
    }

    /** The last tab the player looked at (a category id or "favorites"), or null. */
    public @Nullable String tab(UUID uuid) {
        return tabs.get(uuid);
    }

    public void tab(UUID uuid, String tab) {
        tabs.put(uuid, tab);
    }

    void forget(UUID uuid) {
        favorites.remove(uuid);
        loading.remove(uuid);
        tabs.remove(uuid);
    }
}
