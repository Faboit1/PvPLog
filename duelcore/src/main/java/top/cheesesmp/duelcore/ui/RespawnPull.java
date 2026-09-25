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
 * <p>The arc is a true projectile path under Minecraft's player gravity (0.08 blocks/tick²): constant horizontal speed,
 * a parabola vertically, peaking well above both ends. The flight time follows from the height of the arc the same
 * way it would for a thrown player. It is driven through the player's own velocity every tick, so the client moves
 * them with its normal physics and interpolation (no teleport stutter). Their own input is overwritten each tick, so
 * they can look around but not steer. On landing they are put exactly on the spawn.
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
 *   <li>no velocity is sent once a throw is over, and before the final teleport a zero velocity goes out one tick ahead
 *       (entity velocity packets leave with the end-of-tick entity updates, a teleport immediately), so no velocity
 *       packet can reach the client after the snap and launch it again.</li>
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

    private enum Stage { FLYING, SNAPPING, DONE }

    private record Arc(double bulge, int ticks, double top, double raise) {
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
            verbose("[throw] %s teleported instead: no arc clears the terrain", player.getName());
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
        for (Player v : viewers) {
            if (!v.isOnline() || v.getWorld() != start.getWorld()) continue;
            v.playSound(start, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 0.7f, 0.9f);
            v.spawnParticle(Particle.GUST, start.clone().add(0, 0.2, 0), 1);
        }
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
                    Vector here = point(a, b, arc.bulge(), (double) k / arc.ticks());
                    Vector next = point(a, b, arc.bulge(), (double) (k + 1) / arc.ticks());
                    player.setVelocity(next.subtract(here));
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
                // done steering (no more velocity from here on): the client is a round trip behind and finishes the
                // arc on its own momentum; wait for it to touch down, then stand it exactly on the spawn. A position
                // the server never saw move is not a landing, even if it is on the ground.
                boolean grounded = moved && now.subtract(0, 0.08, 0).getBlock().isSolid();
                if (!grounded && settle++ < settleMax) return;
                landingEffects(viewers, target);
                snap(t, false);
            }
        }, 1L, 1L);
    }

    /** Point at progress {@code u} (0..1): straight chord plus a parabolic bulge of height {@code bulge}. */
    private static Vector point(Vector a, Vector b, double bulge, double u) {
        Vector p = a.clone().add(b.clone().subtract(a).multiply(u));
        return p.setY(ThrowMath.y(a.getY(), b.getY(), bulge, u));
    }

    /**
     * The lowest arc (at least {@code minHeight} above the higher end, a bit more for long throws) whose path the
     * player's body fits through, raising it over trees and hills; null when none does (the barrier ceiling).
     */
    private static @Nullable Arc plan(Location start, Location target, double minHeight) {
        World world = start.getWorld();
        double horizontal = Math.hypot(target.getX() - start.getX(), target.getZ() - start.getZ());
        double base = Math.max(start.getY(), target.getY()) + Math.clamp(minHeight + horizontal * 0.2, minHeight, minHeight + 16);
        for (double raise : RAISE) {
            double top = base + raise;
            if (top + 2 >= world.getMaxHeight()) break;
            double bulge = top - (start.getY() + target.getY()) / 2;
            int ticks = ThrowMath.ticks(bulge);
            if (clear(world, start, target, bulge, ticks)) return new Arc(bulge, ticks, top, raise);
        }
        return null;
    }

    /** True when the player's body fits along the arc, sampled every half tick of flight. */
    private static boolean clear(World world, Location a, Location b, double bulge, int ticks) {
        int samples = ticks * 2;
        for (int i = 1; i < samples; i++) {
            double u = (double) i / samples;
            double x = a.getX() + (b.getX() - a.getX()) * u;
            double z = a.getZ() + (b.getZ() - a.getZ()) * u;
            double y = ThrowMath.y(a.getY(), b.getY(), bulge, u);
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
