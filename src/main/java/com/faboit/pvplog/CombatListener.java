package com.faboit.pvplog;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Collection;
import java.util.Locale;
import java.util.UUID;

public final class CombatListener implements Listener {

    private final PvPLogPlugin plugin;

    CombatListener(PvPLogPlugin plugin) {
        this.plugin = plugin;
    }

    private CombatManager combat() {
        return plugin.combatManager();
    }

    private Settings settings() {
        return plugin.settings();
    }

    // ------------------------------------------------------------------ tagging

    private void tagPair(Player victim, Player attacker) {
        if (victim.equals(attacker)) return;
        if (settings().ignoreFriends() && plugin.friendHook().areFriends(victim, attacker)) return;
        combat().tag(victim, attacker, false);
        if (settings().tagAttacker()) combat().tag(attacker, victim, false);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFriendlyFire(EntityDamageEvent event) {
        if (!settings().preventFriendDamage() || !(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolveAttacker(event);
        if (attacker != null && !attacker.equals(victim) && plugin.friendHook().areFriends(victim, attacker)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolveAttacker(event);
        if (attacker != null) tagPair(victim, attacker);
    }

    private Player resolveAttacker(EntityDamageEvent event) {
        DamageSource source = event.getDamageSource();
        Entity direct = source.getDirectEntity();
        Entity causing = source.getCausingEntity();
        if (direct == null && causing == null) return null;

        EntityDamageEvent.DamageCause cause = event.getCause();
        Settings s = settings();

        if (cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            return s.tagExplosions() && causing instanceof Player p ? p : null;
        }
        if (direct instanceof ThrownPotion || direct instanceof AreaEffectCloud) {
            return s.tagPotions() && causing instanceof Player p ? p : null;
        }
        if (direct instanceof Projectile) {
            return s.tagProjectiles() && causing instanceof Player p ? p : null;
        }
        if (direct instanceof Player p) {
            return s.tagMelee() ? p : null;
        }
        if (direct instanceof Tameable pet && s.tagPets() && pet.getOwnerUniqueId() != null) {
            Player owner = Bukkit.getPlayer(pet.getOwnerUniqueId());
            return owner != null && owner.isOnline() ? owner : null;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        if (!settings().tagPotions()) return;
        if (!(event.getPotion().getShooter() instanceof Player thrower)) return;
        if (!isHarmful(event.getPotion().getEffects())) return;
        for (LivingEntity entity : event.getAffectedEntities()) {
            if (entity instanceof Player victim && event.getIntensity(victim) > 0) {
                tagPair(victim, thrower);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLingering(AreaEffectCloudApplyEvent event) {
        if (!settings().tagPotions()) return;
        AreaEffectCloud cloud = event.getEntity();
        if (!(cloud.getSource() instanceof Player thrower)) return;
        boolean harmful = isHarmful(cloud.getCustomEffects())
                || (cloud.getBasePotionType() != null && isHarmful(cloud.getBasePotionType().getPotionEffects()));
        if (!harmful) return;
        for (LivingEntity entity : event.getAffectedEntities()) {
            if (entity instanceof Player victim) tagPair(victim, thrower);
        }
    }

    private static boolean isHarmful(Collection<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            if (effect.getType().getCategory() == PotionEffectTypeCategory.HARMFUL) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (!settings().tagFishingRod()) return;
        if (event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY && event.getCaught() instanceof Player victim) {
            tagPair(victim, event.getPlayer());
        }
    }

    // ------------------------------------------------------------------ combat log

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!combat().isTagged(player)) {
            combat().untag(player, false);
            return;
        }
        Settings s = settings();
        boolean kicked = event.getReason() == PlayerQuitEvent.QuitReason.KICKED;
        boolean punish = s.killOnLogout() && !Bukkit.isStopping() && (!kicked || s.punishOnKick())
                && !player.hasPermission("pvplog.bypass") && !player.isDead();

        UUID lastAttacker = combat().lastAttacker(player);
        Player killer = lastAttacker == null ? null : Bukkit.getPlayer(lastAttacker);
        combat().untag(player, false);

        if (!punish) return;

        if (s.creditLastAttacker() && killer != null && !killer.equals(player)) {
            player.setKiller(killer);
        }
        player.setHealth(0.0);

        var broadcast = s.message("combat-logged-broadcast", "player", player.getName());
        if (broadcast != null) {
            // Sending chat is thread-safe on Folia; deliver to everyone individually plus console.
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(player)) online.sendMessage(broadcast);
            }
            Bukkit.getConsoleSender().sendMessage(broadcast);
        }

        String killerName = killer == null ? "none" : killer.getName();
        for (String cmd : s.combatLogCommands()) {
            String command = cmd.replace("{player}", player.getName()).replace("{killer}", killerName);
            // Console commands must run on the global region thread on Folia (main thread on Paper).
            Bukkit.getGlobalRegionScheduler().execute(plugin,
                    () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        }
        plugin.getLogger().info(player.getName() + " combat logged (last attacker: " + killerName + ")");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        combat().untag(victim, false);
        Player killer = victim.getKiller();
        if (killer != null && settings().untagKillerOnKill()) {
            combat().untag(killer, true);
        }
    }

    // ------------------------------------------------------------------ restrictions

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!combat().isTagged(player) || player.hasPermission("pvplog.bypass.commands")) return;

        String message = event.getMessage();
        if (message.length() < 2) return;
        String label = message.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        String stripped = stripNamespace(label);

        if (isBlocked(stripped)) {
            event.setCancelled(true);
            CombatManager.send(player, settings().message("command-blocked", "command", stripped));
        }
    }

    private boolean isBlocked(String label) {
        Settings s = settings();
        if (s.isCommandBlocked(label)) {
            // In whitelist mode, still allow if the command is an alias of a whitelisted command.
            Command command = Bukkit.getCommandMap().getCommand(label);
            if (command == null) return true;
            if (!s.isCommandBlocked(command.getName().toLowerCase(Locale.ROOT))) return false;
            for (String alias : command.getAliases()) {
                if (!s.isCommandBlocked(alias.toLowerCase(Locale.ROOT))) return false;
            }
            return true;
        }
        // Label is allowed; block it anyway if it's an alias of a listed command (blacklist mode).
        Command command = Bukkit.getCommandMap().getCommand(label);
        if (command == null) return false;
        if (s.isCommandBlocked(command.getName().toLowerCase(Locale.ROOT))) return true;
        for (String alias : command.getAliases()) {
            if (s.isCommandBlocked(alias.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static String stripNamespace(String label) {
        int colon = label.indexOf(':');
        return colon >= 0 ? label.substring(colon + 1) : label;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (!event.isGliding() || !settings().disableElytra()) return;
        if (event.getEntity() instanceof Player player && combat().isTagged(player)) {
            event.setCancelled(true);
            CombatManager.send(player, settings().message("elytra-blocked"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!event.isFlying() || !settings().disableFlight() || !combat().isTagged(player)) return;
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) return;
        event.setCancelled(true);
        player.setAllowFlight(false);
        CombatManager.send(player, settings().message("flight-blocked"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!settings().isTeleportBlocked(event.getCause()) || !combat().isTagged(player)) return;
        event.setCancelled(true);
        CombatManager.send(player, settings().message("teleport-blocked"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        ProjectileSource shooter = projectile.getShooter();
        if (!(shooter instanceof Player player) || !combat().isTagged(player)) return;

        final Material item;
        final int cooldown;
        if (projectile instanceof EnderPearl) {
            item = Material.ENDER_PEARL;
            cooldown = settings().enderPearlCooldown();
        } else if (projectile instanceof WindCharge) {
            item = Material.WIND_CHARGE;
            cooldown = settings().windChargeCooldown();
        } else {
            return;
        }
        if (cooldown < 0) return;
        // Vanilla applies its own cooldown after launching, so override it on the next tick.
        player.getScheduler().run(plugin, task -> player.setCooldown(item, cooldown), null);
    }
}
