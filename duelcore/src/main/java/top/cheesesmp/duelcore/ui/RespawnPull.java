package top.cheesesmp.duelcore.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;

/**
 * The between-round respawn: the player is thrown back to their spawn along a real ballistic arc.
 *
 * <p>The player gets one launch velocity, computed from Minecraft's own player physics (gravity 0.08, vertical drag
 * 0.98, horizontal drag 0.91, ground friction on the first tick) so that the client's normal physics carries them
 * along a natural arc onto the spawn ({@link ThrowMath#launch}). Nothing steers them after that; on landing they are
 * put exactly on the spawn, which only corrects the little their own air control moved them.
 *
 * <p>Robustness, because the client runs a round trip behind the server and an anticheat checks every velocity:
 * <ul>
 *   <li>arcs that would clip trees, hills or the barrier ceiling are raised, and become a teleport when no height
 *       clears them;</li>
 *   <li>players whose ping is above {@code animations.respawn-throw-max-ping} are teleported instead of thrown;</li>
 *   <li>the wait for touch-down after the last velocity grows with the player's ping, and landing only counts once the
 *       server has seen the player move (a position that never changed is still on the ground at the start);</li>
 *   <li>a throw the server never sees the player follow (movement lost or ignored, e.g. a teleport the client has not
 *       confirmed yet) is ended after {@code respawn-throw-stall-ticks} plus the ping with a teleport;</li>
 *   <li>a launch faster than {@link #MAX_SPEED} blocks per tick (a very long throw) becomes a teleport;</li>
 *   <li>before the final teleport a zero velocity goes out one tick ahead (entity velocity packets leave with the
 *       end-of-tick entity updates, a teleport immediately), so no velocity packet can reach the client after the
 *       snap and launch it again.</li>
 * </ul>
 */
public final class RespawnPull implements Listener {

    /** Extra height tried, in order, when the arc would clip terrain. */
    private static final double[] RAISE = {0, 4, 8, 14, 22};
    /** A server-side position closer than this (squared, blocks) to the start never moved. */
    private static final double STILL_SQ = 1.0;
    /** Heights above the feet checked for a free path (the player is 1.8 tall). */
    private static final double[] BODY = {0.1, 0.9, 1.7};
    /** Half the player's width, for the corners of the path check. */
    private static final double HALF_WIDTH = 0.3;
    /** Fastest launch (blocks per tick, horizontally) before a throw becomes a teleport. */
    static final double MAX_SPEED = 6.0;

    private enum Stage { FLYING, SNAPPING, DONE }

    /** A planned flight: the launch velocity, how long it takes and where the player is after each tick. */
    private record Arc(Vector launch, int ticks, double top, double raise, double[][] path) {
    }

    private final DuelCorePlugin plugin;
    private final Map<UUID, Throw> active = new HashMap<>();

    private static final class Throw {
        final Player player;
        final Location target;
        final Runnable done;
        @Nullable BukkitTask task;
        Stage stage = Stage.FLYING;

        Throw(Player player, Location target, Runnable done) {
            this.player = player;
            this.target = target;
            this.done = done;
        }
    }

    public RespawnPull(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean pulling(UUID player) {
        return active.containsKey(player);
    }

    public int activeCount() {
        return active.size();
    }

    /**
     * Throws the player to {@code target}. {@code minHeight} is how far the top of the arc rises above the higher end
     * point at least (longer throws go higher). Falls back to a teleport for another world, an absurd distance, a
     * high ping or an arc that can't clear the terrain. {@code done} always runs exactly once, on the main thread,
     * after the player is standing on the target (or the throw was aborted).
     */
    public void pull(Player player, Location target, double minHeight, List<Player> viewers, Runnable done) {
        cancel(player.getUniqueId());
        Throw t = new Throw(player, target.clone(), done);
        active.put(player.getUniqueId(), t);
        if (player.getWorld() != target.getWorld() || player.getLocation().distanceSquared(target) > 300 * 300) {
            snap(t, false);
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) player.setGameMode(GameMode.SURVIVAL);
        player.leaveVehicle();
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setFallDistance(0);
        int ping = Math.max(0, player.getPing());
        int maxPing = plugin.settings().animRespawnThrowMaxPing;
        if (maxPing > 0 && ping > maxPing) {
            verbose("[throw] %s teleported instead: ping %d ms is above %d", player.getName(), ping, maxPing);
            snap(t, false);
            return;
        }
        Location start = freeSpot(player.getLocation()); // the death cam may end inside terrain
        Arc arc = plan(start, target, minHeight);
        if (arc == null) {
            verbose("[throw] %s teleported instead: no arc clears the terrain or it would be too fast", player.getName());
            snap(t, false);
            return;
        }
        if (!start.equals(player.getLocation())) player.teleport(start);

        Vector a = start.toVector();
        Vector b = target.toVector();
        int settleMax = ThrowMath.settleTicks(ping);
        int stallAfter = ThrowMath.stallTicks(plugin.settings().animRespawnThrowStallTicks, ping);
        verbose("[throw] %s %.0f blocks, top %.1f above start%s, %d ticks, ping %d ms (settle %d, stall %d)",
            player.getName(), Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ()), arc.top() - a.getY(),
            arc.raise() > 0 ? String.format(java.util.Locale.ROOT, " (raised %.0f over terrain)", arc.raise()) : "",
            arc.ticks(), ping, settleMax, stallAfter);
        // one launch; from here on the client's own physics flies the arc
        player.setVelocity(arc.launch());
        player.setFallDistance(0);
        t.task = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            int k;
            int settle;

            @Override
            public void run() {
                if (t.stage != Stage.FLYING) return;
                if (!player.isOnline()) {
                    complete(t);
                    return;
                }
                Location now = player.getLocation();
                if (now.getWorld() != start.getWorld()) { // moved away by something else: leave them there
                    complete(t);
                    return;
                }
                boolean moved = now.distanceSquared(start) >= STILL_SQ;
                if (k < arc.ticks()) {
                    if (!moved && k >= stallAfter) {
                        // the server hasn't seen them follow the arc at all: stop steering and put them on the spawn
                        verbose("[throw] %s stalled: no movement after %d ticks (ping %d ms), teleporting",
                            player.getName(), k, player.getPing());
                        landingEffects(viewers, target);
                        snap(t, false);
                        return;
                    }
                    player.setFallDistance(0);
                    if (k % 2 == 0) {
                        Location trail = now.add(0, 0.2, 0);
                        for (Player v : viewers) {
                            if (v.isOnline() && v.getWorld() == trail.getWorld()) {
                                v.spawnParticle(Particle.CLOUD, trail, 2, 0.1, 0.1, 0.1, 0.01);
                            }
                        }
                    }
                    k++;
                    return;
                }
                // the flight time is up: the client is a round trip behind and finishes the arc on its own momentum;
                // wait for it to touch down, then stand it exactly on the spawn. A position the server never saw move
                // is not a landing, even if it is on the ground.
                boolean grounded = moved && now.subtract(0, 0.08, 0).getBlock().isSolid();
                if (!grounded && settle++ < settleMax) return;
                landingEffects(viewers, target);
                snap(t, false);
            }
        }, 1L, 1L);
    }

    /**
     * The lowest arc (at least {@code minHeight} above the higher end, a bit more for long throws) whose path the
     * player's body fits through, raising it over trees and hills; null when none does (the barrier ceiling) or the
     * launch would be faster than {@link #MAX_SPEED}.
     */
    private static @Nullable Arc plan(Location start, Location target, double minHeight) {
        World world = start.getWorld();
        double dx = target.getX() - start.getX();
        double dz = target.getZ() - start.getZ();
        double horizontal = Math.hypot(dx, dz);
        double base = Math.max(start.getY(), target.getY()) + Math.clamp(minHeight + horizontal * 0.2, minHeight, minHeight + 16);
        boolean onGround = start.clone().subtract(0, 0.08, 0).getBlock().isSolid();
        for (double raise : RAISE) {
            double top = base + raise;
            if (top + 2 >= world.getMaxHeight()) break;
            double bulge = top - (start.getY() + target.getY()) / 2;
            int ticks = ThrowMath.ticks(bulge);
            double[] v = ThrowMath.launch(dx, target.getY() - start.getY(), dz, ticks, onGround);
            if (Math.hypot(v[0], v[2]) > MAX_SPEED) return null;
            double[][] path = ThrowMath.path(v, ticks, onGround);
            if (clear(world, start, target, path)) return new Arc(new Vector(v[0], v[1], v[2]), ticks, top, raise, path);
        }
        return null;
    }

    /** True when the player's body fits along the flight path, sampled every half tick. */
    private static boolean clear(World world, Location a, Location b, double[][] path) {
        double px = 0, py = 0, pz = 0;
        for (int i = 0; i < path.length * 2 - 1; i++) {
            double[] at = path[i / 2];
            double x, y, z;
            if (i % 2 == 0) { // halfway into tick i/2
                x = a.getX() + (px + at[0]) / 2;
                y = a.getY() + (py + at[1]) / 2;
                z = a.getZ() + (pz + at[2]) / 2;
            } else { // at the end of tick i/2
                x = a.getX() + at[0];
                y = a.getY() + at[1];
                z = a.getZ() + at[2];
                px = at[0];
                py = at[1];
                pz = at[2];
            }
            // right at the ends the player stands next to whatever is there; only the middle of the body must be free
            boolean nearEnd = distanceSq(x, y, z, a) < 1.5 * 1.5 || distanceSq(x, y, z, b) < 1.5 * 1.5;
            if (!fits(world, x, y, z, nearEnd ? 0 : HALF_WIDTH)) return false;
        }
        return true;
    }

    private static double distanceSq(double x, double y, double z, Location l) {
        double dx = x - l.getX();
        double dy = y - l.getY();
        double dz = z - l.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean fits(World world, double x, double y, double z, double r) {
        for (double dx : r == 0 ? new double[] {0} : new double[] {-r, r}) {
            for (double dz : r == 0 ? new double[] {0} : new double[] {-r, r}) {
                int bx = (int) Math.floor(x + dx);
                int bz = (int) Math.floor(z + dz);
                if (!world.isChunkLoaded(bx >> 4, bz >> 4)) continue; // never load chunks for a check
                for (double h : BODY) {
                    int by = (int) Math.floor(y + h);
                    if (by < world.getMinHeight() || by >= world.getMaxHeight()) continue;
                    if (!world.getBlockAt(bx, by, bz).isPassable()) return false;
                }
            }
        }
        return true;
    }

    /** The nearest spot at or above {@code loc} where a player fits (feet and head not in solid blocks). */
    private static Location freeSpot(Location loc) {
        Location l = loc.clone();
        for (int i = 0; i < 24; i++) {
            Block feet = l.getBlock();
            if (!feet.isSolid() && !feet.getRelative(0, 1, 0).isSolid()) return l;
            l.setY(Math.floor(l.getY()) + 1);
        }
        return loc;
    }

    private static void landingEffects(List<Player> viewers, Location target) {
        for (Player v : viewers) {
            if (!v.isOnline() || v.getWorld() != target.getWorld()) continue;
            v.playSound(target, Sound.ENTITY_PLAYER_BIG_FALL, 0.8f, 0.9f);
            v.spawnParticle(Particle.CLOUD, target.clone().add(0, 0.1, 0), 8, 0.3, 0.05, 0.3, 0.02);
        }
    }

    /**
     * Ends the flight on the target: steering stops, a zero velocity goes out, and the teleport follows on the next
     * tick so it reaches the client after every velocity packet ({@code now}: teleport right away).
     */
    private void snap(Throw t, boolean now) {
        if (t.stage != Stage.FLYING) return;
        t.stage = Stage.SNAPPING;
        if (t.task != null) t.task.cancel();
        t.task = null;
        Player p = t.player;
        if (!p.isOnline()) {
            complete(t);
            return;
        }
        p.setVelocity(new Vector());
        p.setFallDistance(0);
        boolean sameWorld = p.getWorld() == t.target.getWorld();
        if (plugin.settings().verbose && sameWorld) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT, "[throw] %s landed %.2f blocks from the spawn",
                p.getName(), p.getLocation().distance(t.target)));
        }
        if (sameWorld && p.getLocation().distanceSquared(t.target) <= 0.25) {
            complete(t);
            return;
        }
        if (now) teleport(t);
        else t.task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> teleport(t), 1L);
    }

    private void teleport(Throw t) {
        if (t.stage != Stage.SNAPPING) return;
        t.stage = Stage.DONE;
        t.task = null;
        active.remove(t.player.getUniqueId(), t);
        if (!t.player.isOnline()) {
            t.done.run();
            return;
        }
        t.player.setFallDistance(0);
        t.player.teleportAsync(t.target).whenComplete((ok, err) -> t.done.run());
    }

    /** Ends a throw where the player is (no velocity, no teleport) and runs its callback. */
    private void complete(Throw t) {
        if (t.stage == Stage.DONE) return;
        t.stage = Stage.DONE;
        if (t.task != null) t.task.cancel();
        t.task = null;
        active.remove(t.player.getUniqueId(), t);
        t.done.run();
    }

    private void verbose(String format, Object... args) {
        if (plugin.settings().verbose) plugin.getLogger().info(String.format(java.util.Locale.ROOT, format, args));
    }

    /** Ends a throw early; the player still ends up on the target. */
    public void cancel(UUID player) {
        Throw t = active.get(player);
        if (t == null) return;
        if (t.stage == Stage.FLYING) snap(t, true);
        else if (t.stage == Stage.SNAPPING) {
            if (t.task != null) t.task.cancel();
            teleport(t);
        }
    }

    /** Ends a throw without moving the player (the match ended mid-flight). */
    public void abort(UUID player) {
        Throw t = active.get(player);
        if (t != null) complete(t);
    }

    public void cancelAll() {
        for (Throw t : new ArrayList<>(active.values())) complete(t);
        active.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        abort(event.getPlayer().getUniqueId());
    }
}
