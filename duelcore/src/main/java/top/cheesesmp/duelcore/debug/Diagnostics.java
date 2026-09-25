package top.cheesesmp.duelcore.debug;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.World;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.arena.ArenaInstance;

/**
 * Runtime health numbers for /duelcore debug: everything needed to spot leaks (instances, chunks, entities, tasks,
 * caches, heap) and to prove database work stays off the main thread.
 */
public final class Diagnostics {

    private final DuelCorePlugin plugin;

    public Diagnostics(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** One line per live match: state, round, score and every participant's flags. */
    public List<String> matches() {
        List<String> out = new ArrayList<>();
        for (top.cheesesmp.duelcore.match.Match m : plugin.matches().active()) {
            StringBuilder sb = new StringBuilder("#" + m.id() + " " + m.kit().id() + (m.ranked() ? " ranked " : " unranked ")
                + m.state() + " round=" + m.round() + " score=" + m.score(0) + "-" + m.score(1) + " ticks=" + m.roundTicks()
                + " arena=" + (m.arena() == null ? "-" : m.arena().template().name() + "#" + m.arena().id()));
            for (top.cheesesmp.duelcore.match.Participant p : m.participants()) {
                org.bukkit.entity.Player pl = org.bukkit.Bukkit.getPlayer(p.uuid());
                sb.append(" | ").append(p.name()).append(" t").append(p.team()).append(p.alive() ? " alive" : " dead")
                    .append(p.left() ? " left" : "").append(" hits=").append(p.hits())
                    .append(pl == null ? " offline" : String.format(Locale.ROOT, " hp=%.1f", pl.getHealth()));
            }
            out.add(sb.toString());
        }
        if (out.isEmpty()) out.add("no live matches");
        return out;
    }

    /** Client, connection and state of one player. */
    public List<String> player(org.bukkit.entity.Player p) {
        List<String> out = new ArrayList<>();
        java.util.UUID uuid = p.getUniqueId();
        out.add(p.getName() + " " + ClientInfo.describe(p) + ", ping " + p.getPing() + " ms");
        top.cheesesmp.duelcore.match.Match m = plugin.matches().match(uuid);
        top.cheesesmp.duelcore.match.Match watching = plugin.spectate().spectating(uuid);
        String music = plugin.queueMusic().track(uuid);
        out.add("state " + (m != null ? "match #" + m.id() + " " + m.state() : watching != null ? "spectating #" + watching.id()
            : "hub") + ", queues " + plugin.queue().entries(uuid).size() + (music == null ? "" : ", music " + music)
            + ", " + p.getGameMode() + (p.getAllowFlight() ? ", may fly" : "") + (p.isFlying() ? " (flying)" : "")
            + ", world " + p.getWorld().getName());
        out.add(plugin.openDialogs().describe(p));
        return out;
    }

    public List<String> report(boolean gc) {
        List<String> out = new ArrayList<>();
        if (gc) System.gc();
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        var db = plugin.database();
        var arenas = plugin.arenas();
        Map<ArenaInstance.State, Integer> states = new EnumMap<>(ArenaInstance.State.class);
        for (ArenaInstance i : arenas.instances()) states.merge(i.state(), 1, Integer::sum);
        World aw = arenas.world();
        int arenaChunks = aw == null ? 0 : aw.getLoadedChunks().length;
        int arenaEntities = aw == null ? 0 : aw.getEntities().size();
        int arenaPlayers = aw == null ? 0 : aw.getPlayers().size();
        int ticketChunks = aw == null ? 0 : aw.getPluginChunkTickets().getOrDefault(plugin, List.of()).size();
        long tasks = Bukkit.getScheduler().getPendingTasks().stream().filter(t -> t.getOwner() == plugin).count();
        out.add("matches live=" + plugin.matches().count() + " players=" + plugin.matches().playersInMatches()
            + " created=" + plugin.matches().created() + " finished=" + plugin.matches().finished()
            + " spectators=" + plugin.spectate().count());
        out.add("queue players=" + plugin.queue().totalQueued() + " music=" + plugin.queueMusic().playingCount()
            + " buckets=" + plugin.queue().bucketCount()
            + " pairings=" + plugin.queue().pairingsMade() + " rematchKeys=" + plugin.queue().rematches().size()
            + " duelRequests=" + plugin.duels().pending());
        out.add("arenas templates=" + arenas.templates().size() + " instances=" + arenas.instances().size() + " " + states
            + " idle=" + arenas.idleCount() + " waiting=" + arenas.waitingCount() + " slots=" + arenas.slotsInUse());
        out.add("arenaJobs queued=" + arenas.queue().size() + " done=" + arenas.queue().completed() + " pastes=" + arenas.pastes()
            + " resets=" + arenas.resets() + " clears=" + arenas.clears() + " dirtyResets=" + arenas.dirtyResets()
            + " restored=" + arenas.restored() + " pregenSlots=" + arenas.pregeneratedSlots()
            + " pregenChunks=" + arenas.pregeneratedChunks());
        out.add("arenaWorld chunks=" + arenaChunks + " ticketChunks=" + ticketChunks + " entities=" + arenaEntities
            + " players=" + arenaPlayers);
        out.add("db executed=" + db.executed() + " mainThread=" + db.mainThreadExecutions() + " failures=" + db.failures()
            + " pending=" + db.pending() + String.format(Locale.ROOT, " avgMs=%.2f", db.averageMillis())
            + " pendingWrites=" + plugin.profiles().pendingWrites());
        out.add("caches profiles=" + plugin.profiles().cacheSize() + " online=" + Bukkit.getOnlinePlayers().size()
            + " boards=" + plugin.sidebar().boards().size() + " leaderboards=" + plugin.leaderboards().cachedBoards()
            + " results=" + plugin.results().pendingCount() + " editors=" + plugin.editor().sessionCount()
            + " reveals=" + plugin.progress().size() + " testers=" + plugin.tester().online());
        out.add("animations " + plugin.anim().stats());
        out.add("dialogs " + plugin.openDialogs().stats());
        out.add("server tasks(plugin)=" + tasks + " heapUsedMB=" + used + " heapMaxMB=" + rt.maxMemory() / (1024 * 1024)
            + String.format(Locale.ROOT, " tps=%.2f mspt=%.2f", Bukkit.getTPS()[0], Bukkit.getAverageTickTime()));
        for (World w : Bukkit.getWorlds()) {
            out.add("world " + w.getName() + " chunks=" + w.getLoadedChunks().length + " entities=" + w.getEntities().size()
                + " players=" + w.getPlayers().size());
        }
        return out;
    }
}
