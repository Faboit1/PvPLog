package com.faboit.pvplog;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Immutable snapshot of config.yml. Rebuilt on reload. */
public final class Settings {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PvPLogPlugin plugin;
    private final FileConfiguration config;

    private final long combatDurationMillis;
    private final Set<String> disabledWorlds;

    private final boolean tagMelee, tagProjectiles, tagPotions, tagExplosions, tagPets, tagFishingRod, tagAttacker;

    private final boolean killOnLogout, punishOnKick, creditLastAttacker;
    private final java.util.List<String> combatLogCommands;
    private final boolean untagKillerOnKill;

    private final boolean disableElytra, disableFlight;
    private final int enderPearlCooldown, windChargeCooldown;
    private final Set<PlayerTeleportEvent.TeleportCause> blockedTeleportCauses;

    private final boolean whitelistMode;
    private final Set<String> commandList;

    private final boolean actionBar, bossBar;
    private final BossBar.Color bossBarColor;
    private final BossBar.Overlay bossBarOverlay;
    private final Sound taggedSound, untaggedSound;

    private final String prefix;

    Settings(PvPLogPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();

        combatDurationMillis = Math.max(1, config.getLong("combat-duration", 20)) * 1000L;
        disabledWorlds = lower(config.getStringList("disabled-worlds"));

        tagMelee = config.getBoolean("tagging.melee", true);
        tagProjectiles = config.getBoolean("tagging.projectiles", true);
        tagPotions = config.getBoolean("tagging.splash-potions", true);
        tagExplosions = config.getBoolean("tagging.explosions", true);
        tagPets = config.getBoolean("tagging.pets", true);
        tagFishingRod = config.getBoolean("tagging.fishing-rod", false);
        tagAttacker = config.getBoolean("tagging.tag-attacker", true);

        killOnLogout = config.getBoolean("combat-log.kill-on-logout", true);
        punishOnKick = config.getBoolean("combat-log.punish-on-kick", false);
        creditLastAttacker = config.getBoolean("combat-log.credit-last-attacker", true);
        combatLogCommands = config.getStringList("combat-log.console-commands");
        untagKillerOnKill = config.getBoolean("on-death.untag-killer", false);

        disableElytra = config.getBoolean("restrictions.disable-elytra", true);
        disableFlight = config.getBoolean("restrictions.disable-flight", true);
        enderPearlCooldown = config.getInt("restrictions.ender-pearl-cooldown", -1);
        windChargeCooldown = config.getInt("restrictions.wind-charge-cooldown", -1);
        blockedTeleportCauses = EnumSet.noneOf(PlayerTeleportEvent.TeleportCause.class);
        for (String cause : config.getStringList("restrictions.blocked-teleport-causes")) {
            try {
                blockedTeleportCauses.add(PlayerTeleportEvent.TeleportCause.valueOf(cause.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Unknown teleport cause in config: " + cause);
            }
        }

        whitelistMode = "WHITELIST".equalsIgnoreCase(config.getString("commands.mode", "BLACKLIST"));
        commandList = lower(config.getStringList("commands.list"));

        actionBar = config.getBoolean("display.action-bar", true);
        bossBar = config.getBoolean("display.boss-bar", true);
        bossBarColor = parseEnum(BossBar.Color.class, config.getString("display.boss-bar-color"), BossBar.Color.RED);
        bossBarOverlay = parseEnum(BossBar.Overlay.class, config.getString("display.boss-bar-overlay"), BossBar.Overlay.PROGRESS);
        taggedSound = sound(config.getString("display.sounds.tagged", ""));
        untaggedSound = sound(config.getString("display.sounds.untagged", ""));

        prefix = config.getString("messages.prefix", "");
    }

    private static Set<String> lower(java.util.List<String> list) {
        Set<String> set = new HashSet<>();
        for (String s : list) {
            set.add(s.toLowerCase(Locale.ROOT));
        }
        return set;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, E def) {
        if (value == null) return def;
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid value '" + value + "' for " + type.getSimpleName() + ", using " + def);
            return def;
        }
    }

    @SuppressWarnings("PatternValidation")
    private Sound sound(String key) {
        if (key == null || key.isBlank()) return null;
        try {
            return Sound.sound(Key.key(key.toLowerCase(Locale.ROOT)), Sound.Source.PLAYER, 1f, 1f);
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid sound: " + key);
            return null;
        }
    }

    /** Raw message (no prefix) with {placeholders} replaced. Placeholder values are inserted as plain text. */
    public Component raw(String path, String... placeholders) {
        String text = config.getString("messages." + path, "");
        TagResolver.Builder resolvers = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String name = placeholders[i];
            text = text.replace("{" + name + "}", "<" + name + ">");
            resolvers.resolver(Placeholder.unparsed(name, placeholders[i + 1]));
        }
        return MM.deserialize(text, resolvers.build());
    }

    /** Chat message with prefix; returns null if the message is configured empty. */
    public Component message(String path, String... placeholders) {
        String text = config.getString("messages." + path, "");
        if (text.isEmpty()) return null;
        return MM.deserialize(prefix).append(raw(path, placeholders));
    }

    public long combatDurationMillis() { return combatDurationMillis; }
    public boolean isWorldDisabled(String world) { return disabledWorlds.contains(world.toLowerCase(Locale.ROOT)); }
    public boolean tagMelee() { return tagMelee; }
    public boolean tagProjectiles() { return tagProjectiles; }
    public boolean tagPotions() { return tagPotions; }
    public boolean tagExplosions() { return tagExplosions; }
    public boolean tagPets() { return tagPets; }
    public boolean tagFishingRod() { return tagFishingRod; }
    public boolean tagAttacker() { return tagAttacker; }
    public boolean killOnLogout() { return killOnLogout; }
    public boolean punishOnKick() { return punishOnKick; }
    public boolean creditLastAttacker() { return creditLastAttacker; }
    public java.util.List<String> combatLogCommands() { return combatLogCommands; }
    public boolean untagKillerOnKill() { return untagKillerOnKill; }
    public boolean disableElytra() { return disableElytra; }
    public boolean disableFlight() { return disableFlight; }
    public int enderPearlCooldown() { return enderPearlCooldown; }
    public int windChargeCooldown() { return windChargeCooldown; }
    public boolean isTeleportBlocked(PlayerTeleportEvent.TeleportCause cause) { return blockedTeleportCauses.contains(cause); }
    public boolean actionBar() { return actionBar; }
    public boolean bossBar() { return bossBar; }
    public BossBar.Color bossBarColor() { return bossBarColor; }
    public BossBar.Overlay bossBarOverlay() { return bossBarOverlay; }
    public Sound taggedSound() { return taggedSound; }
    public Sound untaggedSound() { return untaggedSound; }

    /** @param label command label without slash or namespace, lower case */
    public boolean isCommandBlocked(String label) {
        boolean listed = commandList.contains(label);
        return whitelistMode != listed;
    }
}
