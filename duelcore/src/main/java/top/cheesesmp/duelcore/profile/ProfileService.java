package top.cheesesmp.duelcore.profile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.db.Database;
import top.cheesesmp.duelcore.db.Migrations;
import top.cheesesmp.duelcore.db.dao.MatchDao;
import top.cheesesmp.duelcore.db.dao.MetaDao;
import top.cheesesmp.duelcore.db.dao.PlayerDao;
import top.cheesesmp.duelcore.db.dao.RatingDao;
import top.cheesesmp.duelcore.rating.Tier;
import top.cheesesmp.duelcore.rating.TierService;

/**
 * Player data cache + all persistence of player data. Profiles of online players live in memory; every read or
 * write of the database runs on the DB executor.
 */
public final class ProfileService implements Listener {

    /** A rating row to persist, detached from the live profile. */
    public record RatingWrite(int playerId, String kit, KitStats snapshot, int points, @Nullable Tier overall) {
    }

    private final DuelCorePlugin plugin;
    private final Database db;
    private final Map<UUID, PlayerProfile> online = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<?>> pendingWrites = new ConcurrentHashMap<>();
    /** Profiles loaded at pre-login whose join has not happened yet (login may still fail). */
    private final Map<UUID, Long> awaitingJoin = new ConcurrentHashMap<>();
    private volatile Map<String, Integer> kitIds = Map.of();
    private volatile Map<Integer, String> kitKeys = Map.of();
    private volatile MetaDao.Season season;
    private volatile boolean ready;
    private volatile CompletableFuture<Void> started = CompletableFuture.completedFuture(null);

    public ProfileService(DuelCorePlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    /** Migrates the schema, maps kit ids and loads the current season. */
    public CompletableFuture<Void> start(Collection<String> kits, String firstSeasonName) {
        this.started = db.submit(c -> {
            int version = Migrations.migrate(c, db.dialect());
            Map<String, Integer> ids = MetaDao.syncKits(c, kits);
            MetaDao.Season s = MetaDao.currentSeason(c, firstSeasonName, System.currentTimeMillis());
            applyKitIds(ids);
            this.season = s;
            this.ready = true;
            plugin.getLogger().info("Database ready (schema v" + version + ", " + db.dialect() + "), season "
                + s.id() + " \"" + s.name() + "\"");
            return null;
        });
        return started;
    }

    public CompletableFuture<Void> syncKits(Collection<String> kits) {
        return db.submit(c -> {
            applyKitIds(MetaDao.syncKits(c, kits));
            return null;
        });
    }

    private void applyKitIds(Map<String, Integer> ids) {
        Map<Integer, String> keys = new HashMap<>();
        ids.forEach((k, v) -> keys.put(v, k));
        this.kitIds = Map.copyOf(ids);
        this.kitKeys = Map.copyOf(keys);
    }

    public boolean ready() {
        return ready;
    }

    public MetaDao.Season season() {
        return season;
    }

    public int kitId(String kit) {
        Integer id = kitIds.get(kit);
        if (id == null) throw new IllegalStateException("kit '" + kit + "' has no database id yet");
        return id;
    }

    public Map<Integer, String> kitKeys() {
        return kitKeys;
    }

    // ---------------------------------------------------------------- cache

    public @Nullable PlayerProfile get(UUID uuid) {
        return online.get(uuid);
    }

    public @Nullable PlayerProfile get(Player player) {
        return online.get(player.getUniqueId());
    }

    public Collection<PlayerProfile> online() {
        return online.values();
    }

    public int cacheSize() {
        return online.size();
    }

    public KitStats stats(PlayerProfile profile, String kit) {
        var cfg = plugin.settings();
        boolean glicko = "glicko2".equals(cfg.ratingSystem);
        return profile.statsOrCreate(kit, cfg.ratingDefault, glicko ? cfg.glickoDefaultRd : 350,
            cfg.glickoDefaultVolatility);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        UUID uuid = event.getUniqueId();
        try {
            started.get(15, TimeUnit.SECONDS);
            CompletableFuture<?> pending = pendingWrites.get(uuid);
            if (pending != null) pending.get(10, TimeUnit.SECONDS);
            PlayerProfile profile = load(uuid, event.getName()).get(10, TimeUnit.SECONDS);
            awaitingJoin.put(uuid, System.currentTimeMillis());
            online.put(uuid, profile);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not load DuelCore profile of " + event.getName(), e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text("Your duel profile could not be loaded. Please try again in a moment."));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        awaitingJoin.remove(event.getPlayer().getUniqueId());
    }

    /** Drops profiles whose login never completed (kicked after pre-login, connection lost). */
    public void sweep() {
        long cutoff = System.currentTimeMillis() - 60_000;
        awaitingJoin.entrySet().removeIf(e -> {
            if (e.getValue() > cutoff) return false;
            if (Bukkit.getPlayer(e.getKey()) == null) online.remove(e.getKey());
            return true;
        });
        online.keySet().removeIf(uuid -> !awaitingJoin.containsKey(uuid) && Bukkit.getPlayer(uuid) == null);
    }

    /** Last in the quit chain: matches and queues are handled first by their own (lower priority) listeners. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        PlayerProfile profile = online.remove(uuid);
        if (profile != null) {
            int id = profile.id();
            track(uuid, db.submit(c -> {
                PlayerDao.touch(c, id, System.currentTimeMillis());
                return null;
            }));
        }
    }

    private CompletableFuture<PlayerProfile> load(UUID uuid, String name) {
        TierService tiers = plugin.tiers();
        return db.submit(c -> {
            PlayerDao.PlayerRow row = PlayerDao.loadOrCreate(c, uuid, name, System.currentTimeMillis(), Setting.defaults());
            Map<String, KitStats> stats = byKey(RatingDao.load(c, season.id(), row.id()));
            PlayerProfile profile = new PlayerProfile(row.id(), uuid, row.name(), row.settings(), row.region(),
                row.country(), row.maxPing(), stats);
            tiers.refresh(profile);
            return profile;
        });
    }

    /** Called on join for players that were already online when the plugin enabled (e.g. reload). */
    public CompletableFuture<PlayerProfile> loadOnline(Player player) {
        return load(player.getUniqueId(), player.getName()).thenApply(p -> {
            online.put(player.getUniqueId(), p);
            return p;
        });
    }

    private Map<String, KitStats> byKey(Map<Integer, KitStats> byId) {
        Map<String, KitStats> out = new HashMap<>();
        Map<Integer, String> keys = kitKeys;
        byId.forEach((id, s) -> {
            String key = keys.get(id);
            if (key != null) out.put(key, s);
        });
        return out;
    }

    /**
     * Finds a profile by name: online players are returned from the cache, others are loaded (detached, not
     * cached). When {@code seasonId} is given, stats of that season are loaded instead (legacy view).
     */
    public CompletableFuture<Optional<PlayerProfile>> lookup(String name, @Nullable Integer seasonId) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null && seasonId == null) {
            PlayerProfile p = get(online);
            if (p != null) return CompletableFuture.completedFuture(Optional.of(p));
        }
        TierService tiers = plugin.tiers();
        int season = seasonId == null ? this.season.id() : seasonId;
        return db.submit(c -> {
            Optional<PlayerDao.PlayerRow> row = PlayerDao.findByName(c, name);
            if (row.isEmpty()) return Optional.<PlayerProfile>empty();
            PlayerDao.PlayerRow r = row.get();
            PlayerProfile p = new PlayerProfile(r.id(), r.uuid(), r.name(), r.settings(), r.region(), r.country(),
                r.maxPing(), byKey(RatingDao.load(c, season, r.id())));
            tiers.refresh(p);
            return Optional.of(p);
        });
    }

    public CompletableFuture<List<MatchDao.HistoryEntry>> history(PlayerProfile profile, int limit) {
        Map<Integer, String> keys = kitKeys;
        int id = profile.id();
        return db.submit(c -> MatchDao.history(c, id, limit, keys));
    }

    public CompletableFuture<MetaDao.@Nullable Season> previousSeason() {
        int current = season.id();
        return db.submit(c -> MetaDao.previousSeason(c, current));
    }

    // ---------------------------------------------------------------- writes

    public void saveSettings(PlayerProfile profile) {
        int id = profile.id();
        int bits = profile.settingsBits();
        String region = profile.region();
        String country = profile.country();
        int maxPing = profile.maxPing();
        track(profile.uuid(), db.submit(c -> {
            PlayerDao.saveSettings(c, id, bits, region, country, maxPing);
            return null;
        }));
    }

    /** Persists a match and the rating rows in one transaction. */
    public CompletableFuture<Long> persistMatch(MatchDao.@Nullable MatchRecord record, List<RatingWrite> ratings,
                                                Collection<UUID> players) {
        int seasonId = season.id();
        Map<String, Integer> ids = kitIds;
        CompletableFuture<Long> future = db.transaction(c -> {
            long matchId = record == null ? -1 : MatchDao.insert(c, record);
            for (RatingWrite w : ratings) {
                Integer kitId = ids.get(w.kit());
                if (kitId == null) continue;
                RatingDao.upsert(c, db.dialect(), seasonId, w.playerId(), kitId, w.snapshot());
                RatingDao.upsertStanding(c, db.dialect(), seasonId, w.playerId(), w.points(), w.overall());
            }
            return matchId;
        });
        for (UUID uuid : players) track(uuid, future);
        return future;
    }

    /** Persists one rating row (tier overrides, admin rating edits). */
    public CompletableFuture<Void> persistRating(UUID uuid, RatingWrite write) {
        return persistMatch(null, List.of(write), List.of(uuid)).thenApply(x -> null);
    }

    private void track(UUID uuid, CompletableFuture<?> future) {
        pendingWrites.put(uuid, future);
        future.whenComplete((r, e) -> pendingWrites.remove(uuid, future));
    }

    public int pendingWrites() {
        return pendingWrites.size();
    }

    // ---------------------------------------------------------------- seasons

    /** Ends the current season (archived as legacy) and starts a fresh one. Online caches are cleared. */
    public CompletableFuture<MetaDao.Season> startNewSeason(String name) {
        return db.transaction(c -> MetaDao.startNewSeason(c, name, System.currentTimeMillis()))
            .thenApply(s -> {
                this.season = s;
                return s;
            });
    }

    /** Rebuilds dc_standings for the current season with the current tier configuration. */
    public CompletableFuture<Integer> recalcStandings() {
        TierService tiers = plugin.tiers();
        int seasonId = season.id();
        Map<Integer, String> keys = kitKeys;
        return db.transaction(c -> {
            Map<Integer, Map<String, KitStats>> byPlayer = new HashMap<>();
            RatingDao.forEach(c, seasonId, (ids, stats) -> {
                String kit = keys.get(ids[1]);
                if (kit != null) byPlayer.computeIfAbsent(ids[0], k -> new HashMap<>()).put(kit, stats);
            });
            for (Map.Entry<Integer, Map<String, KitStats>> e : byPlayer.entrySet()) {
                PlayerProfile tmp = new PlayerProfile(e.getKey(), new UUID(0, 0), "", 0, null, null, 0, e.getValue());
                tiers.refresh(tmp);
                RatingDao.upsertStanding(c, db.dialect(), seasonId, e.getKey(), tmp.points(), tmp.overall());
            }
            return byPlayer.size();
        });
    }

    /** Reloads the stats of every online player (after a season change or recalc). */
    public CompletableFuture<Void> reloadOnline() {
        List<CompletableFuture<?>> all = new ArrayList<>();
        for (PlayerProfile p : online.values()) {
            int id = p.id();
            int seasonId = season.id();
            all.add(db.submit(c -> byKey(RatingDao.load(c, seasonId, id))).thenAccept(stats ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    p.clearStats();
                    p.allStats().putAll(stats);
                    plugin.tiers().refresh(p);
                })));
        }
        return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new));
    }
}
