package top.cheesesmp.duelcore.queue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.api.event.MatchStartEvent;
import top.cheesesmp.duelcore.config.MainConfig;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.match.Participant;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;

/**
 * Queues per kit and the matchmaking tick. A player can search in several kit queues at once
 * ({@code queue.allow-multiple}); being matched in one takes them out of all others. Main thread only.
 */
public final class QueueService implements Listener, Runnable {

    public enum JoinResult { OK, SWITCHED, ALREADY, IN_MATCH, SPECTATING, KIT_DISABLED, MODE_DISABLED, NOT_LOADED, PARTY }

    private record Bucket(String kit, QueueMode mode) {
    }

    private final DuelCorePlugin plugin;
    private final Map<Bucket, List<QueueEntry>> buckets = new HashMap<>();
    private final Map<UUID, List<QueueEntry>> byPlayer = new HashMap<>();
    /**
     * The solo queues each player chose. Unlike {@link #byPlayer} this survives the removal when a match starts, so
     * Keep Queuing can re-join them after it; only leaving on purpose (or quitting) forgets them.
     */
    private final Map<UUID, List<Bucket>> chosen = new HashMap<>();
    private final QueuePrefs prefs;
    private final RematchLimiter rematches;
    private final SearchingFeedback searching;
    private Matchmaker matchmaker;
    private MatchPolicy customPolicy;
    private long pairingsMade;
    private int tickCounter;

    public QueueService(DuelCorePlugin plugin) {
        this.plugin = plugin;
        this.rematches = new RematchLimiter(plugin.settings().mmMaxRankedRematchesPerDay);
        this.prefs = new QueuePrefs(plugin);
        this.searching = new SearchingFeedback(plugin);
        reload();
        // the searching action bar and boss bar (Bukkit cancels the timer on disable, the boss bars end with plugin.anim())
        plugin.getServer().getScheduler().runTaskTimer(plugin, searching, 20L, 2L);
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

    /** The searching action bar and boss bar. */
    public SearchingFeedback searching() {
        return searching;
    }

    /** Favourite kits and the last queue menu tab of each player. */
    public QueuePrefs prefs() {
        return prefs;
    }

    /**
     * The queue a kit is played in from the menu: ranked, or unranked for kits with {@code ranked: false} when the
     * unranked queue is open. Null when neither is available.
     */
    public @Nullable QueueMode modeFor(Kit kit) {
        MainConfig c = plugin.settings();
        if (c.queueRanked && kit.ranked()) return QueueMode.RANKED;
        if (c.queueUnranked) return QueueMode.UNRANKED;
        return null;
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
            chosen.remove(uuid);
            switched = true;
        }
        if (plugin.spectate().spectating(uuid) != null) plugin.spectate().leave(player, false);
        KitStats stats = plugin.profiles().stats(profile, kit.id());
        QueueEntry entry = new QueueEntry(uuid, player.getName(), kit.id(), mode, stats.rating, System.currentTimeMillis(),
            profile.region(), player.getPing(), profile.maxPing(), List.of());
        add(entry);
        List<Bucket> kits = chosen.computeIfAbsent(uuid, k -> new ArrayList<>());
        Bucket bucket = new Bucket(kit.id(), mode);
        if (!kits.contains(bucket)) kits.add(bucket);
        plugin.hub().giveItems(player);
        plugin.sidebar().refresh(player);
        plugin.queueMusic().refresh(player);
        return switched ? JoinResult.SWITCHED : JoinResult.OK;
    }

    /** Queues a whole party (leader + members) as one entry for the 2v2 queue. */
    public JoinResult joinParty(Player leader, List<Player> members, Kit kit) {
        for (Player p : members) {
            if (plugin.matches().match(p.getUniqueId()) != null) return JoinResult.IN_MATCH;
        }
        if (!kit.enabled()) return JoinResult.KIT_DISABLED;
        for (Player p : members) {
            removeAll(p.getUniqueId());
            chosen.remove(p.getUniqueId());
        }
        removeAll(leader.getUniqueId());
        chosen.remove(leader.getUniqueId());
        List<UUID> others = new ArrayList<>();
        for (Player p : members) if (!p.equals(leader)) others.add(p.getUniqueId());
        QueueEntry entry = new QueueEntry(leader.getUniqueId(), leader.getName(), kit.id(), QueueMode.PARTY, 1000,
            System.currentTimeMillis(), null, leader.getPing(), 0, others);
        add(entry);
        for (Player p : members) {
            byPlayer.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
            if (!p.equals(leader)) byPlayer.get(p.getUniqueId()).add(entry);
            plugin.hub().giveItems(p);
            plugin.queueMusic().refresh(p);
        }
        return JoinResult.OK;
    }

    private void add(QueueEntry entry) {
        buckets.computeIfAbsent(new Bucket(entry.kit(), entry.mode()), k -> new ArrayList<>()).add(entry);
        byPlayer.computeIfAbsent(entry.player(), k -> new ArrayList<>()).add(entry);
    }

    /** Leaves every queue. Returns true if the player was queued. */
    public boolean leave(Player player, boolean notify) {
        chosen.remove(player.getUniqueId());
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

    /** Leaves the solo queue(s) of one kit. Returns true if the player was in it. */
    public boolean leave(Player player, Kit kit) {
        UUID uuid = player.getUniqueId();
        List<Bucket> kits = chosen.get(uuid);
        if (kits != null) {
            kits.removeIf(b -> b.kit().equals(kit.id()));
            if (kits.isEmpty()) chosen.remove(uuid);
        }
        List<QueueEntry> entries = byPlayer.get(uuid);
        if (entries == null) return false;
        boolean removed = false;
        for (Iterator<QueueEntry> it = entries.iterator(); it.hasNext(); ) {
            QueueEntry e = it.next();
            if (e.mode() == QueueMode.PARTY || !e.kit().equals(kit.id())) continue;
            it.remove();
            List<QueueEntry> bucket = buckets.get(new Bucket(e.kit(), e.mode()));
            if (bucket != null) bucket.remove(e);
            removed = true;
        }
        if (entries.isEmpty()) byPlayer.remove(uuid);
        if (removed) silenceIfIdle(uuid);
        if (removed && plugin.matches().match(uuid) == null) {
            plugin.hub().giveItems(player);
            plugin.sidebar().refresh(player);
        }
        return removed;
    }

    /** Removes every queue entry of a player (a match starts, they quit, …). Their chosen kits are kept. */
    public boolean removeAll(UUID uuid) {
        List<QueueEntry> entries = byPlayer.remove(uuid);
        if (entries == null || entries.isEmpty()) {
            silenceIfIdle(uuid);
            return false;
        }
        for (QueueEntry e : entries) {
            List<QueueEntry> bucket = buckets.get(new Bucket(e.kit(), e.mode()));
            if (bucket != null) bucket.remove(e);
            // a party entry is referenced by every member
            if (e.mode() == QueueMode.PARTY) {
                byPlayer.remove(e.player());
                silenceIfIdle(e.player());
                for (UUID m : e.partyMembers()) {
                    byPlayer.remove(m);
                    silenceIfIdle(m);
                }
            }
        }
        silenceIfIdle(uuid);
        return true;
    }

    /** No longer searching: the queue music, the boss bar and the searching bar stop right away. */
    private void silenceIfIdle(UUID uuid) {
        if (isQueued(uuid)) return;
        plugin.queueMusic().stop(uuid);
        searching.stop(uuid);
    }

    /** Forgets the queues a player chose (they left their match on purpose), so Keep Queuing won't re-join them. */
    public void forget(UUID uuid) {
        chosen.remove(uuid);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        removeAll(uuid);
        chosen.remove(uuid);
        prefs.forget(uuid);
    }

    // ------------------------------------------------------------------ keep queuing

    @EventHandler
    public void onMatchStart(MatchStartEvent event) {
        event.getMatch().onEnd(this::afterMatch);
    }

    /** Back in the hub after a match: players with Keep Queuing re-join the kits they were searching for. */
    private void afterMatch(Match m) {
        Match.EndReason reason = m.endReason();
        boolean requeue = (m.origin() == Match.Origin.QUEUE || m.origin() == Match.Origin.DUEL)
            && reason != Match.EndReason.CANCELLED && reason != Match.EndReason.NO_ARENA;
        for (Participant p : m.participants()) {
            // gone early (forfeit or quit): what they chose since belongs to their next queue, not to this match
            if (p.left() || plugin.matches().match(p.uuid()) != null || isQueued(p.uuid())) continue;
            List<Bucket> kits = chosen.remove(p.uuid());
            if (!requeue || kits == null || kits.isEmpty()) continue;
            PlayerProfile profile = plugin.profiles().get(p.uuid());
            if (profile == null || !profile.setting(Setting.KEEP_QUEUING)) continue;
            List<Bucket> copy = List.copyOf(kits);
            // once the hub teleport has settled; the player may have queued, left or joined another match since
            Bukkit.getScheduler().runTaskLater(plugin, () -> requeue(p.uuid(), copy), 20L);
        }
    }

    private void requeue(UUID uuid, List<Bucket> kits) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || isQueued(uuid) || plugin.matches().match(uuid) != null) return;
        List<Component> joined = new ArrayList<>();
        for (Bucket b : kits) {
            Kit kit = plugin.kits().get(b.kit());
            if (kit == null) continue;
            JoinResult r = join(player, kit, b.mode()); // the queue they chose; skipped when it has closed since
            if (r == JoinResult.OK || r == JoinResult.SWITCHED) joined.add(kit.sprite());
        }
        if (joined.isEmpty()) return;
        plugin.messages().send(player, "queue.requeued",
            Messages.comp("kit_icon", Component.join(JoinConfiguration.noSeparators(), joined)),
            Messages.num("count", joined.size()));
    }

    // ------------------------------------------------------------------ queries

    public boolean isQueued(UUID uuid) {
        List<QueueEntry> list = byPlayer.get(uuid);
        return list != null && !list.isEmpty();
    }

    /** Everyone searching in at least one queue (a copy). */
    public List<UUID> queuedPlayers() {
        List<UUID> list = new ArrayList<>(byPlayer.size());
        for (Map.Entry<UUID, List<QueueEntry>> e : byPlayer.entrySet()) if (!e.getValue().isEmpty()) list.add(e.getKey());
        return list;
    }

    public List<QueueEntry> entries(UUID uuid) {
        return byPlayer.getOrDefault(uuid, List.of());
    }

    /** True when the player is in a solo queue for this kit. */
    public boolean isQueued(UUID uuid, String kit) {
        return entry(uuid, kit) != null;
    }

    /** The solo queue entry of a player for a kit, or null. */
    public @Nullable QueueEntry entry(UUID uuid, String kit) {
        for (QueueEntry e : byPlayer.getOrDefault(uuid, List.of())) {
            if (e.mode() != QueueMode.PARTY && e.kit().equals(kit)) return e;
        }
        return null;
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
                    silenceIfIdle(entry.player());
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
        // (the searching action bar and boss bar are drawn by SearchingFeedback)
    }

    /**
     * Placeholders describing what a player searches for, for the action bar and the sidebar: {@code <kit>} (the
     * kit, or "N kits"), {@code <kit_icon>} (the icons of every kit), {@code <count>}, {@code <mode>},
     * {@code <wait>} and {@code <range>} (of the longest-waiting entry). Empty when not queued.
     */
    public List<TagResolver> searchTags(UUID uuid, long now) {
        SearchingFeedback.Search s = search(uuid, now);
        return s == null ? new ArrayList<>() : searching.tags(s);
    }

    /** What a player searches for right now (kits, wait, rating range and how far it has widened), or null. */
    public SearchingFeedback.@Nullable Search search(UUID uuid, long now) {
        List<QueueEntry> list = entries(uuid);
        if (list.isEmpty()) return null;
        QueueEntry oldest = list.stream().min(Comparator.comparingLong(QueueEntry::joinedAt)).orElseThrow();
        List<Kit> kits = new ArrayList<>();
        for (QueueEntry q : list) {
            Kit kit = plugin.kits().get(q.kit());
            if (kit != null && !kits.contains(kit)) kits.add(kit);
        }
        double range = matchmaker.windowFor(oldest, now);
        MainConfig c = plugin.settings();
        double widened = Double.isInfinite(range) || c.mmWindowMax <= c.mmWindowInitial ? 1
            : Math.clamp((range - c.mmWindowInitial) / (c.mmWindowMax - c.mmWindowInitial), 0.0, 1.0);
        return new SearchingFeedback.Search(kits, oldest.mode(), oldest.waitSeconds(now), range, widened);
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
