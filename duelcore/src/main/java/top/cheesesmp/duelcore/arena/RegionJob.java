package top.cheesesmp.duelcore.arena;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

/**
 * Makes a world region match a snapshot exactly: paste, between-round reset, post-match reset and clear all use
 * this. Each pass captures chunk snapshots (main thread, cheap), diffs them against the target off-thread, then
 * applies only the differing blocks within the per-tick budget. Passes repeat until one finds nothing to fix
 * (fluids that kept flowing during a pass are caught by the next), max {@value #MAX_PASSES}.
 */
public final class RegionJob {

    public record Result(int passes, int blocksChanged, int entitiesRemoved, long millis, boolean clean, long loadMillis,
                         long diffMillis, int ticks) {
    }

    enum Phase { LOADING, SNAPSHOT, DIFFING, APPLY, ENTITIES, DONE }

    private static final int MAX_PASSES = 3;

    private final Plugin plugin;
    private final World world;
    private final int ox;
    private final int oy;
    private final int oz;
    private final ArenaSnapshot target;
    private final BlockData[] palette;
    private final Executor worker;
    private final boolean holdTickets;
    private final boolean releaseTickets;
    private final CompletableFuture<Result> future = new CompletableFuture<>();
    private final long started = System.currentTimeMillis();

    private final List<long[]> chunkCoords = new ArrayList<>();
    private final List<Chunk> chunks = new ArrayList<>();
    private final Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
    private volatile Phase phase = Phase.LOADING;
    private int snapshotIndex;
    private volatile int[] changePositions = new int[0];
    private volatile BlockData[] changeData = new BlockData[0];
    private int applyIndex;
    private int passes;
    private int totalChanged;
    private int entitiesRemoved;
    private volatile Throwable failure;
    private volatile long loadedAt;
    private volatile long diffNanos;
    private int ticks;

    /**
     * @param holdTickets    add plugin chunk tickets (instance is starting to use this region)
     * @param releaseTickets remove tickets and request unload when done (instance is being destroyed)
     */
    public RegionJob(Plugin plugin, World world, int ox, int oy, int oz, ArenaSnapshot target, BlockData[] palette,
                     Executor worker, boolean holdTickets, boolean releaseTickets) {
        this.plugin = plugin;
        this.world = world;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.target = target;
        this.palette = palette;
        this.worker = worker;
        this.holdTickets = holdTickets;
        this.releaseTickets = releaseTickets;
        int minCx = ox >> 4;
        int maxCx = (ox + target.sizeX() - 1) >> 4;
        int minCz = oz >> 4;
        int maxCz = (oz + target.sizeZ() - 1) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) chunkCoords.add(new long[] {cx, cz});
        }
    }

    public CompletableFuture<Result> future() {
        return future;
    }

    public Phase phase() {
        return phase;
    }

    /** Kicks off async chunk loading. Called once on the main thread. */
    void begin() {
        List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        for (long[] c : chunkCoords) loads.add(world.getChunkAtAsync((int) c[0], (int) c[1], true, true));
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenComplete((v, err) -> {
            if (err != null) {
                failure = err;
                return;
            }
            // chunk futures complete on the main thread
            for (CompletableFuture<Chunk> f : loads) {
                Chunk chunk = f.join();
                chunks.add(chunk);
                if (holdTickets) chunk.addPluginChunkTicket(plugin);
            }
            loadedAt = System.currentTimeMillis();
            phase = Phase.SNAPSHOT;
        });
    }

    /**
     * Advances the job until the deadline. Main thread only.
     *
     * @return true when finished (successfully or not)
     */
    boolean step(long deadlineNanos) {
        ticks++;
        if (failure != null) {
            future.completeExceptionally(failure);
            phase = Phase.DONE;
            return true;
        }
        switch (phase) {
            case LOADING, DIFFING -> {
                return false;
            }
            case SNAPSHOT -> {
                while (snapshotIndex < chunks.size()) {
                    Chunk chunk = chunks.get(snapshotIndex++);
                    snapshots.put(key(chunk.getX(), chunk.getZ()), chunk.getChunkSnapshot(false, false, false, false));
                    if (System.nanoTime() > deadlineNanos) return false;
                }
                phase = Phase.DIFFING;
                worker.execute(this::diff);
                return false;
            }
            case APPLY -> {
                int[] pos = changePositions;
                BlockData[] data = changeData;
                while (applyIndex < pos.length) {
                    int flat = pos[applyIndex];
                    int x = flat % target.sizeX();
                    int rest = flat / target.sizeX();
                    int z = rest % target.sizeZ();
                    int y = rest / target.sizeZ();
                    world.getBlockAt(ox + x, oy + y, oz + z).setBlockData(data[applyIndex], false);
                    applyIndex++;
                    if ((applyIndex & 63) == 0 && System.nanoTime() > deadlineNanos) return false;
                }
                totalChanged += pos.length;
                passes++;
                if (pos.length > 0 && passes < MAX_PASSES) {
                    // verify: take fresh snapshots and diff again
                    snapshots.clear();
                    snapshotIndex = 0;
                    applyIndex = 0;
                    phase = Phase.SNAPSHOT;
                    return false;
                }
                phase = Phase.ENTITIES;
                return false;
            }
            case ENTITIES -> {
                BoundingBox box = new BoundingBox(ox - 3, oy - 16, oz - 3,
                    ox + target.sizeX() + 3, oy + target.sizeY() + 32, oz + target.sizeZ() + 3);
                for (Entity entity : world.getNearbyEntities(box)) {
                    if (entity instanceof Player || entity.getScoreboardTags().contains("duelcore_seat")) continue;
                    entity.remove();
                    entitiesRemoved++;
                }
                if (releaseTickets) {
                    for (Chunk chunk : chunks) chunk.removePluginChunkTicket(plugin);
                    for (long[] c : chunkCoords) world.unloadChunkRequest((int) c[0], (int) c[1]);
                }
                boolean clean = changePositions.length == 0;
                phase = Phase.DONE;
                snapshots.clear();
                chunks.clear();
                future.complete(new Result(passes, totalChanged, entitiesRemoved, System.currentTimeMillis() - started, clean,
                    loadedAt == 0 ? 0 : loadedAt - started, diffNanos / 1_000_000, ticks));
                return true;
            }
            default -> {
                return true;
            }
        }
    }

    /** Off-thread: compare every position with the target. */
    private void diff() {
        long t0 = System.nanoTime();
        try {
            int sx = target.sizeX();
            int sy = target.sizeY();
            int sz = target.sizeZ();
            int[] positions = new int[1024];
            BlockData[] data = new BlockData[1024];
            int n = 0;
            for (int y = 0; y < sy; y++) {
                int wy = oy + y;
                for (int z = 0; z < sz; z++) {
                    int wz = oz + z;
                    for (int x = 0; x < sx; x++) {
                        int wx = ox + x;
                        ChunkSnapshot snap = snapshots.get(key(wx >> 4, wz >> 4));
                        int flat = (y * sz + z) * sx + x;
                        BlockData want = palette[target.getAt(flat)];
                        BlockData have = snap.getBlockData(wx & 15, wy, wz & 15);
                        if (!have.equals(want)) {
                            if (n == positions.length) {
                                positions = java.util.Arrays.copyOf(positions, n * 2);
                                data = java.util.Arrays.copyOf(data, n * 2);
                            }
                            positions[n] = flat;
                            data[n] = want;
                            n++;
                        }
                    }
                }
            }
            changePositions = java.util.Arrays.copyOf(positions, n);
            changeData = java.util.Arrays.copyOf(data, n);
            diffNanos += System.nanoTime() - t0;
            phase = Phase.APPLY;
        } catch (Throwable t) {
            failure = t;
        }
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }
}
