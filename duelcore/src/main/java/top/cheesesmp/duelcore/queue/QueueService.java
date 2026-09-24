package top.cheesesmp.duelcore.queue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.MainConfig;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.profile.PlayerProfile;

/** Ranked/unranked queues per kit and the matchmaking tick. Main thread only. */
public final class QueueService implements Listener, Runnable {

    public enum JoinResult { OK, SWITCHED, ALREADY, IN_MATCH, SPECTATING, KIT_DISABLED, MODE_DISABLED, NOT_LOADED, PARTY }

    private record Bucket(String kit, QueueMode mode) {
    }

    private final DuelCorePlugin plugin;
    private final Map<Bucket, List<QueueEntry>> buckets = new HashMap<>();
    private final Map<UUID, List<QueueEntry>> byPlayer = new HashMap<>();
    private final RematchLimiter rematches;
    private Matchmaker matchmaker;
    private MatchPolicy customPolicy;
    private long pairingsMade;
    private int tickCounter;

    public QueueService(DuelCorePlugin plugin) {
        this.plugin = plugin;
        this.rematches = new RematchLimiter(plugin.settings().mmMaxRankedRematchesPerDay);
        reload();
    }

    /** Rebuilds matchmaker settings from config. */
    public void reload() {
        MainConfig c = plugin.settings();
        rematches.limit(c.mmMaxRankedRematchesPerDay);
        MatchPolicy base = new RegionPingPolicy(c.mmRegionEnabled, c.mmRegionPenalty, c.mmPingEnabled,
            c.mmPingPenaltyPerMs, c.mmOverMaxPingPenalty, c.mmRelaxAfterSeconds);
        MatchPolicy policy = customPolicy != null ? customPolicy : base;
        this.matchmaker = new Matchmaker(new Matchmaker.Window(c.mmWindowInitial, c.mmWindowGrowth, c.mmWindowMax),
            policy.and(rematches));
    }

    /** Replaces the latency policy (API hook). Anti-boosting always stays active. */
    public void policy(@Nullable MatchPolicy policy) {
        this.customPolicy = policy;
        reload();
    }

    public Matchmaker matchmaker() {
        return matchmaker;
    }

    public RematchLimiter rematches() {
        return rematches;
    }

    // ------------------------------------------------------------------ join / leave

    public JoinResult join(Player player, Kit kit, QueueMode mode) {
        UUID uuid = player.getUniqueId();
        if (plugin.matches().match(uuid) != null) return JoinResult.IN_MATCH;
        if (!kit.enabled()) return JoinResult.KIT_DISABLED;
        MainConfig c = plugin.settings();
        if (mode == QueueMode.RANKED && (!c.queueRanked || !kit.ranked())) return JoinResult.MODE_DISABLED;
        if (mode == QueueMode.UNRANKED && !c.queueUnranked) return JoinResult.MODE_DISABLED;
        if (mode == QueueMode.PARTY) return JoinResult.PARTY;
        PlayerProfile profile = plugin.profiles().get(player);
        if (profile == null || !plugin.profiles().ready()) return JoinResult.NOT_LOADED;
        List<QueueEntry> current = byPlayer.getOrDefault(uuid, List.of());
        for (QueueEntry e : current) {
            if (e.kit().equals(kit.id()) && e.mode() == mode) return JoinResult.ALREADY;
        }
        boolean switched = false;
        if (!c.queueAllowMultiple && !current.isEmpty()) {
            removeAll(uuid);
            switched = true;
        }
        if (plugin.spectate().spectating(uuid) != null) plugin.spectate().leave(player, false);
        KitStats stats = plugin.profiles().stats(profile, kit.id());
        QueueEntry entry = new QueueEntry(uuid, player.getName(), kit.id(), mode, stats.rating, System.currentTimeMillis(),
            profile.region(), player.getPing(), profile.maxPing(), List.of());
        add(entry);
        plugin.hub().giveItems(player);
        plugin.sidebar().refresh(player);
        return switched ? JoinResult.SWITCHED : JoinResult.OK;
    }

    /** Queues a whole party (leader + members) as one entry for the 2v2 queue. */
    public JoinResult joinParty(Player leader, List<Player> members, Kit kit) {
        for (Player p : members) {
            if (plugin.matches().match(p.getUniqueId()) != null) return JoinResult.IN_MATCH;
        }
        if (!kit.enabled()) return JoinResult.KIT_DISABLED;
        for (Player p : members) removeAll(p.getUniqueId());
        removeAll(leader.getUniqueId());
        List<UUID> others = new ArrayList<>();
        for (Player p : members) if (!p.equals(leader)) others.add(p.getUniqueId());
        QueueEntry entry = new QueueEntry(leader.getUniqueId(), leader.getName(), kit.id(), QueueMode.PARTY, 1000,
            System.currentTimeMillis(), null, leader.getPing(), 0, others);
        add(entry);
        for (Player p : members) {
            byPlayer.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
            if (!p.equals(leader)) byPlayer.get(p.getUniqueId()).add(entry);
            plugin.hub().giveItems(p);
        }
        return JoinResult.OK;
    }

    private void add(QueueEntry entry) {
        buckets.computeIfAbsent(new Bucket(entry.kit(), entry.mode()), k -> new ArrayList<>()).add(entry);
        byPlayer.computeIfAbsent(entry.player(), k -> new ArrayList<>()).add(entry);
    }

    /** Leaves every queue. Returns true if the player was queued. */
    public boolean leave(Player player, boolean notify) {
        boolean was = removeAll(player.getUniqueId());
        if (notify) {
            plugin.messages().send(player, was ? "queue.left" : "queue.not-queued");
        }
        if (was && player.isOnline() && plugin.matches().match(player.getUniqueId()) == null) {
            plugin.hub().giveItems(player);
            plugin.sidebar().refresh(player);
        }
        return was;
    }

    public boolean removeAll(UUID uuid) {
        List<QueueEntry> entries = byPlayer.remove(uuid);
        if (entries == null || entries.isEmpty()) return false;
        for (QueueEntry e : entries) {
            List<QueueEntry> bucket = buckets.get(new Bucket(e.kit(), e.mode()));
            if (bucket != null) bucket.remove(e);
            // a party entry is referenced by every member
            if (e.mode() == QueueMode.PARTY) {
                byPlayer.remove(e.player());
                for (UUID m : e.partyMembers()) byPlayer.remove(m);
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        removeAll(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ queries

    public boolean isQueued(UUID uuid) {
        List<QueueEntry> list = byPlayer.get(uuid);
        return list != null && !list.isEmpty();
    }

    public List<QueueEntry> entries(UUID uuid) {
        return byPlayer.getOrDefault(uuid, List.of());
    }

    public @Nullable QueueEntry firstEntry(UUID uuid) {
        List<QueueEntry> list = byPlayer.get(uuid);
        return list == null || list.isEmpty() ? null : list.getFirst();
    }

    public int size(String kit, QueueMode mode) {
        List<QueueEntry> b = buckets.get(new Bucket(kit, mode));
        if (b == null) return 0;
        int n = 0;
        for (QueueEntry e : b) n += e.size();
        return n;
    }

    public int size(String kit) {
        int n = 0;
        for (QueueMode m : QueueMode.values()) n += size(kit, m);
        return n;
    }

    public int totalQueued() {
        return byPlayer.size();
    }

    public long pairingsMade() {
        return pairingsMade;
    }

    public int bucketCount() {
        int n = 0;
        for (List<QueueEntry> b : buckets.values()) if (!b.isEmpty()) n++;
        return n;
    }

    // ------------------------------------------------------------------ matchmaking

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        if (++tickCounter % 600 == 0) rematches.prune(now);
        for (Map.Entry<Bucket, List<QueueEntry>> e : new ArrayList<>(buckets.entrySet())) {
            List<QueueEntry> bucket = e.getValue();
            // drop stale entries (offline or busy players)
            for (Iterator<QueueEntry> it = bucket.iterator(); it.hasNext(); ) {
                QueueEntry entry = it.next();
                Player p = Bukkit.getPlayer(entry.player());
                if (p == null || plugin.matches().match(entry.player()) != null) {
                    it.remove();
                    byPlayer.remove(entry.player());
                    continue;
                }
                entry.ping(p.getPing());
            }
            if (bucket.size() < 2) continue;
            Kit kit = plugin.kits().get(e.getKey().kit());
            if (kit == null || !kit.enabled()) continue;
            for (Matchmaker.Pair pair : matchmaker.pair(bucket, now)) {
                start(kit, e.getKey().mode(), pair, now);
            }
        }
        if (plugin.settings().queueSearchingActionBar && tickCounter % 2 == 0) {
            for (Map.Entry<UUID, List<QueueEntry>> e : byPlayer.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                Player p = Bukkit.getPlayer(e.getKey());
                QueueEntry q = e.getValue().getFirst();
                Kit kit = plugin.kits().get(q.kit());
                if (p == null || kit == null) continue;
                double range = matchmaker.windowFor(q, now);
                plugin.messages().actionBar(p, "queue.searching",
                    Messages.comp("kit_icon", kit.sprite()), Messages.comp("kit", kit.displayName()),
                    Messages.text("mode", plugin.messages().raw("mode." + q.mode().id())),
                    Messages.text("wait", formatWait(q.waitSeconds(now))),
                    Messages.text("range", Double.isInfinite(range) ? "∞" : String.valueOf((int) range)));
            }
        }
    }

    private void start(Kit kit, QueueMode mode, Matchmaker.Pair pair, long now) {
        QueueEntry a = pair.a();
        QueueEntry b = pair.b();
        removeAll(a.player());
        removeAll(b.player());
        if (mode == QueueMode.PARTY) {
            removeAll(b.player());
        }
        pairingsMade++;
        if (plugin.settings().mmLogPairings || plugin.settings().verbose) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                "[matchmaker] %s %s: %s (%.0f, waited %.1fs, window %.0f) vs %s (%.0f, waited %.1fs, window %.0f), gap %.0f",
                kit.id(), mode.id(), a.name(), a.rating(), a.waitSeconds(now), pair.windowA(), b.name(), b.rating(),
                b.waitSeconds(now), pair.windowB(), pair.ratingGap()));
        }
        List<Player> teamA = players(a);
        List<Player> teamB = players(b);
        if (teamA.isEmpty() || teamB.isEmpty()) return;
        boolean ranked = mode == QueueMode.RANKED;
        if (ranked) rematches.record(a.player(), b.player(), now);
        plugin.matches().create(List.of(teamA, teamB), kit, ranked,
            mode == QueueMode.PARTY ? Match.Origin.PARTY : Match.Origin.QUEUE);
    }

    private static List<Player> players(QueueEntry entry) {
        List<Player> list = new ArrayList<>();
        Player leader = Bukkit.getPlayer(entry.player());
        if (leader != null) list.add(leader);
        for (UUID m : entry.partyMembers()) {
            Player p = Bukkit.getPlayer(m);
            if (p != null) list.add(p);
        }
        return list;
    }

    public static String formatWait(double seconds) {
        int s = (int) seconds;
        return s < 60 ? s + "s" : (s / 60) + "m " + (s % 60) + "s";
    }
}
