package top.cheesesmp.duelcore.party;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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
import top.cheesesmp.duelcore.db.OrderedWrites;
import top.cheesesmp.duelcore.db.SqlWork;
import top.cheesesmp.duelcore.db.dao.PartyDao;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.match.Participant;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;
import top.cheesesmp.duelcore.ui.AlertPop;
import top.cheesesmp.duelcore.ui.Icons;
import top.cheesesmp.duelcore.ui.dialog.DialogService;

/**
 * Persistent parties. They are loaded once at startup (after the database is migrated), kept in memory and changed on
 * the main thread only; every change is written through {@link OrderedWrites}. Members stay in their party while
 * offline and across restarts. Invites and party-vs-party challenges are memory only and expire. The async chat
 * listener reads an immutable per-player {@link ChatRoute}.
 */
public final class PartyService implements Listener, Runnable {

    public static final String PERMISSION = "duelcore.party";
    /** A leader offline this long while another member is online hands the lead to that member. */
    static final long LEADER_AWAY_MS = 60_000;
    /** The same leader can challenge the same party leader at most this often. */
    static final long CHALLENGE_COOLDOWN_MS = 15_000;

    public enum Result {
        OK, NOT_LOADED, NO_PROFILE, IN_PARTY, NOT_IN_PARTY, NOT_LEADER, FULL, OFFLINE, SELF, TARGET_IN_PARTY,
        ALREADY_MEMBER, INVITES_DISABLED, ALREADY_INVITED, NO_INVITE, NO_PARTY, PRIVATE, NEEDS_PASSWORD, WRONG_PASSWORD,
        BAD_PASSWORD, NOT_MEMBER, LEADER_MUST_BE_ONLINE, TOO_FEW, BUSY, MEMBER_BUSY, OWN_PARTY, ALREADY_CHALLENGED,
        NO_CHALLENGE, LEADER_OFFLINE, NO_OPPONENTS, SLOW_DOWN, KIT_DISABLED, FAILED
    }

    /** What an action did; {@code subject} is the player the message is about (the {@code <player>} tag). */
    public record Outcome(Result result, String subject) {

        static final Outcome OK = new Outcome(Result.OK, "");

        static Outcome of(Result result) {
            return new Outcome(result, "");
        }

        static Outcome of(Result result, String subject) {
            return new Outcome(result, subject);
        }

        public boolean ok() {
            return result == Result.OK;
        }
    }

    /** The three party match modes. */
    public enum Mode {
        FFA, SPLIT, PVP;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static @Nullable Mode parse(@Nullable String raw) {
            for (Mode m : values()) if (m.id().equals(raw)) return m;
            return null;
        }
    }

    public enum Status { ONLINE, OFFLINE, IN_MATCH }

    /** Party chat routing of one member: an immutable snapshot, safe to read from the async chat thread. */
    public record ChatRoute(String partyId, boolean chat, Set<UUID> members) {
    }

    public record Invite(String partyId, UUID inviter, String inviterName, String targetName, long expires) {
    }

    /** {@code from}'s leader challenged party {@code to} to a party-vs-party match. */
    public record Challenge(String from, String fromLeader, String to, String toLeader, String kit, long expires) {
    }

    private record Loaded(List<PartyDao.PartyRow> parties, List<PartyDao.Notice> notices, int removed) {
    }

    private final DuelCorePlugin plugin;
    private final PartyDialogs dialogs;
    private final Random random = new Random();
    private final Map<String, Party> parties = new LinkedHashMap<>();
    private final Map<UUID, Party> byMember = new HashMap<>();
    /** Keyed by the invited player. */
    private final Map<UUID, List<Invite>> invites = new HashMap<>();
    /** Keyed by the challenged party's id. */
    private final Map<String, List<Challenge>> challenges = new HashMap<>();
    /** Notices for players removed from a party while offline (also in dc_meta). */
    private final Map<UUID, PartyDao.Notice> notices = new HashMap<>();
    private final Map<UUID, ChatRoute> routes = new ConcurrentHashMap<>();
    /** Last password attempt per player (slows down guessing). */
    private final Map<UUID, Long> lastAttempt = new HashMap<>();
    /** Last Party vs Party challenge per "challenger uuid>challenged leader uuid" (survives disband and re-create). */
    private final Map<String, Long> lastChallenge = new HashMap<>();
    /** Since when a party's leader has been offline while members may be online, by party id. */
    private final Map<String, Long> leaderAway = new HashMap<>();
    private @Nullable OrderedWrites writes;
    private @Nullable BukkitTask ticker;
    private @Nullable BukkitTask waiter;
    private boolean loaded;
    private boolean loading;
    private int waitChecks;

    public PartyService(DuelCorePlugin plugin) {
        this.plugin = plugin;
        this.dialogs = new PartyDialogs(plugin, this);
    }

    public PartyDialogs dialogs() {
        return dialogs;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Registers listeners, the click prefix and the hub item, then loads the parties once the database is ready. */
    public void enable() {
        writes = new OrderedWrites(plugin.database()::transaction);
        var pm = plugin.getServer().getPluginManager();
        pm.registerEvents(this, plugin);
        pm.registerEvents(new PartyChatListener(this), plugin);
        plugin.clicks().register("party", dialogs::click);
        plugin.hub().registerItem("party", PERMISSION, player -> {
            if (player.hasPermission(PERMISSION)) {
                dialogs.open(player);
            } else {
                plugin.messages().send(player, "command.no-permission");
                plugin.menuSounds().play(player, top.cheesesmp.duelcore.ui.MenuSound.DENY);
            }
        });
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this, 20L, 20L);
        waiter = Bukkit.getScheduler().runTaskTimer(plugin, this::loadWhenReady, 5L, 10L);
    }

    /** Stops the timers and forgets everything (pending writes are drained by the database on close). */
    public void disable() {
        if (ticker != null) ticker.cancel();
        if (waiter != null) waiter.cancel();
        loaded = false;
        parties.clear();
        byMember.clear();
        invites.clear();
        challenges.clear();
        notices.clear();
        routes.clear();
        lastAttempt.clear();
        lastChallenge.clear();
        leaderAway.clear();
    }

    private void loadWhenReady() {
        if (loading) return;
        if (!plugin.profiles().ready()) {
            if (++waitChecks == 240) plugin.getLogger().warning("Parties are still waiting for the database");
            return;
        }
        loading = true;
        if (waiter != null) waiter.cancel();
        plugin.database().transaction(c -> {
            int removed = PartyDao.cleanup(c);
            return new Loaded(PartyDao.loadAll(c), PartyDao.loadNotices(c), removed);
        }).whenComplete((data, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || data == null) {
                plugin.getLogger().log(Level.SEVERE, "Could not load parties; retrying in 30 seconds", error);
                loading = false;
                waiter = Bukkit.getScheduler().runTaskTimer(plugin, this::loadWhenReady, 600L, 10L);
                return;
            }
            apply(data);
        }));
    }

    private void apply(Loaded data) {
        for (PartyDao.PartyRow row : data.parties()) {
            List<Party.Member> members = new ArrayList<>();
            UUID leader = null;
            for (PartyDao.MemberRow m : row.members()) {
                if (byMember.containsKey(m.uuid())) continue;
                members.add(new Party.Member(m.playerId(), m.uuid(), m.name(), m.joinedAt(), m.chat()));
                if (m.playerId() == row.leaderId()) leader = m.uuid();
            }
            if (members.isEmpty()) continue;
            boolean repaired = leader == null;
            if (repaired) leader = Objects.requireNonNull(Party.successor(members, null, PartyService::online)).uuid();
            Party party = new Party(row.id(), leader, row.open(), row.password(), row.createdAt());
            members.forEach(party::add);
            register(party);
            if (repaired) persist(party);
        }
        for (PartyDao.Notice n : data.notices()) notices.put(n.uuid(), n);
        long now = System.currentTimeMillis();
        for (Party party : parties.values()) {
            if (!online(party.leader()) && onlineCount(party) > 0) leaderAway.put(party.id(), now);
        }
        loaded = true;
        plugin.getLogger().info("Loaded " + parties.size() + " parties"
            + (data.removed() > 0 ? " (removed " + data.removed() + " stale rows)" : ""));
        for (Player p : Bukkit.getOnlinePlayers()) deliverNotice(p); // joined while parties were loading
    }

    private void register(Party party) {
        parties.put(party.id(), party);
        for (Party.Member m : party.members()) byMember.put(m.uuid(), party);
        refreshRoutes(party);
    }

    private void write(SqlWork<?> work) {
        if (writes != null) writes.submit(work);
    }

    /** Saves leader, open flag and password of a party. */
    private void persist(Party party) {
        Party.Member leader = party.leaderMember();
        if (leader == null) return;
        String id = party.id();
        int leaderId = leader.id();
        boolean open = party.open();
        String hash = party.passwordHash();
        write(c -> {
            PartyDao.updateParty(c, id, leaderId, open, hash);
            return null;
        });
    }

    // ------------------------------------------------------------------ queries

    public boolean loaded() {
        return loaded;
    }

    public @Nullable Party party(UUID player) {
        return byMember.get(player);
    }

    public @Nullable Party byId(@Nullable String id) {
        return id == null ? null : parties.get(id);
    }

    public Collection<Party> parties() {
        return Collections.unmodifiableCollection(parties.values());
    }

    /** The party led by a player of that name (case-insensitive), preferring one whose leader is online. */
    public @Nullable Party byLeaderName(String name) {
        Party found = null;
        for (Party p : parties.values()) {
            Party.Member leader = p.leaderMember();
            if (leader == null || !leader.name().equalsIgnoreCase(name)) continue;
            if (online(leader.uuid())) return p;
            found = p;
        }
        return found;
    }

    public int maxSize() {
        return plugin.settings().partyMaxSize;
    }

    public int inviteSeconds() {
        return plugin.settings().partyInviteSeconds;
    }

    public @Nullable ChatRoute route(UUID player) {
        return routes.get(player);
    }

    public static boolean online(UUID uuid) {
        return Bukkit.getPlayer(uuid) != null;
    }

    public Status status(UUID uuid) {
        if (!online(uuid)) return Status.OFFLINE;
        return plugin.matches().match(uuid) != null ? Status.IN_MATCH : Status.ONLINE;
    }

    public int onlineCount(Party party) {
        int n = 0;
        for (Party.Member m : party.members()) if (online(m.uuid())) n++;
        return n;
    }

    /** Invites waiting for this player (not expired), newest last. */
    public List<Invite> invites(UUID player) {
        long now = System.currentTimeMillis();
        List<Invite> list = new ArrayList<>();
        for (Invite i : invites.getOrDefault(player, List.of())) if (i.expires() >= now && parties.containsKey(i.partyId())) list.add(i);
        return list;
    }

    /** Challenges waiting for this party's answer (not expired). */
    public List<Challenge> challenges(String partyId) {
        long now = System.currentTimeMillis();
        List<Challenge> list = new ArrayList<>();
        for (Challenge c : challenges.getOrDefault(partyId, List.of())) if (c.expires() >= now && parties.containsKey(c.from())) list.add(c);
        return list;
    }

    /** Parties of others whose leader is online (Party vs Party targets). */
    public List<Party> challengeable(@Nullable Party own) {
        List<Party> list = new ArrayList<>();
        for (Party p : parties.values()) if (p != own && online(p.leader())) list.add(p);
        return list;
    }

    /** Why the leader can't start a match with this party right now (null = they can). */
    public @Nullable Result matchBlock(Party party, Player player, Mode mode) {
        if (!party.isLeader(player.getUniqueId())) return Result.NOT_LEADER;
        if (plugin.matches().match(player.getUniqueId()) != null) return Result.BUSY;
        if (mode == Mode.PVP) return challengeable(party).isEmpty() ? Result.NO_OPPONENTS : null;
        return onlineCount(party) < 2 ? Result.TOO_FEW : null;
    }

    // ------------------------------------------------------------------ create / invite / join

    private @Nullable Outcome notReady(Player player) {
        if (!loaded) return Outcome.of(Result.NOT_LOADED);
        if (plugin.profiles().get(player) == null) return Outcome.of(Result.NO_PROFILE);
        return null;
    }

    private static CompletableFuture<Outcome> done(Outcome outcome) {
        return CompletableFuture.completedFuture(outcome);
    }

    /** Creates a party led by the player; with a password it is hashed off the main thread first. */
    public CompletableFuture<Outcome> create(Player player, @Nullable String password) {
        Outcome pre = canCreate(player, password);
        if (pre != null) return done(pre);
        if (password == null) return done(createNow(player, null));
        return hash(password).thenApply(hash -> {
            Outcome again = player.isOnline() ? canCreate(player, password) : Outcome.of(Result.OFFLINE, player.getName());
            return again != null ? again : createNow(player, hash);
        });
    }

    private @Nullable Outcome canCreate(Player player, @Nullable String password) {
        Outcome pre = notReady(player);
        if (pre != null) return pre;
        if (byMember.containsKey(player.getUniqueId())) return Outcome.of(Result.IN_PARTY);
        if (password != null && !PartyPasswords.valid(password)) return Outcome.of(Result.BAD_PASSWORD);
        return null;
    }

    private Outcome createNow(Player player, @Nullable String hash) {
        PlayerProfile profile = Objects.requireNonNull(plugin.profiles().get(player));
        long now = System.currentTimeMillis();
        Party party = new Party(UUID.randomUUID().toString(), player.getUniqueId(), plugin.settings().partyOpenByDefault,
            hash, now);
        party.add(new Party.Member(profile.id(), player.getUniqueId(), player.getName(), now, false));
        register(party);
        invites.remove(player.getUniqueId());
        String id = party.id();
        int playerId = profile.id();
        boolean open = party.open();
        var dialect = plugin.database().dialect();
        write(c -> {
            PartyDao.insertParty(c, id, playerId, open, hash, now);
            PartyDao.addMember(c, dialect, id, playerId, now, false);
            return null;
        });
        plugin.messages().send(player, "party.created");
        return Outcome.OK;
    }

    /** True when the two players follow each other (friends), when the friends feature is running. */
    boolean friends(Player a, Player b) {
        var friends = plugin.friends();
        return friends != null && friends.areFriends(a.getUniqueId(), b.getUniqueId());
    }

    /** Invites a player; a player without a party gets one first. */
    public Outcome invite(Player from, Player target) {
        Outcome pre = notReady(from);
        if (pre != null) return pre;
        if (from.equals(target)) return Outcome.of(Result.SELF);
        String name = target.getName();
        Party party = byMember.get(from.getUniqueId());
        if (party != null && !party.isLeader(from.getUniqueId())) return Outcome.of(Result.NOT_LEADER);
        if (party != null && party.contains(target.getUniqueId())) return Outcome.of(Result.ALREADY_MEMBER, name);
        if (byMember.containsKey(target.getUniqueId())) return Outcome.of(Result.TARGET_IN_PARTY, name);
        PlayerProfile targetProfile = plugin.profiles().get(target);
        if (targetProfile == null) return Outcome.of(Result.OFFLINE, name);
        // with "party invites from anyone" off, only friends (mutual follows) may still invite
        if (!targetProfile.setting(Setting.PARTY_INVITES) && !friends(from, target)) {
            return Outcome.of(Result.INVITES_DISABLED, name);
        }
        if (party != null && party.size() >= maxSize()) return Outcome.of(Result.FULL);
        if (party != null && findInvite(target.getUniqueId(), party.id(), null) != null) {
            return Outcome.of(Result.ALREADY_INVITED, name);
        }
        if (party == null) {
            Outcome created = createNow(from, null);
            if (!created.ok()) return created;
            party = Objects.requireNonNull(byMember.get(from.getUniqueId()));
        }
        long expires = System.currentTimeMillis() + inviteSeconds() * 1000L;
        invites.computeIfAbsent(target.getUniqueId(), k -> new ArrayList<>())
            .add(new Invite(party.id(), from.getUniqueId(), from.getName(), name, expires));
        Messages msg = plugin.messages();
        String payload = "{party:\"" + party.id() + "\",from:\"chat\"}";
        msg.send(target, "party.invite-received", Messages.comp("head", Icons.head(from.getUniqueId(), from.getName())),
            Messages.text("player", from.getName()), Messages.num("size", party.size()), Messages.num("max", maxSize()),
            Messages.num("seconds", inviteSeconds()),
            Messages.comp("accept", clickable("party.accept-button", "party/accept", payload)),
            Messages.comp("deny", clickable("party.deny-button", "party/deny", payload)));
        plugin.alerts().pop(target, AlertPop.Kind.PARTY_INVITE, "hub.alerts.party-invite", Messages.text("player", from.getName()));
        msg.send(from, "party.invite-sent", Messages.text("player", name), Messages.num("seconds", inviteSeconds()));
        notifyParty(party, from.getUniqueId(), "party.notify.invited", Messages.text("player", name),
            Messages.text("inviter", from.getName()));
        return Outcome.OK;
    }

    private Component clickable(String key, String action, String payload) {
        return plugin.messages().get(key).clickEvent(ClickEvent.custom(Key.key(DialogService.NS, action),
            BinaryTagHolder.binaryTagHolder(payload)));
    }

    /** The invite for this player into a party (by id, or by the inviter's / leader's name), or the newest one. */
    private @Nullable Invite findInvite(UUID player, @Nullable String partyId, @Nullable String name) {
        List<Invite> list = invites(player);
        for (int i = list.size() - 1; i >= 0; i--) {
            Invite inv = list.get(i);
            if (partyId != null && !inv.partyId().equals(partyId)) continue;
            if (name != null) {
                Party p = parties.get(inv.partyId());
                Party.Member leader = p == null ? null : p.leaderMember();
                boolean matches = inv.inviterName().equalsIgnoreCase(name) || leader != null && leader.name().equalsIgnoreCase(name);
                if (!matches) continue;
            }
            return inv;
        }
        return null;
    }

    private void dropInvite(UUID player, Invite invite) {
        List<Invite> list = invites.get(player);
        if (list == null) return;
        list.remove(invite);
        if (list.isEmpty()) invites.remove(player);
    }

    public Outcome accept(Player player, @Nullable String partyId, @Nullable String name) {
        Outcome pre = notReady(player);
        if (pre != null) return pre;
        if (byMember.containsKey(player.getUniqueId())) return Outcome.of(Result.IN_PARTY);
        Invite invite = findInvite(player.getUniqueId(), partyId, name);
        if (invite == null) return Outcome.of(Result.NO_INVITE);
        Party party = parties.get(invite.partyId());
        if (party == null) return Outcome.of(Result.NO_PARTY, invite.inviterName());
        if (party.size() >= maxSize()) return Outcome.of(Result.FULL);
        addMember(party, player);
        return Outcome.OK;
    }

    public Outcome deny(Player player, @Nullable String partyId, @Nullable String name) {
        Invite invite = findInvite(player.getUniqueId(), partyId, name);
        if (invite == null) return Outcome.of(Result.NO_INVITE);
        dropInvite(player.getUniqueId(), invite);
        Player inviter = Bukkit.getPlayer(invite.inviter());
        if (inviter != null) plugin.messages().send(inviter, "party.invite-denied", Messages.text("player", player.getName()));
        plugin.messages().send(player, "party.denied");
        return Outcome.OK;
    }

    /** Joins the party led by {@code leaderName}: with an invite, an open party, or its password (checked async). */
    public CompletableFuture<Outcome> join(Player player, String leaderName, @Nullable String password) {
        Party party = loaded ? byLeaderName(leaderName) : null;
        if (loaded && party == null) return done(Outcome.of(Result.NO_PARTY, leaderName));
        return join(player, party, password);
    }

    public CompletableFuture<Outcome> join(Player player, @Nullable Party party, @Nullable String password) {
        Outcome pre = notReady(player);
        if (pre != null) return done(pre);
        UUID uuid = player.getUniqueId();
        if (party == null || !parties.containsKey(party.id())) return done(Outcome.of(Result.NO_PARTY));
        if (party.contains(uuid) || byMember.containsKey(uuid)) return done(Outcome.of(Result.IN_PARTY));
        if (party.size() >= maxSize()) return done(Outcome.of(Result.FULL));
        if (findInvite(uuid, party.id(), null) != null || party.open() && !party.hasPassword()) {
            addMember(party, player);
            return done(Outcome.OK);
        }
        if (!party.hasPassword()) return done(Outcome.of(Result.PRIVATE));
        if (password == null || password.isEmpty()) return done(Outcome.of(Result.NEEDS_PASSWORD));
        long now = System.currentTimeMillis();
        Long last = lastAttempt.get(uuid);
        if (last != null && now - last < 1500) return done(Outcome.of(Result.SLOW_DOWN));
        lastAttempt.put(uuid, now);
        String hash = party.passwordHash();
        String id = party.id();
        return verify(password, hash).thenApply(match -> {
            if (!match) return Outcome.of(Result.WRONG_PASSWORD);
            Party again = parties.get(id);
            if (again == null) return Outcome.of(Result.NO_PARTY);
            if (!player.isOnline()) return Outcome.of(Result.OFFLINE, player.getName());
            if (byMember.containsKey(uuid)) return Outcome.of(Result.IN_PARTY);
            if (!Objects.equals(again.passwordHash(), hash)) return Outcome.of(Result.WRONG_PASSWORD); // changed meanwhile
            if (again.size() >= maxSize()) return Outcome.of(Result.FULL);
            addMember(again, player);
            return Outcome.OK;
        });
    }

    private void addMember(Party party, Player player) {
        PlayerProfile profile = Objects.requireNonNull(plugin.profiles().get(player));
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        party.add(new Party.Member(profile.id(), uuid, player.getName(), now, false));
        byMember.put(uuid, party);
        invites.remove(uuid);
        refreshRoutes(party);
        String id = party.id();
        int playerId = profile.id();
        var dialect = plugin.database().dialect();
        write(c -> {
            PartyDao.addMember(c, dialect, id, playerId, now, false);
            return null;
        });
        notifyParty(party, uuid, "party.notify.joined", Messages.text("player", player.getName()));
        Party.Member leader = party.leaderMember();
        plugin.messages().send(player, "party.you-joined", Messages.text("leader", leader == null ? "?" : leader.name()),
            Messages.num("size", party.size()), Messages.num("max", maxSize()));
        for (Party.Member m : party.members()) {
            Player p = m.uuid().equals(uuid) ? null : Bukkit.getPlayer(m.uuid());
            if (p != null) {
                plugin.alerts().pop(p, AlertPop.Kind.PARTY_JOINED, "hub.alerts.party-joined", Messages.text("player", player.getName()));
            }
        }
        plugin.alerts().pop(player, AlertPop.Kind.PARTY_JOINED, "hub.alerts.you-joined",
            Messages.text("leader", leader == null ? "?" : leader.name()));
    }

    // ------------------------------------------------------------------ leave / kick / promote / disband

    public Outcome leave(Player player) {
        if (!loaded) return Outcome.of(Result.NOT_LOADED);
        Party party = byMember.get(player.getUniqueId());
        if (party == null) return Outcome.of(Result.NOT_IN_PARTY);
        removeMember(party, player.getUniqueId(), false);
        plugin.messages().send(player, "party.you-left");
        return Outcome.OK;
    }

    /** Removes a member; the leader's place goes to the longest-standing (online first) member, an empty party is deleted. */
    private void removeMember(Party party, UUID uuid, boolean kicked) {
        Party.Member member = party.remove(uuid);
        if (member == null) return;
        byMember.remove(uuid);
        routes.remove(uuid);
        if (party.size() == 0) {
            deleteParty(party);
            return;
        }
        int playerId = member.id();
        write(c -> {
            PartyDao.removeMember(c, playerId);
            return null;
        });
        notifyParty(party, null, kicked ? "party.notify.kicked" : "party.notify.left", Messages.text("player", member.name()));
        if (party.isLeader(uuid)) {
            Party.Member next = Objects.requireNonNull(Party.successor(party.members(), null, PartyService::online));
            party.leader(next.uuid());
            persist(party);
            notifyParty(party, null, "party.notify.leader", Messages.text("player", next.name()));
        }
        refreshRoutes(party);
    }

    private void deleteParty(Party party) {
        String id = party.id();
        parties.remove(id);
        for (Party.Member m : party.members()) {
            byMember.remove(m.uuid());
            routes.remove(m.uuid());
        }
        for (Iterator<List<Invite>> it = invites.values().iterator(); it.hasNext(); ) {
            List<Invite> list = it.next();
            list.removeIf(i -> i.partyId().equals(id));
            if (list.isEmpty()) it.remove();
        }
        challenges.remove(id);
        for (Iterator<List<Challenge>> it = challenges.values().iterator(); it.hasNext(); ) {
            List<Challenge> list = it.next();
            list.removeIf(c -> c.from().equals(id));
            if (list.isEmpty()) it.remove();
        }
        write(c -> {
            PartyDao.deleteParty(c, id);
            return null;
        });
    }

    /** Leader check shared by the leader-only actions; null party = the player isn't the leader of one. */
    private record Led(@Nullable Party party, Outcome error) {
    }

    private Led led(Player player) {
        Outcome pre = notReady(player);
        if (pre != null) return new Led(null, pre);
        Party party = byMember.get(player.getUniqueId());
        if (party == null) return new Led(null, Outcome.of(Result.NOT_IN_PARTY));
        if (!party.isLeader(player.getUniqueId())) return new Led(null, Outcome.of(Result.NOT_LEADER));
        return new Led(party, Outcome.OK);
    }

    public Outcome kick(Player leader, String name) {
        Party party = byMember.get(leader.getUniqueId());
        Party.Member member = party == null ? null : party.member(name);
        if (party != null && member == null) return Outcome.of(Result.NOT_MEMBER, name);
        return kick(leader, member == null ? null : member.uuid());
    }

    public Outcome kick(Player leader, @Nullable UUID target) {
        Led led = led(leader);
        Party party = led.party();
        if (party == null) return led.error();
        Party.Member member = target == null ? null : party.member(target);
        if (member == null) return Outcome.of(Result.NOT_MEMBER);
        if (member.uuid().equals(leader.getUniqueId())) return Outcome.of(Result.SELF);
        removeMember(party, member.uuid(), true);
        Player online = Bukkit.getPlayer(member.uuid());
        if (online != null) plugin.messages().send(online, "party.you-kicked", Messages.text("leader", leader.getName()));
        else storeNotice(member, "kicked", leader.getName());
        return Outcome.OK;
    }

    public Outcome promote(Player leader, String name) {
        Party party = byMember.get(leader.getUniqueId());
        Party.Member member = party == null ? null : party.member(name);
        if (party != null && member == null) return Outcome.of(Result.NOT_MEMBER, name);
        return promote(leader, member == null ? null : member.uuid());
    }

    public Outcome promote(Player leader, @Nullable UUID target) {
        Led led = led(leader);
        Party party = led.party();
        if (party == null) return led.error();
        Party.Member member = target == null ? null : party.member(target);
        if (member == null) return Outcome.of(Result.NOT_MEMBER);
        if (member.uuid().equals(leader.getUniqueId())) return Outcome.of(Result.SELF);
        if (!online(member.uuid())) return Outcome.of(Result.LEADER_MUST_BE_ONLINE, member.name());
        party.leader(member.uuid());
        persist(party);
        notifyParty(party, null, "party.notify.leader", Messages.text("player", member.name()));
        return Outcome.OK;
    }

    public Outcome disband(Player leader) {
        Led led = led(leader);
        Party party = led.party();
        if (party == null) return led.error();
        for (Party.Member m : party.members()) {
            if (m.uuid().equals(leader.getUniqueId())) continue;
            Player online = Bukkit.getPlayer(m.uuid());
            if (online != null) plugin.messages().send(online, "party.notify.disbanded", Messages.text("player", leader.getName()));
            else storeNotice(m, "disbanded", leader.getName());
        }
        deleteParty(party);
        plugin.messages().send(leader, "party.you-disbanded");
        return Outcome.OK;
    }

    // ------------------------------------------------------------------ settings

    public Outcome toggleChat(Player player) {
        if (!loaded) return Outcome.of(Result.NOT_LOADED);
        Party party = byMember.get(player.getUniqueId());
        Party.Member member = party == null ? null : party.member(player.getUniqueId());
        if (party == null || member == null) return Outcome.of(Result.NOT_IN_PARTY);
        boolean on = !member.chat();
        member.chat(on);
        refreshRoutes(party);
        int playerId = member.id();
        write(c -> {
            PartyDao.setChat(c, playerId, on);
            return null;
        });
        plugin.messages().send(player, on ? "party.chat-on" : "party.chat-off");
        return Outcome.OK;
    }

    public Outcome setOpen(Player leader, boolean open) {
        Led led = led(leader);
        Party party = led.party();
        if (party == null) return led.error();
        if (party.open() == open) return Outcome.OK;
        party.open(open);
        persist(party);
        notifyParty(party, null, open ? "party.notify.open" : "party.notify.private", Messages.text("player", leader.getName()));
        return Outcome.OK;
    }

    /** Sets (hashed off the main thread) or, with null, removes the join password. */
    public CompletableFuture<Outcome> setPassword(Player leader, @Nullable String password) {
        Led led = led(leader);
        Party party = led.party();
        if (party == null) return done(led.error());
        if (password == null) {
            if (!party.hasPassword()) return done(Outcome.OK);
            party.passwordHash(null);
            persist(party);
            notifyParty(party, null, "party.notify.password-cleared", Messages.text("player", leader.getName()));
            return done(Outcome.OK);
        }
        if (!PartyPasswords.valid(password)) return done(Outcome.of(Result.BAD_PASSWORD));
        String id = party.id();
        return hash(password).thenApply(hash -> {
            Party again = parties.get(id);
            if (again == null || !again.isLeader(leader.getUniqueId())) return Outcome.of(Result.NOT_LEADER);
            again.passwordHash(hash);
            persist(again);
            notifyParty(again, null, "party.notify.password-set", Messages.text("player", leader.getName()));
            return Outcome.OK;
        });
    }

    // ------------------------------------------------------------------ chat

    /** Sends a party chat line (/pc; the chat filter already ran on the command). */
    public Outcome chat(Player player, String message) {
        if (!loaded) return Outcome.of(Result.NOT_LOADED);
        Party party = byMember.get(player.getUniqueId());
        if (party == null) return Outcome.of(Result.NOT_IN_PARTY);
        Component line = chatLine(player.displayName(), Component.text(message));
        for (Party.Member m : party.members()) {
            Player p = Bukkit.getPlayer(m.uuid());
            if (p != null) p.sendMessage(line);
        }
        Bukkit.getConsoleSender().sendMessage(line);
        return Outcome.OK;
    }

    /** One party chat line (messages.yml party.chat-format). Safe to call from the async chat thread. */
    Component chatLine(Component name, Component message) {
        Messages msg = plugin.messages();
        return msg.parse(msg.raw("party.chat-format"), Messages.comp("name", name), Messages.comp("message", message));
    }

    private void refreshRoutes(Party party) {
        List<UUID> uuids = new ArrayList<>();
        for (Party.Member m : party.members()) uuids.add(m.uuid());
        Set<UUID> members = Set.copyOf(uuids);
        for (Party.Member m : party.members()) routes.put(m.uuid(), new ChatRoute(party.id(), m.chat(), members));
    }

    // ------------------------------------------------------------------ party matches

    /** Online members of a party as fighters; an error when one of them is in a match or still loading. */
    private @Nullable Outcome fighters(Party party, List<Player> out) {
        for (Party.Member m : party.members()) {
            Player p = Bukkit.getPlayer(m.uuid());
            if (p == null) continue;
            if (plugin.matches().match(m.uuid()) != null) return Outcome.of(Result.MEMBER_BUSY, m.name());
            if (plugin.profiles().get(p) == null) return Outcome.of(Result.NO_PROFILE);
            out.add(p);
        }
        return null;
    }

    private @Nullable Outcome startable(Player leader, Kit kit) {
        if (!kit.enabled()) return Outcome.of(Result.KIT_DISABLED);
        if (plugin.matches().match(leader.getUniqueId()) != null) return Outcome.of(Result.BUSY);
        return null;
    }

    /** Party FFA: every online member for themselves, one round. */
    public Outcome startFfa(Player leader, Kit kit) {
        return startOwn(leader, kit, Mode.FFA);
    }

    /** Party Duel: the online members in two random, balanced teams. */
    public Outcome startSplit(Player leader, Kit kit) {
        return startOwn(leader, kit, Mode.SPLIT);
    }

    private Outcome startOwn(Player leader, Kit kit, Mode mode) {
        Led led = led(leader);
        Party party = led.party();
        if (party == null) return led.error();
        Outcome blocked = startable(leader, kit);
        if (blocked != null) return blocked;
        List<Player> players = new ArrayList<>();
        Outcome busy = fighters(party, players);
        if (busy != null) return busy;
        if (players.size() < 2) return Outcome.of(Result.TOO_FEW);
        Match match = mode == Mode.FFA
            ? plugin.matches().createFfa(players, kit, Match.Origin.PARTY)
            : plugin.matches().create(Party.split(players, random), kit, false, Match.Origin.PARTY);
        if (match == null) return Outcome.of(Result.FAILED);
        if (mode == Mode.SPLIT) announceTeams(match);
        watch(match, mode, List.of(party.id()), List.of());
        return Outcome.OK;
    }

    /** Tells everyone in a team match who is on their side (nametags have no team colours). */
    private void announceTeams(Match match) {
        for (Participant p : match.participants()) {
            Player player = Bukkit.getPlayer(p.uuid());
            if (player == null) continue;
            List<String> mates = new ArrayList<>();
            List<String> others = new ArrayList<>();
            for (Participant q : match.participants()) {
                if (q == p) continue;
                (q.team() == p.team() ? mates : others).add(q.name());
            }
            plugin.messages().send(player, mates.isEmpty() ? "party.match.teams-solo" : "party.match.teams",
                Messages.text("team", String.join(", ", mates)), Messages.text("opponents", String.join(", ", others)));
        }
    }

    /** Party vs Party: asks the other party's leader (chat + dialog); {@link #acceptChallenge} starts the match. */
    public Outcome challenge(Player leader, @Nullable String targetPartyId, Kit kit) {
        Led led = led(leader);
        Party own = led.party();
        if (own == null) return led.error();
        Party target = byId(targetPartyId);
        if (target == null) return Outcome.of(Result.NO_PARTY);
        if (target == own) return Outcome.of(Result.OWN_PARTY);
        Player targetLeader = Bukkit.getPlayer(target.leader());
        if (targetLeader == null) return Outcome.of(Result.LEADER_OFFLINE);
        Outcome blocked = startable(leader, kit);
        if (blocked != null) return blocked;
        for (Challenge c : challenges(target.id())) if (c.from().equals(own.id())) return Outcome.of(Result.ALREADY_CHALLENGED);
        long now = System.currentTimeMillis();
        String pair = leader.getUniqueId() + ">" + targetLeader.getUniqueId();
        Long last = lastChallenge.get(pair);
        if (last != null && now - last < CHALLENGE_COOLDOWN_MS) return Outcome.of(Result.SLOW_DOWN);
        lastChallenge.put(pair, now);
        Challenge challenge = new Challenge(own.id(), leader.getName(), target.id(), targetLeader.getName(), kit.id(),
            now + inviteSeconds() * 1000L);
        challenges.computeIfAbsent(target.id(), k -> new ArrayList<>()).add(challenge);
        String payload = "{party:\"" + own.id() + "\",from:\"chat\"}";
        plugin.messages().send(targetLeader, "party.challenge-received", Messages.text("leader", leader.getName()),
            Messages.num("count", onlineCount(own)), Messages.comp("kit", kit.displayName()), Messages.comp("kit_icon", kit.sprite()),
            Messages.num("seconds", inviteSeconds()),
            Messages.comp("accept", clickable("party.accept-button", "party/duel-accept", payload)),
            Messages.comp("deny", clickable("party.deny-button", "party/duel-deny", payload)));
        if (plugin.matches().match(targetLeader.getUniqueId()) == null) dialogs.openChallenge(targetLeader, challenge);
        plugin.messages().send(leader, "party.challenge-sent", Messages.text("leader", targetLeader.getName()),
            Messages.comp("kit", kit.displayName()), Messages.comp("kit_icon", kit.sprite()),
            Messages.num("seconds", inviteSeconds()));
        return Outcome.OK;
    }

    /** The newest open challenge to party {@code to} (from {@code from}, or from anyone). */
    private @Nullable Challenge findChallenge(String to, @Nullable String from) {
        Challenge found = null;
        for (Challenge c : challenges(to)) if (from == null || c.from().equals(from)) found = c;
        return found;
    }

    private void dropChallenge(Challenge challenge) {
        List<Challenge> list = challenges.get(challenge.to());
        if (list == null) return;
        list.remove(challenge);
        if (list.isEmpty()) challenges.remove(challenge.to());
    }

    /**
     * The challenged leader accepts: their online members against the challenging party's. The challenge stays open
     * when something that can change (a member still in a match, a leader offline) blocks the start.
     */
    public Outcome acceptChallenge(Player leader, @Nullable String fromPartyId) {
        Led led = led(leader);
        Party own = led.party();
        if (own == null) return led.error();
        Challenge challenge = findChallenge(own.id(), fromPartyId);
        if (challenge == null) return Outcome.of(Result.NO_CHALLENGE);
        Party from = parties.get(challenge.from());
        Kit kit = plugin.kits().get(challenge.kit());
        if (from == null || kit == null || !kit.enabled()) {
            dropChallenge(challenge);
            return from == null ? Outcome.of(Result.NO_PARTY, challenge.fromLeader()) : Outcome.of(Result.KIT_DISABLED);
        }
        Player fromLeader = Bukkit.getPlayer(from.leader());
        if (fromLeader == null) return Outcome.of(Result.LEADER_OFFLINE);
        Outcome blocked = startable(leader, kit);
        if (blocked != null) return blocked;
        List<Player> a = new ArrayList<>();
        List<Player> b = new ArrayList<>();
        Outcome busy = fighters(from, a);
        if (busy == null) busy = fighters(own, b);
        if (busy != null) return busy;
        if (a.isEmpty() || b.isEmpty()) return Outcome.of(Result.TOO_FEW);
        dropChallenge(challenge);
        Match match = plugin.matches().create(List.of(a, b), kit, false, Match.Origin.PARTY);
        if (match == null) return Outcome.of(Result.FAILED);
        announceTeams(match);
        watch(match, Mode.PVP, List.of(from.id(), own.id()), List.of(fromLeader.getName(), leader.getName()));
        return Outcome.OK;
    }

    public Outcome denyChallenge(Player leader, @Nullable String fromPartyId) {
        Led led = led(leader);
        Party own = led.party();
        if (own == null) return led.error();
        Challenge challenge = findChallenge(own.id(), fromPartyId);
        if (challenge == null) return Outcome.of(Result.NO_CHALLENGE);
        dropChallenge(challenge);
        Party from = parties.get(challenge.from());
        Player fromLeader = from == null ? null : Bukkit.getPlayer(from.leader());
        if (fromLeader != null) plugin.messages().send(fromLeader, "party.challenge-denied", Messages.text("leader", leader.getName()));
        plugin.messages().send(leader, "party.denied");
        return Outcome.OK;
    }

    /** Tells the parties how their match ended (once everyone is back in the hub). */
    private void watch(Match match, Mode mode, List<String> partyIds, List<String> leaders) {
        match.onEnd(m -> {
            Match.EndReason reason = m.endReason();
            int w = m.winnerTeam();
            String key;
            List<TagResolver> tags = new ArrayList<>();
            tags.add(Messages.comp("kit", m.kit().displayName()));
            tags.add(Messages.comp("kit_icon", m.kit().sprite()));
            if (reason == Match.EndReason.CANCELLED || reason == Match.EndReason.NO_ARENA) {
                key = "party.result-cancelled";
            } else if (w < 0) {
                key = "party.result-draw";
            } else {
                key = switch (mode) {
                    case FFA -> "party.result-ffa";
                    case SPLIT -> "party.result-split";
                    case PVP -> "party.result-pvp";
                };
                String winner = mode == Mode.PVP && w < leaders.size() ? leaders.get(w) : m.teamName(w);
                String loser = mode == Mode.PVP && 1 - w >= 0 && 1 - w < leaders.size() ? leaders.get(1 - w) : m.teamName(1 - w);
                tags.add(Messages.text("winner", winner));
                tags.add(Messages.text("loser", loser));
                tags.add(Messages.num("winner_score", m.score(w)));
                tags.add(Messages.num("loser_score", m.score(1 - w)));
            }
            TagResolver[] resolvers = tags.toArray(TagResolver[]::new);
            for (String id : partyIds) {
                Party party = parties.get(id);
                if (party != null) notifyParty(party, null, key, resolvers);
            }
        });
    }

    // ------------------------------------------------------------------ notices & notifications

    private void notifyParty(Party party, @Nullable UUID except, String key, TagResolver... resolvers) {
        for (Party.Member m : party.members()) {
            if (m.uuid().equals(except)) continue;
            Player p = Bukkit.getPlayer(m.uuid());
            if (p != null) plugin.messages().send(p, key, resolvers);
        }
    }

    /** Remembers (in memory and dc_meta) why an offline player is no longer in their party. */
    private void storeNotice(Party.Member member, String what, String leader) {
        String text = what + ":" + leader;
        notices.put(member.uuid(), new PartyDao.Notice(member.id(), member.uuid(), text));
        int playerId = member.id();
        var dialect = plugin.database().dialect();
        write(c -> {
            PartyDao.setNotice(c, dialect, playerId, text);
            return null;
        });
    }

    private void deliverNotice(Player player) {
        PartyDao.Notice notice = notices.remove(player.getUniqueId());
        if (notice == null) return;
        int playerId = notice.playerId();
        write(c -> {
            PartyDao.clearNotice(c, playerId);
            return null;
        });
        String[] parts = notice.text().split(":", 2);
        String key = "kicked".equals(parts[0]) ? "party.notice-kicked" : "party.notice-disbanded";
        String leader = parts.length > 1 ? parts[1] : "?";
        // after the join messages, so it isn't missed
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) plugin.messages().send(player, key, Messages.text("leader", leader));
        }, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!loaded) return;
        Player player = event.getPlayer();
        deliverNotice(player);
        Party party = byMember.get(player.getUniqueId());
        Party.Member member = party == null ? null : party.member(player.getUniqueId());
        if (party == null || member == null) return;
        member.name(player.getName());
        if (party.isLeader(player.getUniqueId())) leaderAway.remove(party.id());
        else if (!online(party.leader())) leaderAway.putIfAbsent(party.id(), System.currentTimeMillis());
        notifyParty(party, player.getUniqueId(), "party.notify.online", Messages.text("player", player.getName()));
        Party.Member leader = party.leaderMember();
        plugin.messages().send(player, "party.welcome-back", Messages.text("leader", leader == null ? "?" : leader.name()),
            Messages.num("online", onlineCount(party)), Messages.num("size", party.size()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        invites.remove(uuid);
        lastAttempt.remove(uuid);
        Party party = byMember.get(uuid);
        if (party == null) return;
        notifyParty(party, uuid, "party.notify.offline", Messages.text("player", event.getPlayer().getName()));
        if (party.isLeader(uuid)) leaderAway.put(party.id(), System.currentTimeMillis());
    }

    /** Expires invites and challenges and replaces leaders who stay away (every second). */
    @Override
    public void run() {
        long now = System.currentTimeMillis();
        replaceAwayLeaders(now);
        for (Iterator<Map.Entry<UUID, List<Invite>>> it = invites.entrySet().iterator(); it.hasNext(); ) {
            List<Invite> list = it.next().getValue();
            list.removeIf(i -> {
                if (i.expires() >= now) return false;
                Player inviter = Bukkit.getPlayer(i.inviter());
                if (inviter != null) plugin.messages().send(inviter, "party.invite-expired", Messages.text("player", i.targetName()));
                return true;
            });
            if (list.isEmpty()) it.remove();
        }
        for (Iterator<Map.Entry<String, List<Challenge>>> it = challenges.entrySet().iterator(); it.hasNext(); ) {
            List<Challenge> list = it.next().getValue();
            list.removeIf(c -> {
                if (c.expires() >= now) return false;
                Party from = parties.get(c.from());
                Player leader = from == null ? null : Bukkit.getPlayer(from.leader());
                if (leader != null) plugin.messages().send(leader, "party.challenge-expired", Messages.text("leader", c.toLeader()));
                return true;
            });
            if (list.isEmpty()) it.remove();
        }
        if (lastAttempt.size() > 256) lastAttempt.values().removeIf(t -> now - t > 60_000);
        if (!lastChallenge.isEmpty()) lastChallenge.values().removeIf(t -> now - t >= CHALLENGE_COOLDOWN_MS);
    }

    /**
     * A leader who has been offline for {@link #LEADER_AWAY_MS} hands the lead to an online member (the longest-standing
     * one), so a party whose leader never comes back isn't stuck. With nobody online it waits for the next member.
     */
    private void replaceAwayLeaders(long now) {
        if (!loaded) return;
        for (Iterator<Map.Entry<String, Long>> it = leaderAway.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> e = it.next();
            Party party = parties.get(e.getKey());
            if (party == null || online(party.leader())) {
                it.remove();
                continue;
            }
            if (now - e.getValue() < LEADER_AWAY_MS) continue;
            Party.Member next = Party.successor(party.members(), party.leader(), PartyService::online);
            if (next == null || !online(next.uuid())) {
                it.remove(); // nobody online: the next member to join starts the wait again
                continue;
            }
            it.remove();
            party.leader(next.uuid());
            persist(party);
            notifyParty(party, null, "party.notify.leader", Messages.text("player", next.name()));
        }
    }

    // ------------------------------------------------------------------ async hashing

    private CompletableFuture<String> hash(String password) {
        return offMain(() -> PartyPasswords.hash(password));
    }

    private CompletableFuture<Boolean> verify(String password, @Nullable String hash) {
        return offMain(() -> PartyPasswords.verify(password, hash));
    }

    /** Runs slow work on an async thread; the future completes back on the main thread. */
    private <T> CompletableFuture<T> offMain(java.util.function.Supplier<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            T value;
            try {
                value = work.get();
            } catch (RuntimeException e) {
                if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> future.completeExceptionally(e));
                return;
            }
            if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> future.complete(value));
        });
        return future;
    }

    /** Pending invites and challenges (for /duelcore debug style counts). */
    public int pending() {
        int n = 0;
        for (List<Invite> l : invites.values()) n += l.size();
        for (List<Challenge> l : challenges.values()) n += l.size();
        return n;
    }
}
