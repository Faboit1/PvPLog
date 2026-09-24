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
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import top.cheesesmp.duelcore.DuelCorePlugin;

/**
 * The between-round respawn: the player is thrown back to their spawn along a real ballistic arc.
 *
 * <p>The arc is a true projectile path under Minecraft's player gravity (0.08 blocks/tick²): constant horizontal speed,
 * a parabola vertically, peaking well above both ends. The flight time follows from the height of the arc the same
 * way it would for a thrown player. It is driven through the player's own velocity every tick, so the client moves
 * them with its normal physics and interpolation (no teleport stutter). Their own input is overwritten each tick, so
 * they can look around but not steer. On landing they are put exactly on the spawn.
 */
public final class RespawnPull implements Listener {

    /** Minecraft's gravity for players, blocks per tick². */
    private static final double GRAVITY = 0.08;

    private final DuelCorePlugin plugin;
    private final Map<UUID, Throw> active = new HashMap<>();

    private final class Throw {
        final Player player;
        final Location target;
        final Runnable done;
        BukkitTask task;
        boolean finished;

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
     * point at least (longer throws go higher). Falls back to a teleport for another world or an absurd distance.
     * {@code done} always runs exactly once, on the main thread, after the player is standing on the target.
     */
    public void pull(Player player, Location target, double minHeight, List<Player> viewers, Runnable done) {
        cancel(player.getUniqueId());
        if (player.getWorld() != target.getWorld() || player.getLocation().distanceSquared(target) > 300 * 300) {
            player.teleportAsync(target).thenRun(done);
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) player.setGameMode(GameMode.SURVIVAL);
        player.leaveVehicle();
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setFallDistance(0);
        Location start = freeSpot(player.getLocation());
        if (!start.equals(player.getLocation())) player.teleport(start); // the death cam may end inside terrain

        Vector a = start.toVector();
        Vector b = target.toVector();
        double horizontal = Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
        double top = Math.max(a.getY(), b.getY()) + Math.clamp(minHeight + horizontal * 0.2, minHeight, minHeight + 16);
        // the path is the straight chord plus a parabolic bulge A (height of the top above the chord's middle)
        double bulge = top - (a.getY() + b.getY()) / 2;
        // p(u) = chord(u) + 4A·u(1-u) accelerates downwards by 8A/T² per tick²: pick T so that equals GRAVITY
        int ticks = (int) Math.clamp(Math.round(Math.sqrt(8 * bulge / GRAVITY)), 14, 80);
        Throw t = new Throw(player, target.clone(), done);
        active.put(player.getUniqueId(), t);
        if (plugin.settings().verbose) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                "[throw] %s %.0f blocks, top %.1f above start, %d ticks", player.getName(), horizontal, top - a.getY(), ticks));
        }
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
                if (!player.isOnline()) {
                    finish(t, false);
                    return;
                }
                if (k < ticks) {
                    Vector here = point(a, b, bulge, (double) k / ticks);
                    Vector next = point(a, b, bulge, (double) (k + 1) / ticks);
                    player.setVelocity(next.subtract(here));
                    player.setFallDistance(0);
                    if (k % 2 == 0) {
                        Location trail = player.getLocation().add(0, 0.2, 0);
                        for (Player v : viewers) {
                            if (v.isOnline() && v.getWorld() == trail.getWorld()) {
                                v.spawnParticle(Particle.CLOUD, trail, 2, 0.1, 0.1, 0.1, 0.01);
                            }
                        }
                    }
                    k++;
                    return;
                }
                // arrived (or blocked on the way): wait for the ground briefly, then stand exactly on the spawn
                if (k == ticks) {
                    player.setVelocity(new Vector(0, -0.1, 0));
                    k++;
                }
                boolean grounded = player.getLocation().subtract(0, 0.08, 0).getBlock().isSolid();
                if (!grounded && settle++ < 10) return;
                for (Player v : viewers) {
                    if (!v.isOnline() || v.getWorld() != target.getWorld()) continue;
                    v.playSound(target, Sound.ENTITY_PLAYER_BIG_FALL, 0.8f, 0.9f);
                    v.spawnParticle(Particle.CLOUD, target.clone().add(0, 0.1, 0), 8, 0.3, 0.05, 0.3, 0.02);
                }
                finish(t, true);
            }
        }, 1L, 1L);
    }

    /** Point at progress {@code u} (0..1): straight chord plus a parabolic bulge of height {@code bulge}. */
    private static Vector point(Vector a, Vector b, double bulge, double u) {
        Vector p = a.clone().add(b.clone().subtract(a).multiply(u));
        return p.setY(p.getY() + 4 * bulge * u * (1 - u));
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

    private void finish(Throw t, boolean land) {
        if (t.finished) return;
        t.finished = true;
        active.remove(t.player.getUniqueId(), t);
        if (t.task != null) t.task.cancel();
        if (land && t.player.isOnline()) {
            t.player.setVelocity(new Vector());
            t.player.setFallDistance(0);
            if (plugin.settings().verbose) {
                plugin.getLogger().info(String.format(java.util.Locale.ROOT, "[throw] %s landed %.2f blocks from the spawn",
                    t.player.getName(), t.player.getLocation().distance(t.target)));
            }
            if (t.player.getLocation().distanceSquared(t.target) > 0.25) {
                t.player.teleportAsync(t.target).whenComplete((ok, err) -> t.done.run());
                return;
            }
        }
        t.done.run();
    }

    /** Ends a throw early; the player still ends up on the target. */
    public void cancel(UUID player) {
        Throw t = active.get(player);
        if (t != null) finish(t, true);
    }

    /** Ends a throw without moving the player (the match ended mid-flight). */
    public void abort(UUID player) {
        Throw t = active.get(player);
        if (t != null) finish(t, false);
    }

    public void cancelAll() {
        for (Throw t : new ArrayList<>(active.values())) finish(t, false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        abort(event.getPlayer().getUniqueId());
    }
}
