package top.cheesesmp.duelcore.friends;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.db.OrderedWrites;
import top.cheesesmp.duelcore.db.SqlWork;
import top.cheesesmp.duelcore.db.dao.FollowDao;
import top.cheesesmp.duelcore.db.dao.FollowDao.Person;
import top.cheesesmp.duelcore.db.dao.PlayerDao;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;
import top.cheesesmp.duelcore.ui.dialog.DialogService;

/**
 * Friends by following: A follows B one way; when both follow each other they are friends. The follow graph of
 * every online player is cached (loaded async on join, dropped on quit); follows and unfollows update the cache
 * right away and are written through to the database on the DB thread. The cache is changed on the main thread only.
 */
public final class FriendService implements Listener {

    public static final String PERMISSION = "duelcore.friends";
    /** Most players one player can follow (keeps the dialog and the cache small). */
    public static final int MAX_FOLLOWING = 300;
    /** A "followed you" notice for the same pair is sent at most this often (follow/unfollow spam). */
    private static final long NOTICE_COOLDOWN_MS = 10 * 60_000L;
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    /** Who one online player follows and who follows them, by uuid. */
    static final class Graph {
        final Map<UUID, Person> following = new ConcurrentHashMap<>();
        final Map<UUID, Person> followers = new ConcurrentHashMap<>();
        /** Bumped on every local change of {@link #following}; a reload snapshot taken before one is stale. */
        int changes;
    }

    private final DuelCorePlugin plugin;
    private final FriendDialogs dialogs;
    /** Written on the main thread; concurrent so API reads (e.g. tab completion) are safe from any thread. */
    private final Map<UUID, Graph> graphs = new ConcurrentHashMap<>();
    /** Newest load per player; an older load finishing late (quit and rejoin) is dropped. */
    private final Map<UUID, Long> loads = new HashMap<>();
    private final Map<String, Long> noticed = new HashMap<>();
    /** Players whose load waits for their profile (one retry chain each). */
    private final Set<UUID> waiting = new HashSet<>();
    /**
     * Follow writes and graph loads, in order even on the MySQL pool: an unfollow and a follow must not swap, and a
     * load must see every write queued before it (and its callback must run before theirs).
     */
    private @Nullable OrderedWrites ordered;
    private long loadCounter;

    public FriendService(DuelCorePlugin plugin) {
        this.plugin = plugin;
        this.dialogs = new FriendDialogs(plugin, this);
    }

    /** Registers the listener, the {@code duelcore:friend/…} clicks and the hub item, and loads online players. */
    public void enable() {
        ordered = new OrderedWrites(plugin.database()::submit);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.clicks().register("friend", dialogs::click);
        plugin.hub().registerItem("friends", PERMISSION, player -> {
            if (player.hasPermission(PERMISSION)) dialogs.open(player, 0, FriendDialogs.Filter.ALL);
            else plugin.messages().send(player, "command.no-permission");
        });
        for (Player p : Bukkit.getOnlinePlayers()) load(p, 0, false);
    }

    public void disable() {
        graphs.clear();
        loads.clear();
        noticed.clear();
        waiting.clear();
    }

    public FriendDialogs dialogs() {
        return dialogs;
    }

    // ------------------------------------------------------------------ API (main thread)

    /** True once the follow graph of this online player is loaded. */
    public boolean loaded(UUID player) {
        return graphs.containsKey(player);
    }

    /** Whether {@code follower} follows {@code followed}. Needs one of them online (and loaded); false otherwise. */
    public boolean isFollowing(UUID follower, UUID followed) {
        Graph g = graphs.get(follower);
        if (g != null) return g.following.containsKey(followed);
        Graph t = graphs.get(followed);
        return t != null && t.followers.containsKey(follower);
    }

    /** Mutual follows. */
    public boolean areFriends(UUID a, UUID b) {
        return isFollowing(a, b) && isFollowing(b, a);
    }

    /** Friends of an online player (online or not), empty while not loaded. */
    public List<Person> friends(UUID player) {
        Graph g = graphs.get(player);
        if (g == null) return List.of();
        List<Person> out = new ArrayList<>();
        for (Person p : g.following.values()) if (g.followers.containsKey(p.uuid())) out.add(p);
        return out;
    }

    /** Friends of a player who are online right now. */
    public List<Player> onlineFriends(UUID player) {
        List<Player> out = new ArrayList<>();
        for (Person p : friends(player)) {
            Player online = Bukkit.getPlayer(p.uuid());
            if (online != null) out.add(online);
        }
        return out;
    }

    /** Players an online player follows (read-only view, empty while not loaded). */
    public Collection<Person> following(UUID player) {
        Graph g = graphs.get(player);
        return g == null ? List.of() : Collections.unmodifiableCollection(g.following.values());
    }

    /** Players who follow an online player (read-only view, empty while not loaded). */
    public Collection<Person> followers(UUID player) {
        Graph g = graphs.get(player);
        return g == null ? List.of() : Collections.unmodifiableCollection(g.followers.values());
    }

    @Nullable Graph graph(UUID player) {
        return graphs.get(player);
    }

    /** Current name of a person: the live name when online, else the last stored one. */
    static String name(Person p) {
        Player online = Bukkit.getPlayer(p.uuid());
        return online != null ? online.getName() : p.name();
    }

    // ------------------------------------------------------------------ loading

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer(), 0, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        loads.remove(uuid);
        waiting.remove(uuid);
        Graph g = graphs.remove(uuid);
        if (g == null) return;
        for (Person p : g.following.values()) {
            if (!g.followers.containsKey(p.uuid())) continue;
            Player friend = Bukkit.getPlayer(p.uuid());
            if (friend != null && !friend.equals(player) && alerts(friend)) {
                plugin.messages().send(friend, "friends.offline", Messages.text("player", player.getName()));
            }
        }
    }

    /** Loads the graph of an online player (retried while the profile is still loading, slower after 15 s). */
    private void load(Player player, int attempt, boolean announce) {
        PlayerProfile profile = plugin.profiles().get(player);
        UUID uuid = player.getUniqueId();
        if (profile == null) {
            if (attempt == 0 && !waiting.add(uuid)) return; // a retry is already scheduled
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) load(player, attempt + 1, announce);
            }, attempt < 15 ? 20L : 100L);
            return;
        }
        waiting.remove(uuid);
        long token = ++loadCounter;
        loads.put(uuid, token);
        int id = profile.id();
        ordered(c -> FollowDao.load(c, id), relations -> {
            if (!player.isOnline() || !Long.valueOf(token).equals(loads.get(uuid))) return;
            loads.remove(uuid);
            Graph g = new Graph();
            for (Person p : relations.following()) g.following.put(p.uuid(), p);
            for (Person p : relations.followers()) g.followers.put(p.uuid(), p);
            graphs.put(uuid, g);
            if (announce) announceJoin(player);
        }, () -> {
            if (Long.valueOf(token).equals(loads.get(uuid))) loads.remove(uuid);
        });
    }

    /** Starts loading a player's graph if it is neither loaded nor loading (e.g. after a failed load). */
    void ensureLoaded(Player player) {
        if (!graphs.containsKey(player.getUniqueId()) && !loads.containsKey(player.getUniqueId())) load(player, 0, false);
    }

    /** Reloads a player's graph from the database (the dialog's Refresh), then runs {@code then}. */
    public void reload(Player player, Runnable then) {
        reload(player, then, 0);
    }

    private void reload(Player player, Runnable then, int attempt) {
        PlayerProfile profile = plugin.profiles().get(player);
        if (profile == null) {
            plugin.messages().send(player, "friends.loading");
            ensureLoaded(player);
            return;
        }
        UUID uuid = player.getUniqueId();
        int id = profile.id();
        Graph before = graphs.get(uuid);
        int changes = before == null ? 0 : before.changes;
        ordered(c -> FollowDao.load(c, id), relations -> {
            if (Bukkit.getPlayer(uuid) != player) return; // quit (or quit and rejoined) meanwhile
            Graph now = graphs.get(uuid);
            if (now != null && (now != before || now.changes != changes)) {
                // followed or unfollowed while this was read: read again (queued after that write), or keep the cache
                if (attempt < 3) reload(player, then, attempt + 1);
                else then.run();
                return;
            }
            Graph g = new Graph();
            for (Person p : relations.following()) g.following.put(p.uuid(), p);
            for (Person p : relations.followers()) g.followers.put(p.uuid(), p);
            graphs.put(uuid, g);
            then.run();
        }, () -> {
            if (player.isOnline()) plugin.messages().send(player, "friends.error");
        });
    }

    private void announceJoin(Player player) {
        List<Player> online = onlineFriends(player.getUniqueId());
        if (online.isEmpty()) return;
        List<String> names = new ArrayList<>();
        for (Player friend : online) {
            if (friend.equals(player)) continue;
            if (alerts(friend)) plugin.messages().send(friend, "friends.online", Messages.text("player", player.getName()));
            if (names.size() < 8) names.add(friend.getName());
        }
        if (!names.isEmpty() && alerts(player)) {
            String list = String.join(", ", names) + (online.size() > names.size() ? ", …" : "");
            plugin.messages().send(player, "friends.online-summary", Messages.num("count", online.size()),
                Messages.text("names", list));
        }
    }

    private boolean alerts(Player player) {
        PlayerProfile p = plugin.profiles().get(player);
        return p != null && p.setting(Setting.FRIEND_ALERTS);
    }

    // ------------------------------------------------------------------ follow / unfollow

    /** Follows a player by uuid (dialog and chat clicks). Offline players are looked up in the database. */
    public void follow(Player player, UUID target, @Nullable Runnable then) {
        Person known = person(player, target);
        if (known != null) {
            follow(player, known, then);
            return;
        }
        async(c -> PlayerDao.findByUuid(c, target), row -> {
            if (!player.isOnline()) return;
            if (row.isEmpty()) {
                plugin.messages().send(player, "friends.not-found", Messages.text("player", "?"));
                return;
            }
            follow(player, person(row.get()), then);
        }, () -> {
            if (player.isOnline()) plugin.messages().send(player, "friends.error");
        });
    }

    /** Follows a player by exact name; works for offline players who have played here. */
    public void followByName(Player player, String name, @Nullable Runnable then) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            follow(player, online.getUniqueId(), then);
            return;
        }
        if (!NAME.matcher(name).matches()) {
            plugin.messages().send(player, "friends.not-found", Messages.text("player", name));
            return;
        }
        async(c -> PlayerDao.findByName(c, name), row -> {
            if (!player.isOnline()) return;
            if (row.isEmpty()) {
                plugin.messages().send(player, "friends.not-found", Messages.text("player", name));
                return;
            }
            follow(player, person(row.get()), then);
        }, () -> {
            if (player.isOnline()) plugin.messages().send(player, "friends.error");
        });
    }

    private void follow(Player player, Person target, @Nullable Runnable then) {
        UUID uuid = player.getUniqueId();
        PlayerProfile me = plugin.profiles().get(player);
        Graph g = graphs.get(uuid);
        if (me == null || g == null) {
            plugin.messages().send(player, "friends.loading");
            ensureLoaded(player);
            return;
        }
        String targetName = name(target);
        if (target.uuid().equals(uuid) || target.id() == me.id()) {
            plugin.messages().send(player, "friends.self");
            return;
        }
        if (g.following.containsKey(target.uuid())) {
            plugin.messages().send(player, "friends.already-following", Messages.text("player", targetName));
            if (then != null) then.run();
            return;
        }
        if (g.following.size() >= MAX_FOLLOWING) {
            plugin.messages().send(player, "friends.limit", Messages.num("max", MAX_FOLLOWING));
            return;
        }
        long now = System.currentTimeMillis();
        Person followed = new Person(target.id(), target.uuid(), targetName, now);
        Person self = new Person(me.id(), uuid, player.getName(), now);
        g.following.put(target.uuid(), followed);
        g.changes++;
        sync(uuid, self, target.uuid());
        int meId = me.id();
        int targetId = target.id();
        var dialect = plugin.database().dialect();
        ordered(c -> FollowDao.follow(c, dialect, meId, targetId, now), added -> sync(uuid, self, target.uuid()), () -> {
            Graph cur = graphs.get(uuid);
            if (cur != null && cur.following.remove(target.uuid(), followed)) cur.changes++;
            sync(uuid, self, target.uuid());
            if (player.isOnline()) plugin.messages().send(player, "friends.error");
        });

        Player targetOnline = Bukkit.getPlayer(target.uuid());
        if (g.followers.containsKey(target.uuid())) {
            plugin.messages().send(player, "friends.now-friends", Messages.text("player", targetName));
            if (targetOnline != null && alerts(targetOnline) && firstNotice("mutual:", uuid, target.uuid(), now)) {
                plugin.messages().send(targetOnline, "friends.now-friends", Messages.text("player", player.getName()));
            }
        } else {
            plugin.messages().send(player, "friends.followed", Messages.text("player", targetName));
            if (targetOnline != null && alerts(targetOnline) && firstNotice("", uuid, target.uuid(), now)) {
                Component button = plugin.messages().get("friends.follow-back-button")
                    .hoverEvent(HoverEvent.showText(plugin.messages().get("friends.follow-back-hover",
                        Messages.text("player", player.getName()))))
                    .clickEvent(ClickEvent.custom(Key.key(DialogService.NS, "friend/follow"),
                        BinaryTagHolder.binaryTagHolder("{target:\"" + uuid + "\"}")));
                plugin.messages().send(targetOnline, "friends.followed-you", Messages.text("player", player.getName()),
                    Messages.comp("follow_back", button));
            }
        }
        if (then != null) then.run();
    }

    /** Stops following {@code target}. */
    public void unfollow(Player player, UUID target, @Nullable Runnable then) {
        UUID uuid = player.getUniqueId();
        PlayerProfile me = plugin.profiles().get(player);
        Graph g = graphs.get(uuid);
        if (me == null || g == null) {
            plugin.messages().send(player, "friends.loading");
            ensureLoaded(player);
            return;
        }
        Person followed = g.following.remove(target);
        if (followed == null) {
            Player online = Bukkit.getPlayer(target);
            plugin.messages().send(player, "friends.not-following",
                Messages.text("player", online == null ? "?" : online.getName()));
            if (then != null) then.run();
            return;
        }
        g.changes++;
        Person self = new Person(me.id(), uuid, player.getName(), System.currentTimeMillis());
        sync(uuid, self, target);
        int meId = me.id();
        int targetId = followed.id();
        ordered(c -> FollowDao.unfollow(c, meId, targetId), removed -> sync(uuid, self, target), () -> {
            Graph cur = graphs.get(uuid);
            if (cur != null && cur.following.putIfAbsent(target, followed) == null) cur.changes++;
            sync(uuid, self, target);
            if (player.isOnline()) plugin.messages().send(player, "friends.error");
        });
        plugin.messages().send(player, "friends.unfollowed", Messages.text("player", name(followed)));
        if (then != null) then.run();
    }

    /** Stops following a player by name (current or last known), without a database lookup. */
    public void unfollowByName(Player player, String name) {
        Graph g = graphs.get(player.getUniqueId());
        if (g == null) {
            plugin.messages().send(player, "friends.loading");
            ensureLoaded(player);
            return;
        }
        for (Person p : g.following.values()) {
            if (name(p).equalsIgnoreCase(name) || p.name().equalsIgnoreCase(name)) {
                unfollow(player, p.uuid(), null);
                return;
            }
        }
        plugin.messages().send(player, "friends.not-following", Messages.text("player", name));
    }

    /** Makes the followed player's cached followers agree with the follower's cached following. */
    private void sync(UUID follower, Person followerPerson, UUID followed) {
        Graph fg = graphs.get(follower);
        Graph tg = graphs.get(followed);
        if (fg == null || tg == null) return;
        if (fg.following.containsKey(followed)) tg.followers.putIfAbsent(follower, followerPerson);
        else tg.followers.remove(follower);
    }

    /** What we already know about {@code target} without the database: online profile or a cached relation. */
    private @Nullable Person person(Player player, UUID target) {
        Player online = Bukkit.getPlayer(target);
        PlayerProfile profile = online == null ? null : plugin.profiles().get(online);
        if (profile != null) return new Person(profile.id(), target, online.getName(), System.currentTimeMillis());
        Graph g = graphs.get(player.getUniqueId());
        if (g == null) return null;
        Person p = g.following.get(target);
        return p != null ? p : g.followers.get(target);
    }

    private static Person person(PlayerDao.PlayerRow row) {
        return new Person(row.id(), row.uuid(), row.name(), System.currentTimeMillis());
    }

    /** True when no {@code kind} notice for this pair was sent within the cooldown (then remembers this one). */
    private boolean firstNotice(String kind, UUID from, UUID to, long now) {
        if (noticed.size() > 512) noticed.values().removeIf(t -> now - t > NOTICE_COOLDOWN_MS);
        String key = kind + from + ">" + to;
        Long last = noticed.get(key);
        if (last != null && now - last < NOTICE_COOLDOWN_MS) return false;
        noticed.put(key, now);
        return true;
    }

    /** Runs {@code work} on the DB thread, then {@code done} or {@code failed} on the main thread (Database logs errors). */
    private <T> void async(SqlWork<T> work, Consumer<T> done, Runnable failed) {
        plugin.database().submit(work).whenComplete((result, error) -> back(result, error, done, failed));
    }

    /** Like {@link #async}, but in order with the other follow writes and graph loads (callbacks too). */
    private <T> void ordered(SqlWork<T> work, Consumer<T> done, Runnable failed) {
        OrderedWrites queue = ordered;
        if (queue == null) {
            failed.run();
            return;
        }
        queue.submit(work, (result, error) -> back(result, error, done, failed));
    }

    private <T> void back(@Nullable T result, @Nullable Throwable error, Consumer<T> done, Runnable failed) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (error == null) done.accept(result);
            else failed.run();
        });
    }

    // ------------------------------------------------------------------ helpers for commands and dialogs

    /** Names a player follows, for tab completion. */
    List<String> followedNames(UUID player) {
        Graph g = graphs.get(player);
        if (g == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Person p : g.following.values()) out.add(name(p));
        return out;
    }
}
