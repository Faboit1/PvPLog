package top.cheesesmp.duelcore.match;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.api.event.MatchEndEvent;
import top.cheesesmp.duelcore.api.event.MatchStartEvent;
import top.cheesesmp.duelcore.arena.ArenaInstance;
import top.cheesesmp.duelcore.config.MainConfig;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.db.dao.MatchDao;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.kit.KitManager;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.ProfileService;
import top.cheesesmp.duelcore.profile.ProgressTracker;
import top.cheesesmp.duelcore.profile.Setting;
import top.cheesesmp.duelcore.rating.RatingSystem;
import top.cheesesmp.duelcore.rating.Tier;
import top.cheesesmp.duelcore.ui.MatchFoundReveal;
import top.cheesesmp.duelcore.ui.MatchFx;
import top.cheesesmp.duelcore.ui.MatchFxMath;
import top.cheesesmp.duelcore.ui.MatchSounds;
import top.cheesesmp.duelcore.ui.SoundPool;
import top.cheesesmp.duelcore.ui.TotemPop;
import top.cheesesmp.duelcore.ui.anim.Channel;

/**
 * The match engine: creation, the per-tick state machine (countdown → fight → round end → …), deaths, forfeits,
 * ratings and cleanup. All state changes happen on the main thread; persistence is handed to the DB executor.
 */
public final class MatchService implements Runnable {

    /** Minimum ticks between "match found" and teleporting, so the totem pop plays in the hub. */
    private static final int FOUND_DELAY_TICKS = 20;
    /** Ticks after the match end before the auto-GG lines (after the results title and chat summary). */
    private static final long AUTO_GG_DELAY = 20L;
    private static final int ARENA_TIMEOUT_TICKS = 20 * 30;

    private final DuelCorePlugin plugin;
    private final Map<Integer, Match> matches = new LinkedHashMap<>();
    private final Map<UUID, Match> byPlayer = new HashMap<>();
    private final NamespacedKey freezeKey;
    private final MatchFoundReveal foundReveal;
    private int nextId = 1;
    private long created;
    private long finished;

    public MatchService(DuelCorePlugin plugin) {
        this.plugin = plugin;
        this.freezeKey = new NamespacedKey(plugin, "freeze");
        this.foundReveal = new MatchFoundReveal(plugin);
    }

    /** The animated "match found" title (also played by {@code /animtest play match-found}). */
    public MatchFoundReveal foundReveal() {
        return foundReveal;
    }

    // ------------------------------------------------------------------ queries

    public @Nullable Match match(UUID uuid) {
        return byPlayer.get(uuid);
    }

    public Collection<Match> active() {
        return matches.values();
    }

    public @Nullable Match byId(int id) {
        return matches.get(id);
    }

    public int count() {
        return matches.size();
    }

    public int count(String kit) {
        int n = 0;
        for (Match m : matches.values()) if (m.kit().id().equals(kit)) n++;
        return n;
    }

    public int playersInMatches() {
        return byPlayer.size();
    }

    public long created() {
        return created;
    }

    public long finished() {
        return finished;
    }

    // ------------------------------------------------------------------ creation

    /**
     * Starts a match between two teams. Returns null if a player is unavailable.
     * Queue entries, spectating and pending duel requests of every player are cleared.
     */
    public @Nullable Match create(List<List<Player>> teams, Kit kit, boolean ranked, Match.Origin origin) {
        return create(teams, kit, ranked, origin, false);
    }

    /**
     * Starts an unranked free-for-all: every player is their own team, one round, last one standing wins
     * ({@link Match#ffa()}). Returns null if a player is unavailable.
     */
    public @Nullable Match createFfa(List<Player> players, Kit kit, Match.Origin origin) {
        List<List<Player>> teams = new ArrayList<>();
        for (Player p : players) teams.add(List.of(p));
        return create(teams, kit, false, origin, true);
    }

    private @Nullable Match create(List<List<Player>> teams, Kit kit, boolean ranked, Match.Origin origin, boolean ffa) {
        List<Participant> participants = new ArrayList<>();
        for (int team = 0; team < teams.size(); team++) {
            for (Player p : teams.get(team)) {
                if (!p.isOnline() || byPlayer.containsKey(p.getUniqueId())) return null;
                PlayerProfile profile = plugin.profiles().get(p);
                if (profile == null) return null;
                KitStats stats = plugin.profiles().stats(profile, kit.id());
                participants.add(new Participant(p.getUniqueId(), p.getName(), team, profile.id(), stats.snapshot(),
                    plugin.tiers().kitTier(kit.id(), stats)));
            }
        }
        if (participants.size() < 2) return null;
        Match match = new Match(nextId++, kit, ranked && participants.size() == 2 && !ffa, origin, participants, ffa);
        matches.put(match.id(), match);
        created++;
        // both sides hear the same combination; it varies from match to match
        List<SoundPool.Played> foundSounds = plugin.settings().matchFoundSounds.pick(java.util.concurrent.ThreadLocalRandom.current());
        for (Participant p : participants) {
            byPlayer.put(p.uuid(), match);
            plugin.queue().removeAll(p.uuid());
            plugin.duels().clear(p.uuid());
            Player player = Bukkit.getPlayer(p.uuid());
            if (player == null) continue;
            if (plugin.spectate().spectating(p.uuid()) != null) plugin.spectate().leave(player, false);
            plugin.anim().cancel(player, Channel.DIALOG); // an animating queue menu must not open again
            // a post-match progress reveal still running from the last match makes way for this one
            plugin.anim().cancel(player, Channel.TITLE);
            plugin.anim().cancel(player, Channel.ACTION_BAR);
            plugin.anim().cancel(player, Channel.SOUND);
            plugin.openDialogs().close(player);
            player.setInvulnerable(true);
            player.getInventory().clear();
            player.setLevel(0); // the hub XP bar (matches never show it)
            player.setExp(0f);
            if (plugin.settings().totemPop && profileWants(player, Setting.MATCH_FOUND_POP)) TotemPop.play(plugin, player, kit.icon());
            MatchSounds.playMatch(plugin, player, foundSounds, 1);
            Participant opp = match.opponentOf(p);
            PlayerProfile oppProfile = opp == null ? null : plugin.profiles().get(opp.uuid());
            Component subtitle = plugin.messages().get(match.ffa() ? "party.match.found-ffa" : "match.found-subtitle",
                Messages.text("opponent", match.teamName(1 - p.team())),
                Messages.comp("tier", plugin.tiers().format(opp == null ? null : opp.tierBefore())),
                Messages.comp("kit", kit.displayName()),
                Messages.comp("kit_icon", kit.sprite()),
                Messages.text("mode", plugin.messages().raw("mode." + (match.ranked() ? "ranked" : "unranked"))),
                Messages.text("region", oppProfile == null || oppProfile.region() == null ? "" : oppProfile.region()),
                Messages.num("players", participants.size()));
            if (plugin.settings().animMatchFound) {
                foundReveal.play(player, match, subtitle); // MATCH FOUND sweeps in, then the opponent is typed out
            } else {
                player.showTitle(Title.title(plugin.messages().get("match.found-title"), subtitle,
                    Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1600), Duration.ofMillis(300))));
            }
            plugin.sidebar().refresh(player);
            plugin.tags().update(player); // the tag now shows this match's kit and tier
        }
        Bukkit.getPluginManager().callEvent(new MatchStartEvent(match));
        plugin.arenas().acquire(kit.arenaTags()).whenComplete((instance, error) ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING, "No arena for match #" + match.id(), error);
                    if (!match.isOver()) end(match, -1, Match.EndReason.NO_ARENA);
                    return;
                }
                if (match.isOver() || !matches.containsKey(match.id())) {
                    plugin.arenas().release(instance);
                    return;
                }
                match.arena = instance;
                match.arenaName = instance.template().name();
            }));
        verbose("created match #" + match.id() + " " + kit.id() + " " + (match.ranked() ? "ranked" : "unranked") + " "
            + match.teamName(0) + " vs " + match.teamName(1));
        return match;
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void run() {
        for (Match match : new ArrayList<>(matches.values())) {
            try {
                tick(match);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Match #" + match.id() + " crashed; cancelling it", t);
                try {
                    if (!match.isOver()) end(match, -1, Match.EndReason.CANCELLED);
                    finish(match);
                } catch (Throwable ignored) {
                    matches.remove(match.id());
                    match.participants().forEach(p -> byPlayer.remove(p.uuid()));
                }
            }
        }
    }

    private void tick(Match m) {
        m.stateTicks++;
        MainConfig cfg = plugin.settings();
        switch (m.state) {
            case STARTING -> {
                if (m.arena != null && m.stateTicks >= FOUND_DELAY_TICKS) startRound(m);
                else if (m.stateTicks > ARENA_TIMEOUT_TICKS) end(m, -1, Match.EndReason.NO_ARENA);
            }
            case PREPARING -> {
                // waiting for the between-round arena reset and the respawn animation (with a safety cap)
                if (!m.arenaResetting && (m.pulling <= 0 || m.stateTicks > 120)) beginCountdown(m);
            }
            case COUNTDOWN -> {
                int total = (m.round == 1 ? cfg.countdownSeconds : cfg.roundCountdownSeconds) * 20;
                int left = total - m.stateTicks;
                if (left > 0 && left % 20 == 0) {
                    int secs = left / 20;
                    // one round from winning: the subtitle says so (and pulses with the countdown animation)
                    List<Integer> matchPoint = cfg.animMatchPoint && !m.ffa() ? MatchFxMath.matchPoint(m.score, m.firstTo()) : List.of();
                    Component sub = matchPoint.isEmpty()
                        ? plugin.messages().get("match.countdown-sub", Messages.num("round", m.round), Messages.num("first_to", m.firstTo()))
                        : matchPoint.size() > 1 ? plugin.messages().get("match.fx.final-round", Messages.num("round", m.round))
                        : plugin.messages().get("match.fx.match-point", Messages.text("team", m.teamName(matchPoint.getFirst())),
                            Messages.num("round", m.round));
                    for (Player p : online(m)) {
                        if (plugin.animations().fx().countdown(p, secs, total / 20 - 1, sub, !matchPoint.isEmpty())) continue;
                        p.showTitle(Title.title(plugin.messages().get("match.countdown", Messages.num("seconds", secs)), sub,
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)));
                        sound(p, Sound.BLOCK_NOTE_BLOCK_HAT, 1f);
                    }
                }
                if (left <= 0) startFighting(m);
            }
            case FIGHTING -> {
                m.roundTicks++;
                int limit = m.kit().roundTimeLimitSeconds() * 20;
                if (limit > 0 && m.roundTicks >= limit) {
                    timeout(m);
                } else if (m.roundTicks % 5 == 0) {
                    boundsCheck(m);
                    if (cfg.animHeartbeat && m.state == Match.State.FIGHTING) heartbeats(m);
                }
            }
            case ROUND_END -> {
                if (m.stateTicks >= cfg.roundEndDelayTicks && !m.arenaResetting) {
                    if (cfg.resetBetweenRounds && m.arena != null) {
                        m.arenaResetting = true;
                        plugin.arenas().resetForNextRound(m.arena).whenComplete((r, e) -> m.arenaResetting = false);
                    }
                    startRound(m);
                }
            }
            case ENDING -> {
                if (m.stateTicks >= cfg.returnDelaySeconds * 20) finish(m);
            }
            case ENDED -> matches.remove(m.id());
        }
    }

    private void startRound(Match m) {
        ArenaInstance arena = m.arena;
        if (arena == null) return;
        m.round++;
        m.state = Match.State.PREPARING;
        m.stateTicks = 0;
        m.roundTicks = 0;
        arena.clearPlaced();
        for (Participant p : m.participants()) {
            p.resetRound();
            Player player = Bukkit.getPlayer(p.uuid());
            if (player == null || p.left()) {
                p.alive = false;
                continue;
            }
            Location spawn = spawnFor(m, p);
            player.setInvulnerable(true);
            Runnable arrive = () -> {
                if (!player.isOnline() || match(player.getUniqueId()) != m || m.isOver()) return;
                p.kitLayout = KitManager.apply(player, m.kit(), p.kitLayout); // chosen once per match
                player.setInvulnerable(true);
                freeze(player);
                applyBorder(player, arena);
                plugin.sidebar().refresh(player);
            };
            if (m.round > 1 && plugin.settings().animRespawnThrow && player.getWorld() == arena.world()) {
                // later rounds: a respawn animation (throw, look-down, spin) instead of a plain teleport (see
                // RespawnPull); no walking or jumping from its first tick on
                player.setFireTicks(0);
                player.getInventory().clear();
                freeze(player);
                m.pulling++;
                plugin.respawnPull().pull(player, spawn, plugin.settings().animRespawnThrowHeight, audience(m), () -> {
                    m.pulling--;
                    arrive.run();
                });
            } else if (plugin.settings().animSpawnRise) {
                // round 1 (or no throw): rise out of the ground at the spawn (see SpawnRise); waits for the round reset
                player.setFireTicks(0);
                player.getInventory().clear();
                m.pulling++;
                plugin.spawnRise().rise(player, spawn, audience(m), () -> !m.arenaResetting,
                    () -> match(player.getUniqueId()) == m && !m.isOver(), () -> {
                        m.pulling--;
                        arrive.run();
                    });
            } else {
                // leaving the hub (where everyone may fly) or a death cam: no flying in the arena
                player.setFlying(false);
                player.setAllowFlight(false);
                player.teleportAsync(spawn).thenRun(arrive);
            }
        }
        for (UUID s : m.spectators()) {
            Player sp = Bukkit.getPlayer(s);
            if (sp != null) plugin.sidebar().refresh(sp);
        }
    }

    private void beginCountdown(Match m) {
        m.state = Match.State.COUNTDOWN;
        m.stateTicks = 0;
    }

    private void startFighting(Match m) {
        m.state = Match.State.FIGHTING;
        m.stateTicks = 0;
        m.roundTicks = 0;
        if (m.firstFightAt == 0) m.firstFightAt = System.currentTimeMillis();
        for (Participant p : m.participants()) earlyLeaves.remove(p.uuid());
        List<SoundPool.Played> fightSounds = plugin.settings().fightStartSounds.pick(java.util.concurrent.ThreadLocalRandom.current());
        for (Participant p : m.participants()) {
            Player player = Bukkit.getPlayer(p.uuid());
            if (player == null || !p.alive) continue;
            unfreeze(player);
            player.setInvulnerable(false);
            plugin.animations().fightStart(player, audience(m));
            if (!plugin.animations().fx().fight(player)) {
                player.showTitle(Title.title(plugin.messages().get("match.fight"), Component.empty(),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(600), Duration.ofMillis(250))));
            }
            MatchSounds.playMatch(plugin, player, fightSounds, 0);
        }
    }

    private Location spawnFor(Match m, Participant p) {
        ArenaInstance arena = m.arena;
        if (m.teamCount() > 2) return ringSpawn(arena, p.team(), m.teamCount());
        Location base = arena.spawn(p.team());
        List<Participant> team = m.team(p.team());
        int index = team.indexOf(p);
        if (team.size() > 1) {
            // spread team mates sideways relative to the facing direction
            double offset = (index - (team.size() - 1) / 2.0) * 2.0;
            double yaw = Math.toRadians(base.getYaw());
            base.add(Math.cos(yaw) * offset, 0, Math.sin(yaw) * offset);
        }
        return base;
    }

    /**
     * Spawn of one team of a free-for-all: evenly spread on a ring between the two arena spawns ({@link Teams#ring}),
     * facing the middle, at least 6 blocks inside the arena, on the nearest ground where a player fits.
     */
    private static Location ringSpawn(ArenaInstance arena, int team, int teams) {
        Location a = arena.spawn(0);
        Location b = arena.spawn(1);
        double cx = (a.getX() + b.getX()) / 2;
        double cz = (a.getZ() + b.getZ()) / 2;
        double minX = arena.originX();
        double minZ = arena.originZ();
        double maxX = minX + arena.template().sizeX();
        double maxZ = minZ + arena.template().sizeZ();
        double room = Math.min(Math.min(cx - minX, maxX - cx), Math.min(cz - minZ, maxZ - cz)) - 6;
        double[] pos = Teams.ring(a.getX(), a.getZ(), b.getX(), b.getZ(), team, teams, room);
        int x = (int) Math.floor(pos[0]);
        int z = (int) Math.floor(pos[1]);
        int y = groundY(arena, x, z, (int) Math.floor((a.getY() + b.getY()) / 2));
        return new Location(arena.world(), x + 0.5, y, z + 0.5, (float) pos[2], 0f);
    }

    /** The feet Y nearest {@code startY} (±16) with solid ground below and two free blocks, inside the arena. */
    private static int groundY(ArenaInstance arena, int x, int z, int startY) {
        World world = arena.world();
        int min = arena.originY() + 1;
        int max = arena.originY() + arena.template().sizeY() - 2;
        for (int d = 0; d <= 16; d++) {
            int up = startY + d;
            if (up >= min && up <= max && standable(world, x, up, z)) return up;
            int down = startY - d;
            if (d > 0 && down >= min && down <= max && standable(world, x, down, z)) return down;
        }
        return Math.clamp(startY, min, Math.max(min, max));
    }

    private static boolean standable(World world, int x, int y, int z) {
        Material ground = world.getBlockAt(x, y - 1, z).getType();
        return ground.isSolid() && ground != Material.BARRIER && !Tag.LEAVES.isTagged(ground)
            && free(world.getBlockAt(x, y, z)) && free(world.getBlockAt(x, y + 1, z));
    }

    private static boolean free(Block block) {
        return block.isPassable() && !block.isLiquid();
    }

    private void applyBorder(Player player, ArenaInstance arena) {
        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(arena.originX() + arena.template().sizeX() / 2.0, arena.originZ() + arena.template().sizeZ() / 2.0);
        border.setSize(Math.max(arena.template().sizeX(), arena.template().sizeZ()) + 2);
        border.setWarningDistance(0);
        border.setDamageAmount(0);
        player.setWorldBorder(border);
    }

    // ------------------------------------------------------------------ freeze

    /**
     * Stops walking and jumping. Walk speed 0 (not a movement-speed modifier) avoids the client FOV zoom; the move
     * listener rolls back anything that still slips through.
     */
    public void freeze(Player player) {
        player.setSprinting(false);
        player.setWalkSpeed(0f);
        AttributeInstance jump = player.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null) {
            jump.removeModifier(freezeKey);
            jump.addTransientModifier(new AttributeModifier(freezeKey, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                EquipmentSlotGroup.ANY));
        }
    }

    public void unfreeze(Player player) {
        player.setWalkSpeed(0.2f);
        for (Attribute attribute : List.of(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH)) {
            AttributeInstance inst = player.getAttribute(attribute);
            if (inst != null) inst.removeModifier(freezeKey);
        }
    }

    /** True while players must stand still (before the fight starts). */
    public boolean isFrozen(Match m) {
        return m.state == Match.State.STARTING || m.state == Match.State.PREPARING || m.state == Match.State.COUNTDOWN;
    }

    // ------------------------------------------------------------------ deaths & rounds

    /** A participant died (lethal damage was cancelled by the listener, or they fell into the void). */
    public void onDeath(Player victim, @Nullable Player killer) {
        Match m = byPlayer.get(victim.getUniqueId());
        if (m == null || m.state != Match.State.FIGHTING) return;
        Participant dead = m.participant(victim.getUniqueId());
        if (dead == null || !dead.alive) return;
        dead.alive = false;
        Participant credited = null;
        if (killer != null) credited = m.participant(killer.getUniqueId());
        if (credited == null && dead.lastDamager != null && System.currentTimeMillis() - dead.lastDamagedAt < 15_000) {
            credited = m.participant(dead.lastDamager);
        }
        if (credited != null && credited.team() != dead.team()) credited.kills++;
        // death cam: spectate the rest of the round
        victim.setGameMode(GameMode.SPECTATOR);
        plugin.animations().death(victim.getLocation(), audience(m));
        for (Player p : online(m)) {
            plugin.messages().send(p, credited == null ? "match.death" : "match.death-by", Messages.text("victim", dead.name()),
                Messages.text("killer", credited == null ? "" : credited.name()),
                Messages.comp("hearts", killerHealth(credited)));
        }
        checkRoundOver(m);
        if (m.state == Match.State.FIGHTING) {
            // the round goes on: the killer's "+1 kill" bar, and how many are left in a free-for-all
            Player k = credited == null || credited.team() == dead.team() ? null : Bukkit.getPlayer(credited.uuid());
            if (k != null && !credited.left()) plugin.animations().fx().kill(k, credited.combo);
            if (m.ffa()) playersLeft(m);
        }
    }

    /** Party FFA: "3 players left" for everyone watching. */
    private void playersLeft(Match m) {
        int left = m.alive();
        for (Player p : online(m)) plugin.animations().fx().playersLeft(p, left);
    }

    /** Low-health heartbeat for fighters at or below animations.heartbeat-hearts (checked every 5 ticks). */
    private void heartbeats(Match m) {
        double threshold = plugin.settings().animHeartbeatHearts * 2;
        for (Participant p : m.participants()) {
            Player player = Bukkit.getPlayer(p.uuid());
            if (player == null) continue;
            double hp = player.getHealth() + player.getAbsorptionAmount();
            if (!p.alive || p.left() || !MatchFxMath.lowHealth(hp, threshold)) {
                clearBeat(p, player); // healed or dead: the last beat's HP is out of date
                continue;
            }
            if (m.roundTicks - p.lastBeat < MatchFxMath.heartbeatInterval(hp, threshold)) continue;
            p.lastBeat = m.roundTicks;
            p.beating = plugin.animations().fx().heartbeat(player, hp);
        }
    }

    /**
     * Takes a heartbeat's last (dim) frame off the action bar once no further beat follows, unless something else
     * is showing there now (it then replaced the frame; tried again on the next check).
     */
    private void clearBeat(Participant p, Player player) {
        if (!p.beating || plugin.anim().busy(player, Channel.ACTION_BAR)) return;
        p.beating = false;
        player.sendActionBar(Component.empty());
    }

    private Component killerHealth(@Nullable Participant credited) {
        if (credited == null) return Component.empty();
        Player k = Bukkit.getPlayer(credited.uuid());
        if (k == null) return Component.empty();
        double hp = k.getHealth() + k.getAbsorptionAmount();
        return plugin.messages().get("match.hearts", Messages.text("hp", String.format(java.util.Locale.ROOT, "%.1f", hp / 2.0)));
    }

    /** The round is over when at most one team has someone standing (none standing = a draw). */
    private void checkRoundOver(Match m) {
        boolean[] alive = new boolean[m.teamCount()];
        for (int t = 0; t < alive.length; t++) alive[t] = anyAlive(m, t);
        int outcome = Teams.outcome(alive);
        if (outcome != Teams.ONGOING) roundOver(m, outcome);
    }

    private static boolean anyAlive(Match m, int team) {
        for (Participant p : m.team(team)) if (p.alive && !p.left()) return true;
        return false;
    }

    /** Teams that still have someone in the match (who hasn't quit or forfeited). */
    private static boolean[] present(Match m) {
        boolean[] present = new boolean[m.teamCount()];
        for (Participant p : m.participants()) if (!p.left()) present[p.team()] = true;
        return present;
    }

    private void timeout(Match m) {
        int winner = -1;
        if ("health".equals(plugin.settings().timeoutDecision)) {
            // the clearly healthiest team takes the round; a tie (within 0.1 %) is a draw
            double[] health = new double[m.teamCount()];
            for (int t = 0; t < health.length; t++) health[t] = healthFraction(m, t);
            winner = Teams.best(health, 0.001);
        }
        for (Player p : online(m)) plugin.messages().send(p, "match.timeout");
        roundOver(m, winner);
    }

    private double healthFraction(Match m, int team) {
        double hp = 0;
        double max = 0;
        for (Participant p : m.team(team)) {
            Player player = Bukkit.getPlayer(p.uuid());
            if (player == null || !p.alive) continue;
            AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
            hp += player.getHealth();
            max += attr == null ? 20 : attr.getValue();
        }
        return max == 0 ? 0 : hp / max;
    }

    private void roundOver(Match m, int winnerTeam) {
        m.addRoundWinner(winnerTeam);
        if (winnerTeam >= 0) m.score[winnerTeam]++;
        m.state = Match.State.ROUND_END;
        m.stateTicks = 0;
        for (Participant p : m.participants()) {
            Player player = Bukkit.getPlayer(p.uuid());
            if (player == null) continue;
            player.setInvulnerable(true);
            if (p.alive) player.setFireTicks(0);
        }
        boolean decided = winnerTeam >= 0 && m.score[winnerTeam] >= m.firstTo();
        boolean capped = m.round >= plugin.settings().maxRounds;
        // the match goes on: a round banner (title) instead of the action bar line
        boolean banner = !decided && !capped && !m.ffa();
        for (Player p : online(m)) {
            Participant self = m.participant(p.getUniqueId());
            int you = self == null ? m.score[0] : m.score[self.team()];
            int opp = self == null ? m.score[1] : Teams.bestOther(m.score, self.team());
            plugin.anim().cancel(p, Channel.ACTION_BAR); // combo and heartbeat bars make way for the result
            if (self != null) clearBeat(self, p);
            if (banner && roundBanner(m, p, self, winnerTeam, you, opp)) {
                plugin.sidebar().refresh(p);
                continue;
            }
            String key = winnerTeam < 0 ? "match.round-draw" : (self != null && self.team() == winnerTeam ? "match.round-won" : "match.round-lost");
            if (self == null) key = "match.round-spectator";
            if (m.ffa() && winnerTeam >= 0) {
                key = self != null && self.team() == winnerTeam ? "party.match.ffa-won" : "party.match.ffa-lost";
            }
            p.sendActionBar(plugin.messages().get(key, Messages.num("you", you), Messages.num("opp", opp),
                Messages.num("round", m.round), Messages.text("winner", winnerTeam < 0 ? "" : m.teamName(winnerTeam))));
            boolean won = winnerTeam >= 0 && self != null && self.team() == winnerTeam;
            // a decided match plays the victory jingle / defeat notes instead (see celebrate)
            boolean jingle = decided && (self == null || won ? plugin.settings().animVictoryTitle : plugin.settings().animDefeatTitle);
            if (!jingle) sound(p, won ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_NOTE_BLOCK_BASS, 1f);
            plugin.sidebar().refresh(p);
        }
        if (winnerTeam >= 0) {
            for (Participant p : m.team(winnerTeam)) {
                Player w = Bukkit.getPlayer(p.uuid());
                if (w == null || p.left()) continue;
                if (decided) plugin.animations().matchWin(w, audience(m));
                else plugin.animations().roundWin(w, audience(m));
            }
        }
        if (decided) {
            end(m, winnerTeam, Match.EndReason.SCORE);
        } else if (m.ffa()) {
            end(m, -1, Match.EndReason.DRAW); // one round only: a replay would bring the eliminated back
        } else if (capped) {
            int w = Teams.leader(m.score);
            end(m, w, w < 0 ? Match.EndReason.DRAW : Match.EndReason.SCORE);
        }
    }

    /** The animated round banner for one viewer; false when it is switched off. */
    private boolean roundBanner(Match m, Player viewer, @Nullable Participant self, int winnerTeam, int you, int opp) {
        MatchFx.Banner kind = self == null ? MatchFx.Banner.SPECTATOR : winnerTeam < 0 ? MatchFx.Banner.DRAW
            : self.team() == winnerTeam ? MatchFx.Banner.WON : MatchFx.Banner.LOST;
        int[] before = {you, opp};
        if (winnerTeam >= 0) {
            int scored = self == null ? (winnerTeam == 0 ? 0 : 1) : (self.team() == winnerTeam ? 0 : 1);
            before[scored]--;
        }
        return plugin.animations().fx().roundBanner(viewer, kind, before, new int[] {you, opp}, m.round,
            winnerTeam < 0 ? "" : m.teamName(winnerTeam));
    }

    private void boundsCheck(Match m) {
        ArenaInstance arena = m.arena;
        if (arena == null) return;
        for (Participant p : m.participants()) {
            if (!p.alive) continue;
            Player player = Bukkit.getPlayer(p.uuid());
            if (player == null) continue;
            Location loc = player.getLocation();
            if (loc.getWorld() != arena.world()) continue;
            if (loc.getY() < arena.floorY() - plugin.settings().voidDepth) {
                Player killer = p.lastDamager == null ? null : Bukkit.getPlayer(p.lastDamager);
                onDeath(player, killer);
            } else if (!arena.containsXZ(loc.getX(), loc.getZ(), 2)) {
                Location back = spawnFor(m, p);
                back.setYaw(loc.getYaw());
                back.setPitch(loc.getPitch());
                player.teleportAsync(back);
                p.beating = false; // (this replaced a heartbeat frame: not to be cleared later)
                plugin.messages().actionBar(player, "match.out-of-bounds");
            }
        }
    }

    // ------------------------------------------------------------------ forfeit / quit

    /** A participant left the server or used /leave: they lose (or drop out, in team games). */
    public void forfeit(Player player, boolean quit) {
        forfeit(player, quit, false);
    }

    /** Early leaves in a row per player (reset once they play a fight); see {@link #leaveBeforeStart}. */
    private final Map<UUID, Integer> earlyLeaves = new HashMap<>();

    /**
     * /leave before the first fight of a 1v1 has started: the match ends with no result at once (no confirmation,
     * nothing saved, no Elo) and the leaver is not queued again by Keep Queuing; the opponent is, as after any match.
     * Allowed {@code match.leave-before-start-max} times in a row (a fight played resets it); false when it doesn't
     * apply, so the normal forfeit follows.
     */
    public boolean leaveBeforeStart(Player player) {
        Match m = byPlayer.get(player.getUniqueId());
        if (m == null || m.isOver() || m.firstFightAt != 0 || m.ffa() || m.participants().size() != 2) return false;
        int used = earlyLeaves.getOrDefault(player.getUniqueId(), 0);
        if (used >= plugin.settings().leaveBeforeStartMax) return false;
        Participant p = m.participant(player.getUniqueId());
        if (p == null) return false;
        earlyLeaves.put(player.getUniqueId(), used + 1);
        p.left = true;
        p.alive = false;
        byPlayer.remove(player.getUniqueId());
        plugin.queue().forget(player.getUniqueId());
        plugin.messages().send(player, "match.left-before-start",
            Messages.num("left", Math.max(0, plugin.settings().leaveBeforeStartMax - used - 1)));
        for (Player other : online(m)) {
            if (other != player) plugin.messages().send(other, "match.opponent-left-before-start", Messages.text("player", p.name()));
        }
        end(m, -1, Match.EndReason.LEFT_BEFORE_START);
        plugin.hub().send(player);
        return true;
    }

    /**
     * {@code connectionLost}: the player quit because their connection dropped. In a ranked 1v1 that uses one of their
     * disconnect saves when they have one left: the match ends with no result and nobody's Elo changes.
     */
    public void forfeit(Player player, boolean quit, boolean connectionLost) {
        Match m = byPlayer.get(player.getUniqueId());
        if (m == null) return;
        Participant p = m.participant(player.getUniqueId());
        if (p == null) return;
        if (m.isOver()) {
            if (quit) byPlayer.remove(player.getUniqueId());
            return;
        }
        if (quit && connectionLost && m.ranked() && m.participants().size() == 2 && !m.ffa()
            && plugin.disconnectSaves().tryUse(player.getUniqueId())) {
            p.left = true;
            p.alive = false;
            byPlayer.remove(player.getUniqueId());
            int left = plugin.disconnectSaves().remaining(player.getUniqueId());
            plugin.getLogger().info("[match] " + p.name() + " lost connection in ranked match #" + m.id()
                + ": disconnect save used, no Elo change (" + left + " left today)");
            for (Player other : online(m)) {
                if (other != player) plugin.messages().send(other, "match.opponent-connection-lost", Messages.text("player", p.name()));
            }
            end(m, -1, Match.EndReason.CONNECTION_LOST);
            return;
        }
        p.left = true;
        p.alive = false;
        if (quit) byPlayer.remove(player.getUniqueId());
        else plugin.queue().forget(player.getUniqueId()); // left on purpose: Keep Queuing doesn't bring back the old kits
        for (Player other : online(m)) {
            if (other != player) plugin.messages().send(other, quit ? "match.opponent-quit" : "match.opponent-forfeit",
                Messages.text("player", p.name()));
        }
        boolean teamLeft = true;
        for (Participant mate : m.team(p.team())) if (!mate.left()) teamLeft = false;
        // with two teams the other one wins; with more (free-for-all) only once a single team is still there
        int winner = !teamLeft ? Teams.ONGOING : m.teamCount() == 2 ? 1 - p.team() : Teams.outcome(present(m));
        if (winner != Teams.ONGOING) {
            end(m, winner, quit ? Match.EndReason.FORFEIT_QUIT : Match.EndReason.FORFEIT_COMMAND);
        } else if (m.state == Match.State.FIGHTING) {
            checkRoundOver(m);
            if (m.ffa() && m.state == Match.State.FIGHTING) playersLeft(m);
        }
        if (!quit) {
            byPlayer.remove(player.getUniqueId());
            plugin.hub().send(player);
        }
    }

    // ------------------------------------------------------------------ end

    /** Decides the match. winnerTeam -1 = draw / no result. */
    public void end(Match m, int winnerTeam, Match.EndReason reason) {
        if (m.isOver()) return;
        m.state = Match.State.ENDING;
        m.stateTicks = 0;
        m.winnerTeam = winnerTeam;
        m.endReason = reason;
        boolean rated = m.ranked() && reason.countsForRating() && m.participants().size() == 2
            && (winnerTeam >= 0 || reason == Match.EndReason.DRAW);
        List<ProfileService.RatingWrite> writes = new ArrayList<>();
        if (rated) applyRatings(m, winnerTeam, writes);
        else plugin.tester().afterMatch(m);
        for (Participant p : m.participants()) {
            Player player = Bukkit.getPlayer(p.uuid());
            if (player == null) continue;
            plugin.respawnPull().abort(p.uuid());
            plugin.spawnRise().abort(p.uuid());
            player.setInvulnerable(true);
            unfreeze(player);
            if (p.alive && player.getGameMode() == GameMode.SPECTATOR) player.setGameMode(GameMode.SURVIVAL);
        }
        if (reason != Match.EndReason.CANCELLED && reason != Match.EndReason.NO_ARENA && m.firstFightAt > 0) {
            persist(m, writes);
        } else if (!writes.isEmpty()) {
            plugin.profiles().persistMatch(null, writes, uuids(m));
        }
        Bukkit.getPluginManager().callEvent(new MatchEndEvent(m));
        // countdown, FIGHT!, round banner and bar animations still running would draw over the results title (and
        // keep the victory / defeat animations from starting)
        for (Player p : online(m)) {
            plugin.anim().cancel(p, Channel.TITLE);
            plugin.anim().cancel(p, Channel.ACTION_BAR);
            Participant self = m.participant(p.getUniqueId());
            if (self != null) clearBeat(self, p);
        }
        plugin.results().show(m);
        if (reason != Match.EndReason.CANCELLED && reason != Match.EndReason.NO_ARENA) celebrate(m);
        verbose("ended match #" + m.id() + " winner=" + winnerTeam + " reason=" + reason + " score=" + m.score[0] + "-"
            + m.score[1] + " rounds=" + m.roundString());
    }

    /** After the results title: victory / defeat animations, confetti over the winners, the spectators' banner. */
    private void celebrate(Match m) {
        int winner = m.winnerTeam();
        List<Player> audience = audience(m);
        autoGg(m, audience);
        for (Participant p : m.participants()) {
            Player player = Bukkit.getPlayer(p.uuid());
            if (player == null || p.left() || winner < 0) continue;
            if (p.team() == winner) {
                plugin.animations().fx().victory(player);
                plugin.animations().confetti(player, audience);
            } else {
                plugin.animations().fx().defeat(player);
            }
        }
        for (UUID s : m.spectators()) {
            Player sp = Bukkit.getPlayer(s);
            if (sp != null) plugin.animations().fx().spectatorResult(sp, winner < 0 ? null : m.teamName(winner), m.score(0),
                m.score(1), m.ffa());
        }
    }

    /**
     * Fighters with {@link Setting#AUTO_GG} say "gg" to the match (its fighters and spectators, nobody else) a moment
     * after the results: messages.yml {@code match.auto-gg}.
     */
    private void autoGg(Match m, List<Player> audience) {
        List<String> names = new ArrayList<>();
        for (Participant p : m.participants()) {
            Player player = Bukkit.getPlayer(p.uuid());
            if (player != null && !p.left() && profileWants(player, Setting.AUTO_GG)) names.add(player.getName());
        }
        if (names.isEmpty()) return;
        List<Player> to = List.copyOf(audience);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (String name : names) {
                for (Player v : to) if (v.isOnline()) plugin.messages().send(v, "match.auto-gg", Messages.text("player", name));
            }
        }, AUTO_GG_DELAY);
    }

    private void applyRatings(Match m, int winnerTeam, List<ProfileService.RatingWrite> writes) {
        Participant a = m.team(0).getFirst();
        Participant b = m.team(1).getFirst();
        double scoreA = winnerTeam == 0 ? 1 : winnerTeam == 1 ? 0 : 0.5;
        RatingSystem.Result result = plugin.ratingSystem().rate(a.before(), b.before(), scoreA);
        long now = System.currentTimeMillis();
        for (Participant p : List.of(a, b)) {
            RatingSystem.Side side = p == a ? result.a() : result.b();
            PlayerProfile profile = plugin.profiles().get(p.uuid());
            if (profile == null) continue;
            KitStats stats = plugin.profiles().stats(profile, m.kit().id());
            stats.rating = side.rating();
            stats.rd = side.rd();
            stats.volatility = side.volatility();
            if (winnerTeam < 0) {
                stats.games++;
                stats.streak = 0;
            } else {
                stats.recordResult(p.team() == winnerTeam);
            }
            stats.peak = Math.max(stats.peak, stats.rating);
            stats.updatedAt = now;
            p.ratingAfter = stats.rating;
            p.tierAfter = plugin.tiers().kitTier(m.kit().id(), stats);
            // remembered for the post-match action bar and the queue menu's progress animation
            plugin.progress().record(p.uuid(), ProgressTracker.Reveal.of(m.kit().id(), p.before(), stats,
                plugin.tiers().placementMatches(), p.tierBefore(), p.tierAfter, false));
            plugin.tiers().refresh(profile);
            writes.add(new ProfileService.RatingWrite(profile.id(), m.kit().id(), stats.snapshot(), profile.elo(),
                profile.overall()));
            profile.recent(null);
        }
    }

    private void persist(Match m, List<ProfileService.RatingWrite> writes) {
        if (m.persisted) return;
        m.persisted = true;
        int kitId;
        try {
            kitId = plugin.profiles().kitId(m.kit().id());
        } catch (IllegalStateException e) {
            plugin.getLogger().warning("Match #" + m.id() + " not saved: " + e.getMessage());
            return;
        }
        List<MatchDao.ParticipantRecord> records = new ArrayList<>();
        for (Participant p : m.participants()) {
            float before = (float) p.ratingBefore();
            float after = Double.isNaN(p.ratingAfter()) ? before : (float) p.ratingAfter();
            records.add(new MatchDao.ParticipantRecord(p.profileId(), p.team(), m.score[p.team()], p.hits(),
                (float) p.damageDealt(), (float) p.damageTaken(), before, after));
        }
        long duration = m.firstFightAt == 0 ? 0 : System.currentTimeMillis() - m.firstFightAt;
        MatchDao.MatchRecord record = new MatchDao.MatchRecord(plugin.profiles().season().id(), kitId, m.ranked(),
            m.arenaName == null ? "?" : m.arenaName, m.createdAt(), (int) Math.min(Integer.MAX_VALUE, duration),
            m.endReason().id(), m.winnerTeam(), m.firstTo(), m.roundString(), records);
        plugin.profiles().persistMatch(record, writes, uuids(m)).whenComplete((id, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not save match #" + m.id(), error);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> plugin.leaderboards().invalidate(m.kit().id()));
            verbose("saved match #" + m.id() + " as id " + id);
        });
    }

    private static List<UUID> uuids(Match m) {
        return m.participants().stream().map(Participant::uuid).toList();
    }

    /** Sends everyone back and releases the arena. */
    private void finish(Match m) {
        if (m.state == Match.State.ENDED) return;
        m.state = Match.State.ENDED;
        finished++;
        for (Participant p : m.participants()) {
            if (byPlayer.get(p.uuid()) == m) byPlayer.remove(p.uuid());
            Player player = Bukkit.getPlayer(p.uuid());
            if (player != null && !p.left() && byPlayer.get(p.uuid()) == null) plugin.hub().send(player);
        }
        for (UUID s : new ArrayList<>(m.spectators())) {
            Player sp = Bukkit.getPlayer(s);
            if (sp != null) plugin.spectate().leave(sp, true);
        }
        m.spectators().clear();
        if (m.arena != null) {
            plugin.arenas().release(m.arena);
            m.arena = null;
        }
        matches.remove(m.id());
        runEndListeners(m);
    }

    private void runEndListeners(Match m) {
        for (var listener : m.endListeners()) {
            try {
                listener.accept(m);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Match end listener failed", t);
            }
        }
    }

    /** Ends every match without rating changes (plugin disable / admin). */
    public void cancelAll() {
        for (Match m : new ArrayList<>(matches.values())) {
            if (!m.isOver()) end(m, -1, Match.EndReason.CANCELLED);
            for (Participant p : m.participants()) {
                Player player = Bukkit.getPlayer(p.uuid());
                byPlayer.remove(p.uuid());
                if (player != null) {
                    unfreeze(player);
                    player.setWorldBorder(null);
                    KitManager.resetState(player, 20);
                    player.teleport(plugin.hub().spawn());
                }
            }
            for (UUID s : m.spectators()) {
                Player sp = Bukkit.getPlayer(s);
                if (sp != null) sp.teleport(plugin.hub().spawn());
            }
            m.state = Match.State.ENDED;
            runEndListeners(m); // e.g. Keep Queuing forgets the kits chosen before this match
        }
        matches.clear();
        byPlayer.clear();
    }

    // ------------------------------------------------------------------ helpers

    /** Everyone who sees this match: online fighters plus spectators (the same list as {@link #online}). */
    public List<Player> audience(Match m) {
        return online(m);
    }

    /** Online participants (still in this match) and spectators. */
    public List<Player> online(Match m) {
        List<Player> list = new ArrayList<>();
        for (Participant p : m.participants()) {
            Player player = Bukkit.getPlayer(p.uuid());
            if (player != null && byPlayer.get(p.uuid()) == m) list.add(player);
        }
        for (UUID s : m.spectators()) {
            Player player = Bukkit.getPlayer(s);
            if (player != null) list.add(player);
        }
        return list;
    }

    /** A plain match sound (countdown tick, round result), unless the player turned match sounds off. */
    private void sound(Player p, Sound sound, float pitch) {
        if (!MatchSounds.matchSounds(plugin, p)) return;
        p.playSound(p.getLocation(), sound, 0.7f, pitch);
    }

    /** A player's setting (its default for a player without a loaded profile). */
    private boolean profileWants(Player player, Setting setting) {
        PlayerProfile profile = plugin.profiles().get(player);
        return profile == null ? setting.defaultValue() : profile.setting(setting);
    }

    public @Nullable Tier tierOf(Participant p, boolean after) {
        return after ? p.tierAfter() : p.tierBefore();
    }

    private void verbose(String msg) {
        if (plugin.settings().verbose) plugin.getLogger().info("[match] " + msg);
    }
}
