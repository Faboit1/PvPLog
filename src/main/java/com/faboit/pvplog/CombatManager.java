package com.faboit.pvplog;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Tracks who is in combat, drives the countdown display and expiry. */
public final class CombatManager {

    private final PvPLogPlugin plugin;
    private final Map<UUID, Tag> tags = new HashMap<>();
    private BukkitTask task;

    private static final class Tag {
        long expiresAt;
        UUID lastAttacker;
        BossBar bossBar;
    }

    CombatManager(PvPLogPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L);
    }

    void shutdown() {
        if (task != null) task.cancel();
        for (UUID uuid : tags.keySet().toArray(new UUID[0])) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) hideDisplay(player, tags.get(uuid));
        }
        tags.clear();
    }

    /** Whether this player can be tagged at all right now. */
    public boolean canTag(Player player) {
        if (player.hasPermission("pvplog.bypass")) return false;
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) return false;
        return !plugin.settings().isWorldDisabled(player.getWorld().getName());
    }

    /**
     * Put a player in combat (or refresh their timer).
     *
     * @param attacker the opponent, may be null
     */
    public void tag(Player player, Player attacker) {
        Settings settings = plugin.settings();
        Tag tag = tags.get(player.getUniqueId());
        boolean fresh = tag == null;
        if (fresh) {
            tag = new Tag();
            tags.put(player.getUniqueId(), tag);
        }
        tag.expiresAt = System.currentTimeMillis() + settings.combatDurationMillis();
        if (attacker != null) tag.lastAttacker = attacker.getUniqueId();

        if (fresh) {
            send(player, settings.message("tagged"));
            if (settings.taggedSound() != null) player.playSound(settings.taggedSound());
            applyRestrictions(player);
        }
        updateDisplay(player, tag);
    }

    /** Remove a player from combat. */
    public void untag(Player player, boolean notify) {
        Tag tag = tags.remove(player.getUniqueId());
        if (tag == null) return;
        hideDisplay(player, tag);
        if (notify) {
            Settings settings = plugin.settings();
            send(player, settings.message("untagged"));
            if (settings.untaggedSound() != null) player.playSound(settings.untaggedSound());
        }
    }

    /** Drop state for a player that left, without touching them. */
    void forget(UUID uuid) {
        tags.remove(uuid);
    }

    public boolean isTagged(Player player) {
        Tag tag = tags.get(player.getUniqueId());
        return tag != null && tag.expiresAt > System.currentTimeMillis();
    }

    /** Remaining combat time in milliseconds, 0 if not tagged. */
    public long remainingMillis(Player player) {
        Tag tag = tags.get(player.getUniqueId());
        if (tag == null) return 0;
        return Math.max(0, tag.expiresAt - System.currentTimeMillis());
    }

    public UUID lastAttacker(Player player) {
        Tag tag = tags.get(player.getUniqueId());
        return tag == null ? null : tag.lastAttacker;
    }

    public static String seconds(long millis) {
        return String.valueOf((millis + 999) / 1000);
    }

    private void applyRestrictions(Player player) {
        Settings settings = plugin.settings();
        if (settings.disableElytra() && player.isGliding()) {
            player.setGliding(false);
        }
        GameMode mode = player.getGameMode();
        if (settings.disableFlight() && mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR
                && (player.isFlying() || player.getAllowFlight())) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Tag>> it = tags.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Tag> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            Tag tag = entry.getValue();
            if (tag.expiresAt <= now) {
                it.remove();
                hideDisplay(player, tag);
                Settings settings = plugin.settings();
                send(player, settings.message("untagged"));
                if (settings.untaggedSound() != null) player.playSound(settings.untaggedSound());
            } else {
                updateDisplay(player, tag);
            }
        }
    }

    private void updateDisplay(Player player, Tag tag) {
        Settings settings = plugin.settings();
        long remaining = Math.max(0, tag.expiresAt - System.currentTimeMillis());
        String time = seconds(remaining);

        if (settings.actionBar()) {
            player.sendActionBar(settings.raw("action-bar", "time", time));
        }
        if (settings.bossBar()) {
            float progress = Math.min(1f, Math.max(0f, (float) remaining / settings.combatDurationMillis()));
            Component name = settings.raw("boss-bar", "time", time);
            if (tag.bossBar == null) {
                tag.bossBar = BossBar.bossBar(name, progress, settings.bossBarColor(), settings.bossBarOverlay());
                player.showBossBar(tag.bossBar);
            } else {
                tag.bossBar.name(name);
                tag.bossBar.progress(progress);
                tag.bossBar.color(settings.bossBarColor());
                tag.bossBar.overlay(settings.bossBarOverlay());
            }
        } else if (tag.bossBar != null) {
            player.hideBossBar(tag.bossBar);
            tag.bossBar = null;
        }
    }

    private void hideDisplay(Player player, Tag tag) {
        if (tag == null) return;
        if (tag.bossBar != null) {
            player.hideBossBar(tag.bossBar);
            tag.bossBar = null;
        }
        if (plugin.settings().actionBar()) {
            player.sendActionBar(Component.empty());
        }
    }

    static void send(Player player, Component message) {
        if (message != null) player.sendMessage(message);
    }
}
