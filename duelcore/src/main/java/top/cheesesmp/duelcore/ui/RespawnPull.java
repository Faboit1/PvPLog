package top.cheesesmp.duelcore.ui;

import io.papermc.paper.math.Angle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.ui.RespawnMotion.Style;

/**
 * The between-round respawn animation: the player is brought back to their spawn in one of the configured styles
 * ({@code animations.respawn-styles}, see {@link RespawnMotion}), and can't move until they stand on it.
 *
 * <ul>
 *   <li><b>throw</b>: the player rides an invisible seat that the server moves along a real ballistic arc (the path
 *       a thrown player would fly, {@link ThrowMath#path}) every tick. The client interpolates the seat
 *       ({@link org.bukkit.entity.Display#setTeleportDuration}), so the flight is smooth, and a passenger has no air
 *       control: they can look around but not steer. Dismounting (sneak) is cancelled while it runs. The launch and
 *       the landing are softened ({@link ThrowMath#soften}): the flight speeds up from and slows down to a stop.</li>
 *   <li><b>float</b>: the same seat, straight up, across and gently down onto the spawn ({@link RespawnPaths#floatUp}).</li>
 *   <li><b>orbit</b>: the same seat, in a rising spiral round the arena centre with the view on it, down onto the
 *       spawn ({@link RespawnPaths#orbit}).</li>
 *   <li><b>swoop</b>: the same seat, up and back behind the spawn looking over the arena, a short pause, then a dive
 *       that levels out onto the spawn ({@link RespawnPaths#swoop}).</li>
 *   <li><b>look-down</b>: the view tilts smoothly down to the ground, the player is teleported onto the spawn still
 *       looking down, and the view tilts smoothly back up.</li>
 *   <li><b>spin</b>: one smooth 360° turn, teleported onto the spawn exactly half way through it.</li>
 * </ul>
 *
 * <p>For look-down and spin a player who isn't standing on the ground (the death cam, a jump) is held on a seat
 * until the teleport; on the ground the caller's freeze (no walking or jumping) is enough. The view is turned once
 * per tick by the <em>change</em> since the last tick ({@link Player#setRotation(Angle, Angle)} with relative angles):
 * the client adds it to its own view, so mouse movement in between is kept instead of being snapped back every tick
 * (the old absolute rotations made the view jitter while the player moved their mouse). Free-look flights (throw,
 * float) turn the view into the spawn's facing over their last {@link RespawnMotion#LAND_TURN_TICKS} ticks, and
 * every animation ends with the exact spawn facing, so the final teleport never snaps the view round.
 *
 * <p>Nothing here gives the player velocity: a flight moves the seat and the player rides it (a server-moved
 * vehicle, which anticheats expect), and the rest are rotations and teleports.
 *
 * <p>Robustness: players whose ping is above {@code animations.respawn-throw-max-ping} are teleported instead;
 * throws that would clip terrain are raised, and become a teleport when no height clears them or the arc would be
 * faster than {@link #MAX_SPEED}; a float, orbit or swoop that doesn't fit the arena tries a smaller shape and
 * becomes a throw when none fits. The seats are not persistent and carry {@link SpawnRise#KEEP_TAG}, so an arena reset
 * leaves them alone; every exit (arrival, quit, match end, another teleport, plugin disable) removes them.
 */
public final class RespawnPull implements Listener {

    /** Extra height tried, in order, when the arc would clip terrain. */
    private static final double[] RAISE = {0, 4, 8, 14, 22};
    /** Heights above the feet checked for a free path (the player is 1.8 tall). */
    private static final double[] BODY = {0.1, 0.9, 1.7};
    /** Half the player's width, for the corners of the path check. */
    private static final double HALF_WIDTH = 0.3;
    /** Fastest flight (blocks per tick, horizontally) before a throw becomes a teleport. */
    static final double MAX_SPEED = 6.0;
    /**
     * How far a passenger's feet sit below its seat: a player attaches to a vehicle 0.6 above its feet
     * ({@code Avatar.DEFAULT_VEHICLE_ATTACHMENT}) and a display has no height, so its riders sit on its origin.
     */
    static final double RIDE_OFFSET = 0.6;
    /**
     * Ticks the client takes to glide the seat to each new position. Two ticks per one-tick move keeps the camera
     * smooth even when a packet comes in late.
     */
    private static final int SEAT_GLIDE_TICKS = 2;
    /** Ticks over which a throw speeds up from a standstill at the launch (see {@link ThrowMath#soften}). */
    static final int THROW_EASE_IN = 6;
    /** Ticks over which a throw slows down to a stop onto the spawn. */
    static final int THROW_EASE_OUT = 8;
    /** A player this close (squared, blocks) to the spawn at the end of a rotation is not teleported again. */
    private static final double ON_SPAWN_SQ = 0.25 * 0.25;
    private static final String TAG = "duelcore_pull";

    private enum Stage { RUNNING, SNAPPING, DONE }

    /** A planned flight: how long it takes, how high it goes and where the player is after each tick. */
    private record Arc(int ticks, double top, double raise, double[][] path) {
    }

    private final DuelCorePlugin plugin;
    private final Map<UUID, Pull> active = new HashMap<>();

    private static final class Pull {
        final Player player;
        final Location target;
        /** What orbit and swoop look at (the arena centre), or null for the middle between start and spawn. */
        final @Nullable Location focus;
        final Style style;
        final List<Player> viewers;
        final Runnable done;
        @Nullable BukkitTask task;
        Stage stage = Stage.RUNNING;
        /** The seat the player rides, while they are held on one. */
        @Nullable ItemDisplay seat;
        /** True while the seat is being moved: the player's own teleport that comes with it is expected. */
        boolean movingSeat;
        /** The mid-animation teleport onto the spawn has landed. */
        volatile boolean arrived;

        Pull(Player player, Location target, @Nullable Location focus, Style style, List<Player> viewers, Runnable done) {
            this.player = player;
            this.target = target;
            this.focus = focus;
            this.style = style;
            this.viewers = viewers;
            this.done = done;
        }
    }

    public RespawnPull(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** True while the player is in a respawn animation (their position is driven by it). */
    public boolean pulling(UUID player) {
        return active.containsKey(player);
    }

    /**
     * True while the player rides an animation's seat: the server moves them, so the freeze's move rollback must leave
     * them alone. A player standing through a look-down or spin is not carried and stays under the rollback, so a
     * client that ignores the walk speed still can't walk off during the turn.
     */
    public boolean carried(UUID player) {
        Pull t = active.get(player);
        return t != null && t.seat != null;
    }

    public int activeCount() {
        return active.size();
    }

    /** Brings the player to {@code target} in a style picked at random from the configured ones. */
    public void pull(Player player, Location target, double minHeight, List<Player> viewers, Runnable done) {
        pull(player, target, minHeight, viewers, null, done);
    }

    /**
     * Brings the player to {@code target} with the respawn animation {@code style} (null: picked at random from
     * {@code animations.respawn-styles}). {@code minHeight} is how far the top of a throw rises above the higher end
     * point at least (longer throws go higher). Falls back to a teleport for another world, an absurd distance, a
     * high ping or a throw that can't clear the terrain. {@code done} always runs exactly once, on the main thread,
     * after the player is standing on the target (or the animation was aborted).
     */
    public void pull(Player player, Location target, double minHeight, List<Player> viewers, @Nullable Style style,
                     Runnable done) {
        pull(player, target, null, minHeight, viewers, style, done);
    }

    /**
     * Like {@link #pull(Player, Location, double, List, Style, Runnable)}; {@code focus} is the arena centre that the
     * orbit circles and the swoop looks at (null: the middle between the player and the target). {@code minHeight}
     * is also how high a float, orbit or swoop rises.
     */
    public void pull(Player player, Location target, @Nullable Location focus, double minHeight, List<Player> viewers,
                     @Nullable Style style, Runnable done) {
        cancel(player.getUniqueId());
        Style s = style != null ? style : plugin.settings().animRespawnStyles.pick(ThreadLocalRandom.current().nextDouble());
        Pull t = new Pull(player, target.clone(), focus == null || focus.getWorld() != target.getWorld() ? null
            : focus.clone(), s, viewers, done);
        active.put(player.getUniqueId(), t);
        if (player.getWorld() != target.getWorld() || player.getLocation().distanceSquared(target) > 300 * 300) {
            snap(t);
            return;
        }
        boolean wasSpectator = player.getGameMode() == GameMode.SPECTATOR;
        if (wasSpectator) player.setGameMode(GameMode.SURVIVAL);
        player.leaveVehicle();
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setFallDistance(0);
        player.setVelocity(new Vector());
        int ping = Math.max(0, player.getPing());
        int maxPing = plugin.settings().animRespawnThrowMaxPing;
        if (maxPing > 0 && ping > maxPing) {
            verbose("[respawn] %s teleported instead of %s: ping %d ms is above %d", player.getName(), s.key, ping, maxPing);
            snap(t);
            return;
        }
        switch (s) {
            case THROW -> startThrow(t, minHeight);
            case FLOAT, ORBIT, SWOOP -> startFlight(t, minHeight);
            case LOOK_DOWN, SPIN -> startTurn(t, wasSpectator || !standing(player));
        }
    }

    // ------------------------------------------------------------------ throw

    private void startThrow(Pull t, double minHeight) {
        Player player = t.player;
        Location target = t.target;
        Location start = freeSpot(player.getLocation()); // the death cam may end inside terrain
        Arc arc = plan(start, target, minHeight);
        if (arc == null) {
            verbose("[respawn] %s teleported instead: no arc clears the terrain or it would be too fast", player.getName());
            snap(t);
            return;
        }
        if (!seat(t, start, SEAT_GLIDE_TICKS)) {
            verbose("[respawn] %s teleported instead: could not seat them for the throw", player.getName());
            snap(t);
            return;
        }
        verbose("[respawn] %s throw %.0f blocks, top %.1f above start%s, %d ticks, ping %d ms", player.getName(),
            Math.hypot(target.getX() - start.getX(), target.getZ() - start.getZ()), arc.top() - start.getY(),
            arc.raise() > 0 ? String.format(java.util.Locale.ROOT, " (raised %.0f over terrain)", arc.raise()) : "",
            arc.ticks(), player.getPing());
        fly(t, start, new RespawnPaths.Flight(ThrowMath.soften(arc.path(), THROW_EASE_IN, THROW_EASE_OUT), null), true);
    }

    /**
     * The lowest arc (at least {@code minHeight} above the higher end, a bit more for long throws) whose path the
     * player's body fits through, raising it over trees and hills; null when none does (the barrier ceiling) or the
     * flight would be faster than {@link #MAX_SPEED}.
     */
    private static @Nullable Arc plan(Location start, Location target, double minHeight) {
        World world = start.getWorld();
        double dx = target.getX() - start.getX();
        double dz = target.getZ() - start.getZ();
        double horizontal = Math.hypot(dx, dz);
        double base = Math.max(start.getY(), target.getY()) + Math.clamp(minHeight + horizontal * 0.2, minHeight, minHeight + 16);
        for (double raise : RAISE) {
            double top = base + raise;
            if (top + 2 >= world.getMaxHeight()) break;
            double bulge = top - (start.getY() + target.getY()) / 2;
            int ticks = ThrowMath.ticks(bulge);
            double[] v = ThrowMath.launch(dx, target.getY() - start.getY(), dz, ticks, false);
            if (Math.hypot(v[0], v[2]) > MAX_SPEED) return null;
            double[][] path = ThrowMath.path(v, ticks, false);
            if (clear(world, start, target, path)) return new Arc(ticks, top, raise, path);
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

    // ------------------------------------------------------------------ float, orbit, swoop

    /**
     * Float, orbit or swoop: plans the flight from where the player is ({@link RespawnPaths}), trying smaller shapes
     * when one doesn't fit through the arena (walls, ceiling, terrain), and falls back to a throw when none does.
     */
    private void startFlight(Pull t, double minHeight) {
        Player player = t.player;
        Location start = freeSpot(player.getLocation()); // the death cam may end inside terrain
        Location target = t.target;
        double[] d = {target.getX() - start.getX(), target.getY() - start.getY(), target.getZ() - start.getZ()};
        Location focus = t.focus != null ? t.focus
            : new Location(target.getWorld(), (start.getX() + target.getX()) / 2, Math.min(start.getY(), target.getY()),
                (start.getZ() + target.getZ()) / 2);
        double[] c = {focus.getX() - start.getX(), focus.getY() - start.getY(), focus.getZ() - start.getZ()};
        float yaw0 = player.getLocation().getYaw();
        float pitch0 = player.getLocation().getPitch();
        float yaw1 = target.getYaw();
        float pitch1 = target.getPitch();
        List<RespawnPaths.Flight> options = new ArrayList<>();
        switch (t.style) {
            case FLOAT -> {
                double horizontal = Math.hypot(d[0], d[2]);
                for (double lift : new double[] {minHeight, minHeight * 0.6, minHeight + 6}) {
                    options.add(RespawnPaths.floatUp(d, lift, RespawnPaths.floatTicks(horizontal, lift)));
                }
            }
            case ORBIT -> {
                for (double lift : new double[] {minHeight * 0.8, minHeight * 0.4}) {
                    options.add(RespawnPaths.orbit(d, c, lift, yaw0, pitch0, yaw1, pitch1));
                }
            }
            default -> { // swoop: as far back as fits, then lower
                for (double height : new double[] {minHeight, minHeight * 0.6}) {
                    for (double back : new double[] {8, 4, 0}) {
                        double[] top = RespawnPaths.swoopTop(d, c, yaw1, back, height);
                        options.add(RespawnPaths.swoop(d, c, top, yaw0, pitch0, yaw1, pitch1));
                    }
                }
            }
        }
        for (RespawnPaths.Flight f : options) {
            if (RespawnPaths.maxStep(f.path()) > MAX_SPEED || !clear(start.getWorld(), start, target, f.path())) continue;
            if (!seat(t, start, SEAT_GLIDE_TICKS)) {
                verbose("[respawn] %s teleported instead: could not seat them for the %s", player.getName(), t.style.key);
                snap(t);
                return;
            }
            verbose("[respawn] %s %s %.0f blocks, %d ticks, ping %d ms", player.getName(), t.style.key,
                Math.hypot(d[0], d[2]), f.ticks(), player.getPing());
            fly(t, start, f, false);
            return;
        }
        verbose("[respawn] %s throw instead of %s: it doesn't fit through the arena", player.getName(), t.style.key);
        startThrow(t, minHeight);
    }

    /**
     * Carries the seated player along {@code flight} (offsets from {@code start}, one per tick), turning the view with
     * it, then stands them exactly on the spawn. A free-look flight (no facing) turns the view into the spawn's
     * facing over its last {@link RespawnMotion#LAND_TURN_TICKS} ticks. {@code thud}: the throw's heavier landing.
     */
    private void fly(Pull t, Location start, RespawnPaths.Flight flight, boolean thud) {
        Player player = t.player;
        Location target = t.target;
        double[][] path = flight.path();
        float[][] facing = flight.facing();
        int ticks = path.length;
        int turnFrom = Math.max(0, ticks - RespawnMotion.LAND_TURN_TICKS);
        Particle particle = thud ? Particle.CLOUD : Particle.END_ROD;
        t.task = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            int k;
            /** The view the animation set last (the client may have added mouse movement on top of it). */
            float[] view = {player.getLocation().getYaw(), player.getLocation().getPitch()};
            float[] turnStart = view;

            @Override
            public void run() {
                if (t.stage != Stage.RUNNING) return;
                if (!player.isOnline()) {
                    complete(t);
                    return;
                }
                if (!seated(t)) { // the seat went away underneath them: finish on the spawn
                    snap(t);
                    return;
                }
                if (k < ticks) {
                    double[] at = path[k];
                    moveSeat(t, start.clone().add(at[0], at[1], at[2]));
                    if (facing != null) {
                        view = turn(player, view, facing[k]);
                    } else if (k >= turnFrom) {
                        if (k == turnFrom) { // from wherever they are looking now
                            turnStart = new float[] {player.getLocation().getYaw(), player.getLocation().getPitch()};
                            view = turnStart;
                        }
                        view = turn(player, view, RespawnMotion.landTurn(turnStart[0], turnStart[1], target.getYaw(),
                            target.getPitch(), k + 1 - turnFrom, ticks - turnFrom));
                    }
                    player.setFallDistance(0);
                    if (k % 2 == 0) trail(t, player.getLocation().add(0, 0.2, 0), particle);
                    k++;
                    return;
                }
                // let the clients' glide onto the last position finish, then stand them exactly on the spawn
                if (k++ < ticks + SEAT_GLIDE_TICKS) return;
                landingEffects(t.viewers, target, thud);
                snap(t);
            }
        }, 1L, 1L);
    }

    /**
     * Turns the player's view from {@code from} (what the animation set last) to {@code to} by sending only the
     * change: the client adds it to its own view, so mouse movement in between is kept instead of being snapped back
     * every tick. Returns {@code to}.
     */
    private static float[] turn(Player player, float[] from, float[] to) {
        float yaw = RespawnMotion.wrap(to[0] - from[0]);
        float pitch = to[1] - from[1];
        if (yaw != 0 || pitch != 0) player.setRotation(Angle.relative(yaw), Angle.relative(pitch));
        return to;
    }

    // ------------------------------------------------------------------ look-down and spin

    /**
     * Look-down or spin: rotation only, one step per tick, with the teleport onto the spawn in the middle.
     * {@code hold} puts a player who isn't standing on the ground on a seat until then, so they can't drift.
     */
    private void startTurn(Pull t, boolean hold) {
        Player player = t.player;
        Location from = player.getLocation();
        if (hold && !seat(t, freeSpot(from), 0)) {
            verbose("[respawn] %s teleported instead: could not hold them for the %s", player.getName(), t.style.key);
            snap(t);
            return;
        }
        float startYaw = from.getYaw();
        float startPitch = from.getPitch();
        float endYaw = t.target.getYaw();
        float endPitch = t.target.getPitch();
        verbose("[respawn] %s %s%s, ping %d ms", player.getName(), t.style.key, hold ? " (held on a seat)" : "",
            player.getPing());
        t.task = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            int k;
            int held;
            /** The view the animation set last. */
            float[] view = {startYaw, startPitch};

            @Override
            public void run() {
                if (t.stage != Stage.RUNNING) return;
                if (!player.isOnline()) {
                    complete(t);
                    return;
                }
                if (t.seat != null && !seated(t)) { // the seat went away underneath them: finish on the spawn
                    snap(t);
                    return;
                }
                player.setFallDistance(0);
                k++;
                if (t.style == Style.SPIN) {
                    int ticks = RespawnMotion.SPIN_TICKS;
                    float yaw = RespawnMotion.spinYaw(startYaw, endYaw, k, ticks);
                    float pitch = RespawnMotion.spinPitch(startPitch, endPitch, k, ticks);
                    if (k == RespawnMotion.spinTeleportTick(ticks)) jump(t, yaw, pitch);
                    else turn(player, view, new float[] {yaw, pitch});
                    view = new float[] {yaw, pitch};
                    if (k >= ticks) land(t);
                    return;
                }
                // look-down: tilt down (keeping their own yaw), jump, wait for the landing, tilt back up
                int look = RespawnMotion.LOOK_TICKS;
                if (k < look) {
                    view = turn(player, view, new float[] {view[0], RespawnMotion.lookDown(startPitch, k, look)});
                    return;
                }
                if (k == look) {
                    jump(t, endYaw, RespawnMotion.DOWN);
                    view = new float[] {endYaw, RespawnMotion.DOWN};
                    return;
                }
                if (!t.arrived && held++ < RespawnMotion.LOOK_HOLD_MAX_TICKS) {
                    k--; // still waiting at the bottom
                    return;
                }
                int up = k - look - 1;
                view = turn(player, view, new float[] {endYaw, RespawnMotion.lookUp(endPitch, up, look)});
                if (up >= look) land(t);
            }
        }, 1L, 1L);
    }

    /** The teleport in the middle of a rotation: off the seat and onto the spawn, facing {@code yaw}/{@code pitch}. */
    private void jump(Pull t, float yaw, float pitch) {
        Player player = t.player;
        removeSeat(t);
        Location to = t.target.clone();
        to.setYaw(yaw);
        to.setPitch(pitch);
        Location from = player.getLocation();
        for (Player v : t.viewers) {
            if (!v.isOnline()) continue;
            if (v.getWorld() == from.getWorld()) {
                v.spawnParticle(Particle.REVERSE_PORTAL, from.clone().add(0, 1, 0), 24, 0.25, 0.5, 0.25, 0.02);
            }
            if (v.getWorld() == to.getWorld()) {
                v.playSound(to, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.45f, 1.3f);
                v.spawnParticle(Particle.REVERSE_PORTAL, to.clone().add(0, 1, 0), 24, 0.25, 0.5, 0.25, 0.02);
            }
        }
        player.setVelocity(new Vector());
        player.setFallDistance(0);
        player.teleportAsync(to).whenComplete((ok, err) -> t.arrived = true);
    }

    /** End of a rotation: facing the spawn's direction, and on the spawn (teleported there if something moved them). */
    private void land(Pull t) {
        if (t.stage != Stage.RUNNING) return;
        Player player = t.player;
        player.setRotation(t.target.getYaw(), t.target.getPitch());
        if (player.getWorld() == t.target.getWorld() && player.getLocation().distanceSquared(t.target) <= ON_SPAWN_SQ) {
            complete(t);
        } else {
            snap(t);
        }
    }

    // ------------------------------------------------------------------ seat

    /**
     * Puts the player on an invisible seat so their feet are at {@code feet}: a passenger can't walk, jump or steer.
     * False when the seat could not be spawned or mounted (another plugin cancelled it).
     */
    private boolean seat(Pull t, Location feet, int glide) {
        Location at = feet.clone().add(0, RIDE_OFFSET, 0);
        at.setYaw(0);
        at.setPitch(0);
        ItemDisplay seat = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(SpawnRise.KEEP_TAG);
            d.addScoreboardTag(TAG);
            d.setTeleportDuration(glide);
        });
        t.seat = seat;
        boolean mounted;
        t.movingSeat = true; // mounting re-sends the player's position: not a teleport from something else
        try {
            mounted = seat.isValid() && seat.addPassenger(t.player);
        } finally {
            t.movingSeat = false;
        }
        if (!mounted) {
            removeSeat(t);
            return false;
        }
        return true;
    }

    /** True while the player still rides their seat. */
    private static boolean seated(Pull t) {
        ItemDisplay seat = t.seat;
        return seat != null && seat.isValid() && t.player.getVehicle() == seat;
    }

    /** Moves the seat (and the player on it) so the player's feet are at {@code feet}. */
    private static void moveSeat(Pull t, Location feet) {
        ItemDisplay seat = t.seat;
        if (seat == null) return;
        Location at = feet.clone().add(0, RIDE_OFFSET, 0);
        at.setYaw(0);
        at.setPitch(0);
        t.movingSeat = true;
        try {
            seat.teleport(at); // riders come along (Paper 26.2 always carries passengers)
        } finally {
            t.movingSeat = false;
        }
    }

    /** Takes the player off the seat and removes it (the dismount is allowed: the seat is forgotten first). */
    private static void removeSeat(Pull t) {
        ItemDisplay seat = t.seat;
        if (seat == null) return;
        t.seat = null;
        if (seat.isValid()) {
            seat.removePassenger(t.player);
            seat.remove();
        }
    }

    // ------------------------------------------------------------------ effects

    private static void trail(Pull t, Location at, Particle particle) {
        int count = particle == Particle.CLOUD ? 2 : 1;
        for (Player v : t.viewers) {
            if (v.isOnline() && v.getWorld() == at.getWorld()) {
                v.spawnParticle(particle, at, count, 0.1, 0.1, 0.1, 0.01);
            }
        }
    }

    /** The landing: a thud for the throw, a soft step for the gentler flights. */
    private static void landingEffects(List<Player> viewers, Location target, boolean thud) {
        for (Player v : viewers) {
            if (!v.isOnline() || v.getWorld() != target.getWorld()) continue;
            if (thud) v.playSound(target, Sound.ENTITY_PLAYER_BIG_FALL, 0.8f, 0.9f);
            else v.playSound(target, Sound.ENTITY_PLAYER_SMALL_FALL, 0.7f, 1.1f);
            v.spawnParticle(Particle.CLOUD, target.clone().add(0, 0.1, 0), thud ? 8 : 5, 0.3, 0.05, 0.3, 0.02);
        }
    }

    /** True when the player stands on a solid block (checked on the server, not the client's own claim). */
    private static boolean standing(Player player) {
        return player.getLocation().subtract(0, 0.08, 0).getBlock().isSolid();
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

    // ------------------------------------------------------------------ ending

    /** Ends the animation on the target: off the seat and teleported onto it; {@code done} runs once it landed. */
    private void snap(Pull t) {
        if (t.stage != Stage.RUNNING) return;
        t.stage = Stage.SNAPPING;
        if (t.task != null) t.task.cancel();
        t.task = null;
        removeSeat(t);
        Player p = t.player;
        if (!p.isOnline()) {
            complete(t);
            return;
        }
        p.setVelocity(new Vector());
        p.setFallDistance(0);
        if (plugin.settings().verbose && p.getWorld() == t.target.getWorld()) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT, "[respawn] %s ended %.2f blocks from the spawn",
                p.getName(), p.getLocation().distance(t.target)));
        }
        p.teleportAsync(t.target).whenComplete((ok, err) -> complete(t));
    }

    /** Ends the animation where the player is (no teleport) and runs its callback, once. */
    private void complete(Pull t) {
        if (t.stage == Stage.DONE) return;
        t.stage = Stage.DONE;
        if (t.task != null) t.task.cancel();
        t.task = null;
        removeSeat(t);
        active.remove(t.player.getUniqueId(), t);
        t.done.run();
    }

    private void verbose(String format, Object... args) {
        if (plugin.settings().verbose) plugin.getLogger().info(String.format(java.util.Locale.ROOT, format, args));
    }

    /** Ends an animation early; the player still ends up on the target. */
    public void cancel(UUID player) {
        Pull t = active.get(player);
        if (t != null) snap(t);
    }

    /** Ends an animation without moving the player (the match ended mid-flight). */
    public void abort(UUID player) {
        Pull t = active.get(player);
        if (t != null) complete(t);
    }

    public void cancelAll() {
        for (Pull t : new ArrayList<>(active.values())) complete(t);
        active.clear();
    }

    /** Sneaking off the seat is not allowed while it carries the player. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Pull t = active.get(player.getUniqueId());
        if (t != null && t.seat != null && event.getDismounted() == t.seat && event.isCancellable()) {
            event.setCancelled(true);
        }
    }

    /**
     * Something else teleported a seated player (a command, the match sending them away): the animation gives way and
     * ends where they go, without its own teleport.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Pull t = active.get(event.getPlayer().getUniqueId());
        if (t == null || t.seat == null || t.movingSeat) return;
        complete(t);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        abort(event.getPlayer().getUniqueId());
    }
}
