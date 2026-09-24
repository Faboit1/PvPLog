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
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import top.cheesesmp.duelcore.DuelCorePlugin;

/**
 * The between-round "yoink": the player is seated on an invisible display entity that glides along an arc back to
 * their spawn. Display entities interpolate teleports on the client ({@code teleport_duration}), so the camera moves
 * smoothly even though the server only moves the seat every other tick. The player can look around but not move or
 * dismount. Seats are never saved (non-persistent) and the arena reset skips them (scoreboard tag {@link #TAG}).
 */
public final class RespawnPull implements Listener {

    public static final String TAG = "duelcore_seat";
    private static final int STEP = 2;

    private final DuelCorePlugin plugin;
    private final Map<UUID, Pull> active = new HashMap<>();

    private final class Pull {
        final Player player;
        final ItemDisplay seat;
        final Location target;
        final Runnable done;
        BukkitTask task;
        boolean finishing;

        Pull(Player player, ItemDisplay seat, Location target, Runnable done) {
            this.player = player;
            this.seat = seat;
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
     * Carries the player to {@code target} over about {@code ticks} ticks, then teleports them exactly onto it and
     * runs {@code done}. Falls back to a plain teleport when the target is in another world or far away.
     * {@code done} always runs exactly once (also when the pull is cut short), on the main thread.
     */
    public void pull(Player player, Location target, int ticks, List<Player> viewers, Runnable done) {
        cancel(player.getUniqueId());
        Location start = player.getLocation();
        if (ticks < STEP * 3 || start.getWorld() != target.getWorld() || start.distanceSquared(target) > 160 * 160) {
            player.teleportAsync(target).thenRun(done);
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) player.setGameMode(GameMode.SURVIVAL); // spectators can't ride
        player.leaveVehicle();
        player.setFallDistance(0);
        ItemDisplay seat = start.getWorld().spawn(start, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setTeleportDuration(STEP);
            d.setInvulnerable(true);
        });
        if (!seat.addPassenger(player)) {
            seat.remove();
            player.teleportAsync(target).thenRun(done);
            return;
        }
        Pull pull = new Pull(player, seat, target.clone(), done);
        active.put(player.getUniqueId(), pull);
        Vector a = start.toVector();
        Vector b = target.toVector();
        double dist = a.distance(b);
        Vector control = a.clone().add(b).multiply(0.5).add(new Vector(0, Math.min(14, 2.5 + dist * 0.35), 0));
        int total = Math.max(STEP * 3, ticks);
        for (Player v : viewers) v.playSound(start, Sound.ENTITY_BREEZE_WIND_BURST, 0.6f, 1.3f);
        pull.task = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            int t;

            @Override
            public void run() {
                t += STEP;
                if (!player.isOnline() || !seat.isValid()) {
                    finish(pull);
                    return;
                }
                double p = Math.min(1, (double) t / total);
                double e = p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2; // ease in-out cubic
                Vector pos = bezier(a, control, b, e);
                Location loc = pos.toLocation(seat.getWorld(), seat.getYaw(), seat.getPitch());
                seat.teleport(loc); // passengers ride along since 1.21.10
                Location trail = loc.clone().add(0, 0.9, 0);
                for (Player v : viewers) {
                    if (v.isOnline() && v.getWorld() == trail.getWorld()) {
                        v.spawnParticle(Particle.END_ROD, trail, 2, 0.12, 0.12, 0.12, 0.005);
                    }
                }
                if (p >= 1) {
                    for (Player v : viewers) {
                        if (v.isOnline()) v.playSound(target, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
                    }
                    finish(pull);
                }
            }
        }, STEP, STEP);
    }

    private static Vector bezier(Vector a, Vector c, Vector b, double t) {
        double u = 1 - t;
        return a.clone().multiply(u * u).add(c.clone().multiply(2 * u * t)).add(b.clone().multiply(t * t));
    }

    private void finish(Pull pull) {
        finish(pull, true);
    }

    private void finish(Pull pull, boolean land) {
        if (pull.finishing) return;
        pull.finishing = true;
        active.remove(pull.player.getUniqueId(), pull);
        if (pull.task != null) pull.task.cancel();
        pull.seat.eject();
        pull.seat.remove();
        if (land && pull.player.isOnline()) {
            pull.player.setFallDistance(0);
            pull.player.teleportAsync(pull.target).whenComplete((ok, err) -> pull.done.run());
        } else {
            pull.done.run();
        }
    }

    /** Ends a pull early (the player still lands on the target). */
    public void cancel(UUID player) {
        Pull pull = active.get(player);
        if (pull != null) finish(pull);
    }

    /** Drops the player off where they are (no landing teleport); used when the match ends mid-pull. */
    public void abort(UUID player) {
        Pull pull = active.get(player);
        if (pull != null) finish(pull, false);
    }

    public void cancelAll() {
        for (Pull pull : new ArrayList<>(active.values())) finish(pull, false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDismount(EntityDismountEvent event) {
        Pull pull = active.get(event.getEntity().getUniqueId());
        if (pull != null && !pull.finishing && event.getDismounted() == pull.seat && event.isCancellable()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Pull pull = active.get(event.getPlayer().getUniqueId());
        if (pull != null) finish(pull, false);
    }
}
