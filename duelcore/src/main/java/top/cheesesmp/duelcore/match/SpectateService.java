package top.cheesesmp.duelcore.match;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import top.cheesesmp.duelcore.arena.ArenaInstance;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.kit.KitManager;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;

/**
 * Watching live matches. Spectators fly around the arena in adventure mode, invulnerable and hidden from everyone
 * fighting (other spectators see them); they can't hit anyone, use blocks or pick anything up, and projectiles pass
 * through them. Flying far off their match's arena brings them back. They keep their queue entries. Fighters with {@link Setting#SPECTATOR_ALERTS}
 * are told in chat when someone starts or stops watching (see {@link #alert}).
 */
public final class SpectateService implements Listener, Runnable {

    /** How far (blocks) spectators may fly past the arena's sides, top and floor before they're brought back. */
    private static final double MARGIN = 24;

    public enum Result { OK, NOT_IN_MATCH, SELF, BUSY, DISALLOWED, NO_ARENA }

    private final DuelCorePlugin plugin;
    private final Map<UUID, Match> spectating = new HashMap<>();

    public SpectateService(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    public @Nullable Match spectating(UUID uuid) {
        return spectating.get(uuid);
    }

    public int count() {
        return spectating.size();
    }

    public Result spectate(Player viewer, Player target) {
        if (viewer.equals(target)) return Result.SELF;
        Match match = plugin.matches().match(target.getUniqueId());
        if (match == null || match.isOver()) return Result.NOT_IN_MATCH;
        PlayerProfile targetProfile = plugin.profiles().get(target);
        if (targetProfile != null && !targetProfile.setting(Setting.ALLOW_SPECTATORS)
            && !viewer.hasPermission("duelcore.spectate.bypass")) {
            return Result.DISALLOWED;
        }
        return spectate(viewer, match, target.getLocation());
    }

    public Result spectate(Player viewer, Match match, @Nullable Location focus) {
        if (plugin.matches().match(viewer.getUniqueId()) != null) return Result.BUSY;
        if (match.arena() == null) return Result.NO_ARENA;
        Match previous = spectating.remove(viewer.getUniqueId());
        if (previous != null) previous.spectators().remove(viewer.getUniqueId());
        if (previous != null && previous != match) alert(previous, viewer, false);
        spectating.put(viewer.getUniqueId(), match);
        match.spectators().add(viewer.getUniqueId());
        plugin.queueMusic().stop(viewer.getUniqueId()); // they stay queued, but the match is what they hear now
        plugin.tags().update(viewer); // grey, italic and last in the tab list
        Location to = focus != null && match.arena().contains(focus) ? focus.clone().add(0, 3, 0) : match.arena().center().add(0, 6, 0);
        KitManager.resetState(viewer, 20);
        if (viewer.getGameMode() == GameMode.SPECTATOR) viewer.setGameMode(GameMode.ADVENTURE);
        viewer.setInvulnerable(true);
        viewer.setCollidable(false);
        boolean arriving = previous != match;
        viewer.teleportAsync(to).thenRun(() -> {
            if (!viewer.isOnline() || spectating.get(viewer.getUniqueId()) != match) return;
            viewer.setAllowFlight(true);
            viewer.setFlying(true);
            plugin.hub().giveSpectatorItems(viewer);
            plugin.visibility().refresh(viewer);
            plugin.sidebar().refresh(viewer);
            // after the refresh: a fighter may still have them hidden from the hub until then
            if (arriving) alert(match, viewer, true);
        });
        if (isFreeForAll(match)) {
            plugin.messages().send(viewer, "spectate.started-ffa", Messages.num("players", match.participants().size()),
                Messages.num("alive", match.alive()), Messages.comp("kit", match.kit().displayName()));
        } else {
            plugin.messages().send(viewer, "spectate.started", Messages.text("red", match.teamName(0)),
                Messages.text("blue", match.teamName(1)), Messages.comp("kit", match.kit().displayName()));
        }
        return Result.OK;
    }

    /**
     * Tells the fighters of a match that is still on that {@code spectator} started (or stopped) watching it, as far as
     * {@link SpectatorAlerts} lets through. Only fighters with {@link Setting#SPECTATOR_ALERTS} on who can see the
     * spectator (a vanished one stays unannounced, as in the tab list); staff watching through
     * {@code duelcore.spectate.bypass} aren't announced to fighters who don't allow spectators.
     */
    private void alert(Match match, Player spectator, boolean started) {
        if (match.isOver()) return; // everyone is leaving anyway
        UUID id = spectator.getUniqueId();
        SpectatorAlerts alerts = match.spectatorAlerts;
        if (!(started ? alerts.start(id, System.currentTimeMillis()) : alerts.stop(id))) return;
        boolean bypass = spectator.hasPermission("duelcore.spectate.bypass");
        for (Participant p : match.participants()) {
            Player fighter = plugin.getServer().getPlayer(p.uuid());
            if (fighter == null || p.left() || plugin.matches().match(p.uuid()) != match) continue;
            if (!fighter.canSee(spectator)) continue;
            PlayerProfile profile = plugin.profiles().get(fighter);
            if (profile != null && !profile.setting(Setting.SPECTATOR_ALERTS)) continue;
            if (bypass && profile != null && !profile.setting(Setting.ALLOW_SPECTATORS)) continue;
            plugin.messages().send(fighter, started ? "spectate.alert-started" : "spectate.alert-stopped",
                Messages.text("player", spectator.getName()));
        }
    }

    /** A Party FFA (or any match of more than two teams): shown as players/alive instead of "red vs blue". */
    public static boolean isFreeForAll(Match match) {
        return match.ffa() || match.teamCount() > 2;
    }

    /** Stops spectating; sends to the hub when {@code toHub}. */
    public boolean leave(Player player, boolean toHub) {
        Match match = spectating.remove(player.getUniqueId());
        if (match == null) return false;
        match.spectators().remove(player.getUniqueId());
        alert(match, player, false);
        plugin.tags().update(player);
        if (player.getGameMode() == GameMode.SPECTATOR) player.setGameMode(GameMode.ADVENTURE);
        player.setCollidable(true);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvulnerable(false);
        plugin.visibility().refresh(player);
        if (toHub) plugin.hub().send(player);
        return true;
    }

    /** Spectators can't hit anyone (the fighters can't see them) or use blocks (doors, buttons, chests, plates). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpectatorAttack(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        Player attacker = event.getDamager() instanceof Player p ? p
            : event.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player s ? s : null;
        if (attacker != null && spectating.containsKey(attacker.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onSpectatorInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (!spectating.containsKey(event.getPlayer().getUniqueId())) return;
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpectatorInteractEntity(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (spectating.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    /** The spectator menu (number keys) teleports to any player on the server: only within the watched arena. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpectatorTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE) return;
        Match match = spectating.get(event.getPlayer().getUniqueId());
        if (match == null) return;
        ArenaInstance arena = match.arena();
        if (arena == null || !arena.contains(event.getTo())) event.setCancelled(true);
    }

    /** Every second: spectators who flew far off their arena go back (and keep flying after a mode change). */
    @Override
    public void run() {
        for (Map.Entry<UUID, Match> e : spectating.entrySet()) {
            Player player = plugin.getServer().getPlayer(e.getKey());
            ArenaInstance arena = e.getValue().arena();
            if (player == null || arena == null) continue;
            Location loc = player.getLocation();
            boolean away = loc.getWorld() != arena.world() || !arena.containsXZ(loc.getX(), loc.getZ(), MARGIN)
                || loc.getY() < arena.floorY() - MARGIN || loc.getY() > arena.floorY() + arena.template().sizeY() + MARGIN;
            if (!player.getAllowFlight()) player.setAllowFlight(true);
            if (!away) continue;
            if (player.getSpectatorTarget() != null) player.setSpectatorTarget(null);
            player.teleportAsync(arena.center().add(0, 6, 0));
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        Match match = spectating.remove(event.getPlayer().getUniqueId());
        if (match != null) {
            match.spectators().remove(event.getPlayer().getUniqueId());
            alert(match, event.getPlayer(), false);
        }
        event.getPlayer().setCollidable(true);
    }
}
