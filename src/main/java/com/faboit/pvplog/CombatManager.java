package com.faboit.pvplog;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks who is in combat, drives the countdown display and expiry.
 *
 * <p>Folia-safe: every tagged player gets their own repeating task on their entity
 * scheduler, and anything that touches a player is run on the thread that owns them.
 * On Paper the same schedulers simply run on the main thread.
 */
public final class CombatManager {

    private final PvPLogPlugin plugin;
    private final Map<UUID, Tag> tags = new ConcurrentHashMap<>();

    private static final class Tag {
        volatile long expiresAt;
        volatile UUID lastAttacker;
        ScheduledTask task;
    }

    /** Set on players who turned the combat bar off (/showcombatbar false); absent = shown. */
    private final NamespacedKey hideBarKey;

    CombatManager(PvPLogPlugin plugin) {
        this.plugin = plugin;
        this.hideBarKey = new NamespacedKey(plugin, "hide_combat_bar");
    }

    /** Whether this player sees the combat bar (action bar countdown). Call on the player's thread. */
    public boolean showsBar(Player player) {
        return !player.getPersistentDataContainer().has(hideBarKey, PersistentDataType.BYTE);
    }

    /** Show or hide the combat bar for this player (stored on the player, survives restarts). */
    public void showBar(Player player, boolean show) {
        if (show) {
            player.getPersistentDataContainer().remove(hideBarKey);
        } else {
            player.getPersistentDataContainer().set(hideBarKey, PersistentDataType.BYTE, (byte) 1);
            if (isTagged(player)) player.sendActionBar(Component.empty());
        }
        Tag tag = tags.get(player.getUniqueId());
        if (show && tag != null) updateDisplay(player, tag);
    }

    void shutdown() {
        for (Map.Entry<UUID, Tag> entry : tags.entrySet()) {
            Tag tag = entry.getValue();
            if (tag.task != null) tag.task.cancel();
        }
        tags.clear();
    }

    /** Run on the thread that owns this player (immediately if we already are on it). */
    void runFor(Player player, Runnable action) {
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            action.run();
        } else {
            player.getScheduler().run(plugin, task -> action.run(), null);
        }
    }

    /** Whether this player can be tagged at all right now. Call on the player's thread. */
    public boolean canTag(Player player) {
        if (player.hasPermission("pvplog.bypass")) return false;
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) return false;
        return !plugin.settings().isWorldDisabled(player.getWorld().getName());
    }

    /**
     * Put a player in combat (or refresh their timer). Safe from any thread.
     *
     * @param attacker the opponent, may be null
     * @param force    skip the bypass / gamemode / world checks
     */
    public void tag(Player player, Player attacker, boolean force) {
        UUID attackerId = attacker == null ? null : attacker.getUniqueId();
        runFor(player, () -> {
            if (!player.isOnline() || (!force && !canTag(player))) return;
            tagNow(player, attackerId);
        });
    }

    private void tagNow(Player player, UUID attackerId) {
        Settings settings = plugin.settings();
        Tag tag = tags.get(player.getUniqueId());
        boolean fresh = tag == null;
        if (fresh) {
            tag = new Tag();
            tags.put(player.getUniqueId(), tag);
        }
        tag.expiresAt = System.currentTimeMillis() + settings.combatDurationMillis();
        if (attackerId != null) tag.lastAttacker = attackerId;

        if (fresh) {
            UUID uuid = player.getUniqueId();
            tag.task = player.getScheduler().runAtFixedRate(plugin, t -> tick(player, t),
                    () -> tags.remove(uuid), 5L, 5L);
            CombatManager.send(player, settings.message("tagged"));
            if (settings.taggedSound() != null) player.playSound(settings.taggedSound());
            applyRestrictions(player);
        }
        updateDisplay(player, tag);
    }

    /** Remove a player from combat. Safe from any thread. */
    public void untag(Player player, boolean notify) {
        Tag tag = tags.remove(player.getUniqueId());
        if (tag == null) return;
        runFor(player, () -> {
            if (tag.task != null) tag.task.cancel();
            hideDisplay(player, tag);
            if (notify) notifyUntagged(player);
        });
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
        GameMode mode = player.getGameMode();
        if (settings.disableFlight() && mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR
                && (player.isFlying() || player.getAllowFlight())) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    private void tick(Player player, ScheduledTask task) {
        Tag tag = tags.get(player.getUniqueId());
        if (tag == null || tag.task != task) {
            task.cancel();
            return;
        }
        if (tag.expiresAt <= System.currentTimeMillis()) {
            tags.remove(player.getUniqueId(), tag);
            task.cancel();
            hideDisplay(player, tag);
            notifyUntagged(player);
        } else {
            updateDisplay(player, tag);
        }
    }

    private void notifyUntagged(Player player) {
        Settings settings = plugin.settings();
        send(player, settings.message("untagged"));
        if (settings.untaggedSound() != null) player.playSound(settings.untaggedSound());
    }

    private void updateDisplay(Player player, Tag tag) {
        Settings settings = plugin.settings();
        if (!settings.actionBar() || !showsBar(player)) return;
        long remaining = Math.max(0, tag.expiresAt - System.currentTimeMillis());
        player.sendActionBar(settings.raw("action-bar", "time", seconds(remaining)));
    }

    private void hideDisplay(Player player, Tag tag) {
        if (plugin.settings().actionBar() && showsBar(player)) {
            player.sendActionBar(Component.empty());
        }
    }

    static void send(Player player, Component message) {
        if (message != null) player.sendMessage(message);
    }
}
