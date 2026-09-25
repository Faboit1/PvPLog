package top.cheesesmp.duelcore.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;

/**
 * The round start "rising platform": the fighter comes up out of a 2×2 hole at their spawn.
 *
 * <p>The 2×2 columns around the spawn corner are carved out a few blocks deep and the player is put at the bottom.
 * The removed blocks (their real block data, plus loose decoration like grass on top) are shown as block displays
 * sunk into the ground below the hole; their transformation is then interpolated back up by the client over the
 * whole rise, smooth at any frame rate. The player is carried up with them through their own velocity every tick,
 * exactly like {@link RespawnPull} (no teleport stutter, no riding), and put exactly on the spawn at the end. Then
 * the real blocks come back and the displays go away in the same tick.
 *
 * <p>The displays are not persistent and carry {@link #KEEP_TAG}, so an arena reset running at the same time leaves
 * them alone and a crash can never leave them in the world. Every rise ends through {@link #finish}, which always
 * restores the blocks and runs {@code done} exactly once: on arrival, quit, match end, or plugin disable.
 */
public final class SpawnRise implements Listener {

    /** Entities with this tag survive arena resets (see RegionJob). */
    public static final String KEEP_TAG = "duelcore_seat";
    private static final String TAG = "duelcore_rise";
    /** Ticks at the bottom before rising, so a player who just changed world sees the hole and the platform. */
    private static final int HOLD_TICKS = 10;
    /**
     * Longest wait for the arena reset (between rounds) and for the teleport. With the rise capped at 60 ticks the
     * whole animation stays under the match's 120-tick PREPARING limit, so the countdown never starts early.
     */
    private static final int WAIT_LIMIT_TICKS = 20;
    /** Decoration above the top block that rises with it (grass, flowers, snow layers). */
    private static final int DECORATION_LAYERS = 2;
    /**
     * Longest wait of a plain-teleport fallback for another rise's open hole under its spawn to close (a rise lasts
     * at most about 110 ticks), so the player never drops into it and gets sealed in.
     */
    private static final int FALLBACK_WAIT_TICKS = 120;

    private final DuelCorePlugin plugin;
    private final Map<UUID, Rise> active = new HashMap<>();

    private record Carved(Block block, BlockData data) {
    }

    private final class Rise {
        final Player player;
        final Location spawn;
        final List<Player> viewers;
        final BooleanSupplier ready;
        final BooleanSupplier valid;
        final Runnable done;
        final List<Carved> carved = new ArrayList<>();
        final List<BlockDisplay> displays = new ArrayList<>();
        @Nullable RiseGeometry geo;
        @Nullable Location target;
        double startY;
        /** Length of the rise, fixed at the start (a reload must not change it halfway). */
        int ticks;
        volatile boolean arrived;
        BukkitTask task;
        boolean finished;
        /** No hole here: the plain teleport waits until no other open hole lies under the spawn. */
        boolean fallingBack;
        int fallbackWaited;

        Rise(Player player, Location spawn, List<Player> viewers, BooleanSupplier ready, BooleanSupplier valid,
             Runnable done) {
            this.player = player;
            this.spawn = spawn;
            this.viewers = viewers;
            this.ready = ready;
            this.valid = valid;
            this.done = done;
        }
    }

    public SpawnRise(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** True while the player is waiting in or rising out of the hole (their movement is driven by the rise). */
    public boolean rising(UUID player) {
        return active.containsKey(player);
    }

    public int activeCount() {
        return active.size();
    }

    /**
     * Brings the player up to {@code spawn} on a rising platform. {@code ready} delays the start (e.g. until the
     * between-round arena reset is done, so it doesn't refill the hole); {@code valid} going false (match over, player
     * gone) ends it early with the blocks restored. {@code done} always runs exactly once, on the main thread; when
     * the rise completes the player is standing on the spawn (moved to the nearest block corner) by then.
     */
    public void rise(Player player, Location spawn, List<Player> viewers, BooleanSupplier ready, BooleanSupplier valid,
                     Runnable done) {
        abort(player.getUniqueId());
        Rise r = new Rise(player, spawn.clone(), viewers, ready, valid, done);
        active.put(player.getUniqueId(), r);
        r.task = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            int waited;
            int travelled;
            int held;
            int k = -1;

            @Override
            public void run() {
                if (!player.isOnline() || !r.valid.getAsBoolean()) {
                    finish(r, player.isOnline());
                    return;
                }
                if (r.fallingBack) {
                    fallback(r);
                    return;
                }
                if (r.geo == null) {
                    // waiting for the arena to be ready
                    if (r.ready.getAsBoolean()) {
                        if (!begin(r)) fallback(r);
                    } else if (++waited > WAIT_LIMIT_TICKS) {
                        fallback(r);
                    }
                    return;
                }
                if (k < 0) {
                    // at the bottom: wait for the teleport, then hold a moment
                    if (!r.arrived && ++travelled <= WAIT_LIMIT_TICKS) return;
                    if (++held < HOLD_TICKS) {
                        player.setFallDistance(0);
                        return;
                    }
                    start(r);
                    k = 0;
                }
                int ticks = r.ticks;
                Location target = r.target;
                if (k < ticks && target != null) {
                    double here = RiseGeometry.heightAt(r.startY, target.getY(), k, ticks);
                    double next = RiseGeometry.heightAt(r.startY, target.getY(), k + 1, ticks);
                    Location at = player.getLocation();
                    // straight up; a gentle pull keeps them centred if something nudged them sideways
                    double dx = Math.clamp((target.getX() - at.getX()) * 0.3, -0.1, 0.1);
                    double dz = Math.clamp((target.getZ() - at.getZ()) * 0.3, -0.1, 0.1);
                    player.setVelocity(new Vector(dx, next - here, dz));
                    player.setFallDistance(0);
                    k++;
                    return;
                }
                // one extra tick so the clients' interpolation has surely ended before the real blocks come back
                if (k++ == ticks) return;
                finish(r, true, true);
            }
        }, 1L, 1L);
        // the arena is usually ready right away (round 1): start in this tick instead of the next
        if (ready.getAsBoolean() && valid.getAsBoolean() && !begin(r)) fallback(r);
    }

    /**
     * No hole possible (or the arena never got ready): a plain teleport onto the spawn, once no other rise's open hole
     * is under it (the task calls this again every tick until then).
     */
    private void fallback(Rise r) {
        if (r.finished) return;
        if (overOpenHole(r) && r.fallbackWaited++ < FALLBACK_WAIT_TICKS) {
            r.fallingBack = true;
            return;
        }
        r.finished = true;
        active.remove(r.player.getUniqueId(), r);
        r.task.cancel();
        r.player.teleportAsync(r.spawn).whenComplete((ok, err) -> r.done.run());
    }

    /** True when the player's footprint at the spawn lies over another rise's open hole. */
    private boolean overOpenHole(Rise r) {
        World world = r.spawn.getWorld();
        int minX = (int) Math.floor(r.spawn.getX() - 0.3);
        int maxX = (int) Math.floor(r.spawn.getX() + 0.3);
        int minZ = (int) Math.floor(r.spawn.getZ() - 0.3);
        int maxZ = (int) Math.floor(r.spawn.getZ() + 0.3);
        for (Rise other : active.values()) {
            RiseGeometry g = other.geo;
            if (other == r || g == null || other.spawn.getWorld() != world) continue;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) if (g.contains(x, z)) return true;
            }
        }
        return false;
    }

    /**
     * A platform for this spawn: the nearest block corner whose 2×2 overlaps no open hole, has room for the player
     * above it (they end up standing on all four columns) and can be carved. Null when none fits.
     */
    private @Nullable RiseGeometry place(Rise r, World world) {
        for (RiseGeometry geo : RiseGeometry.candidates(r.spawn.getX(), r.spawn.getY(), r.spawn.getZ(),
                plugin.settings().animSpawnRiseDepth)) {
            int[][] columns = geo.columns();
            if (overlapsOpenHole(r, world, columns) || !headroom(world, columns, geo.topY())) continue;
            int depth = RiseGeometry.usableDepth(geo.topY(), geo.depth(),
                y -> {
                    for (int[] c : columns) if (!carvable(world.getBlockAt(c[0], y, c[1]))) return false;
                    return true;
                },
                y -> {
                    for (int[] c : columns) if (!world.getBlockAt(c[0], y, c[1]).isSolid()) return false;
                    return true;
                });
            if (depth > 0) return geo.withDepth(depth);
        }
        return null;
    }

    /** Team mates spawn close together: never dig into a hole that is already open. */
    private boolean overlapsOpenHole(Rise r, World world, int[][] columns) {
        for (Rise other : active.values()) {
            RiseGeometry g = other.geo;
            if (other == r || g == null || other.spawn.getWorld() != world) continue;
            for (int[] c : columns) if (g.contains(c[0], c[1])) return true;
        }
        return false;
    }

    /** Feet and head height above the platform are free in all four columns (no wall, step or glass to rise into). */
    private static boolean headroom(World world, int[][] columns, int topY) {
        for (int[] c : columns) {
            for (int dy = 1; dy <= 2; dy++) {
                Block b = world.getBlockAt(c[0], topY + dy, c[1]);
                if (!b.isPassable() || b.isLiquid()) return false;
            }
        }
        return true;
    }

    /** Carves the hole, sinks the platform and puts the player at the bottom. False when no hole fits here. */
    private boolean begin(Rise r) {
        if (r.geo != null) return true;
        Player player = r.player;
        World world = r.spawn.getWorld();
        RiseGeometry geo = place(r, world);
        if (geo == null) return false;
        int depth = geo.depth();
        int[][] columns = geo.columns();
        r.geo = geo;

        // light of the surface, so the platform isn't drawn dark while it is still below ground
        int sky = 0;
        int blockLight = 0;
        for (int[] c : columns) {
            Block above = world.getBlockAt(c[0], geo.topY() + 1, c[1]);
            sky = Math.max(sky, above.getLightFromSky());
            blockLight = Math.max(blockLight, above.getLightFromBlocks());
        }
        Display.Brightness brightness = new Display.Brightness(blockLight, sky);

        for (int[] c : columns) {
            for (int y = geo.bottomY(); y <= geo.topY(); y++) carve(r, world.getBlockAt(c[0], y, c[1]));
            for (int i = 1; i <= DECORATION_LAYERS; i++) {
                Block deco = world.getBlockAt(c[0], geo.topY() + i, c[1]);
                if (deco.isEmpty() || deco.isSolid() || !carvable(deco)) break;
                carve(r, deco);
            }
        }
        for (Carved c : r.carved) {
            c.block().setType(Material.AIR, false);
        }
        for (Carved c : r.carved) {
            if (c.data().getMaterial().isAir()) continue;
            Block b = c.block();
            Transformation sunk = transformation(geo, b.getX(), b.getZ(), geo.depth());
            r.displays.add(world.spawn(b.getLocation(), BlockDisplay.class, d -> {
                d.setBlock(c.data());
                d.setPersistent(false);
                d.addScoreboardTag(KEEP_TAG);
                d.addScoreboardTag(TAG);
                d.setBrightness(brightness);
                d.setInterpolationDuration(0);
                d.setTransformation(sunk);
            }));
        }

        Location target = r.spawn.clone();
        target.setX(geo.cornerX());
        target.setZ(geo.cornerZ());
        r.target = target;
        r.startY = geo.startY(target.getY());
        Location bottom = target.clone();
        bottom.setY(r.startY);
        if (player.getGameMode() == GameMode.SPECTATOR) player.setGameMode(GameMode.SURVIVAL);
        player.leaveVehicle();
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setFallDistance(0);
        player.setVelocity(new Vector());
        plugin.matches().freeze(player);
        player.teleportAsync(bottom).whenComplete((ok, err) -> r.arrived = true);
        if (plugin.settings().verbose) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT, "[rise] %s from %.1f to %.1f (%d deep, %d blocks)",
                player.getName(), r.startY, target.getY(), depth, r.carved.size()));
        }
        return true;
    }

    private void carve(Rise r, Block block) {
        r.carved.add(new Carved(block, block.getBlockData()));
    }

    /** Blocks that may be lifted: no fluids, no block entities (their contents would be lost), nothing unbreakable. */
    private static boolean carvable(Block block) {
        Material type = block.getType();
        if (type == Material.BEDROCK || type == Material.BARRIER || type.getHardness() < 0 || block.isLiquid()) return false;
        return type.isAir() || !(block.getState(false) instanceof TileState);
    }

    private static Transformation transformation(RiseGeometry geo, int x, int z, double lift) {
        float[] t = geo.translation(x, z, lift);
        float[] s = geo.scale();
        return new Transformation(new Vector3f(t[0], t[1], t[2]), new Quaternionf(), new Vector3f(s[0], s[1], s[2]),
            new Quaternionf());
    }

    /** Sends the platform up: the clients interpolate every display from sunk to in place over the rise. */
    private void start(Rise r) {
        RiseGeometry geo = r.geo;
        if (geo == null) return;
        int ticks = plugin.settings().animSpawnRiseTicks;
        r.ticks = ticks;
        for (BlockDisplay d : r.displays) {
            if (!d.isValid()) continue;
            Location l = d.getLocation();
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(ticks);
            d.setTransformation(transformation(geo, l.getBlockX(), l.getBlockZ(), 0));
        }
        Location target = r.target;
        if (target == null) return;
        BlockData top = topData(r);
        Location rim = target.clone().add(0, 0.1, 0);
        for (Player v : r.viewers) {
            if (!v.isOnline() || v.getWorld() != target.getWorld()) continue;
            v.playSound(rim, Sound.BLOCK_PISTON_EXTEND, 0.35f, 0.55f);
            v.playSound(rim, Sound.BLOCK_GRAVEL_BREAK, 0.4f, 0.7f);
            if (top != null) v.spawnParticle(Particle.BLOCK, rim, 24, 0.9, 0.05, 0.9, 0, top);
        }
    }

    private static @Nullable BlockData topData(Rise r) {
        RiseGeometry geo = r.geo;
        if (geo == null) return null;
        for (Carved c : r.carved) {
            if (c.block().getY() == geo.topY() && !c.data().getMaterial().isAir()) return c.data();
        }
        return null;
    }

    private void finish(Rise r, boolean land) {
        finish(r, land, false);
    }

    /**
     * Restores the blocks, removes the displays and runs {@code done}; with {@code land} the player ends on the spawn
     * when still near it. A {@code completed} rise always ends exactly on the spawn, however far the player got
     * (movement isn't checked by the server while rising, so a modified client could otherwise start anywhere).
     */
    private void finish(Rise r, boolean land, boolean completed) {
        if (r.finished) return;
        r.finished = true;
        active.remove(r.player.getUniqueId(), r);
        if (r.task != null) r.task.cancel();
        BlockData top = topData(r); // before restore() forgets the carved blocks
        restore(r);
        Location target = r.target;
        Player player = r.player;
        boolean sameWorld = target != null && player.getWorld() == target.getWorld();
        boolean near = sameWorld && player.getLocation().distanceSquared(target) < 16 * 16;
        if (land && target != null && player.isOnline() && (near || completed)) {
            player.setVelocity(new Vector());
            player.setFallDistance(0);
            for (Player v : near ? r.viewers : List.<Player>of()) {
                if (!v.isOnline() || v.getWorld() != target.getWorld()) continue;
                v.playSound(target, Sound.BLOCK_PISTON_CONTRACT, 0.3f, 0.7f);
                if (top != null) v.spawnParticle(Particle.BLOCK, target.clone().add(0, 0.1, 0), 12, 0.8, 0.05, 0.8, 0, top);
            }
            if (!sameWorld || player.getLocation().distanceSquared(target) > 0.01) {
                Location exact = target.clone();
                exact.setYaw(player.getLocation().getYaw());
                exact.setPitch(player.getLocation().getPitch());
                player.teleportAsync(exact).whenComplete((ok, err) -> r.done.run());
                return;
            }
        }
        r.done.run();
    }

    private static void restore(Rise r) {
        for (BlockDisplay d : r.displays) d.remove();
        r.displays.clear();
        // bottom up, so decoration goes back onto its support
        for (Carved c : r.carved) c.block().setBlockData(c.data(), false);
        r.carved.clear();
    }

    /** Ends a rise early: blocks back, player lifted onto the spawn if still there. */
    public void abort(UUID player) {
        Rise r = active.get(player);
        if (r != null) finish(r, true);
    }

    /** Plugin disable: every hole is filled again, players are not moved (they are sent away anyway). */
    public void cancelAll() {
        for (Rise r : new ArrayList<>(active.values())) finish(r, false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Rise r = active.get(event.getPlayer().getUniqueId());
        if (r != null) finish(r, false);
    }
}
