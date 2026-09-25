package top.cheesesmp.duelcore.config;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import top.cheesesmp.duelcore.rating.Tier;
import top.cheesesmp.duelcore.rating.TierLadder;
import top.cheesesmp.duelcore.rating.TierService;

/**
 * Loads config.yml, messages.yml, tiers.yml and gui.yml. Missing keys are filled from the bundled defaults and
 * written back (comments are kept), so upgrading the plugin never leaves a config without new options.
 */
public final class ConfigManager {

    private final JavaPlugin plugin;
    private MainConfig main;
    private Messages messages;
    private TierService tiers;
    private GuiConfig gui;
    private top.cheesesmp.duelcore.chat.ChatFilter chatFilter;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        YamlConfiguration config = loadWithDefaults("config.yml");
        YamlConfiguration msg = loadWithDefaults("messages.yml");
        YamlConfiguration tierYml = loadWithDefaults("tiers.yml");
        YamlConfiguration guiYml = loadWithDefaults("gui.yml");
        this.main = new MainConfig(config);
        this.messages = new Messages(msg, plugin.getLogger());
        this.tiers = parseTiers(tierYml);
        this.gui = new GuiConfig(guiYml);
        this.chatFilter = new top.cheesesmp.duelcore.chat.ChatFilter(loadWithDefaults("chat-filter.yml"), plugin.getLogger());
    }

    public top.cheesesmp.duelcore.chat.ChatFilter chatFilter() {
        return chatFilter;
    }

    private YamlConfiguration loadWithDefaults(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) plugin.saveResource(name, false);
        YamlConfiguration yml = new YamlConfiguration();
        try {
            yml.load(file);
        } catch (Exception e) {
            plugin.getLogger().severe(name + " is invalid, using defaults: " + e.getMessage());
            yml = new YamlConfiguration();
        }
        try (InputStream in = plugin.getResource(name)) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                boolean changed = name.equals("config.yml") && upgrade(yml, plugin.getLogger());
                if (name.equals("messages.yml")) changed |= retireDefaults(yml, defaults, RETIRED_MESSAGES);
                for (String key : defaults.getKeys(true)) {
                    if (defaults.isConfigurationSection(key)) continue;
                    if (!yml.contains(key, true) && !isUserMap(name, key)) {
                        yml.set(key, defaults.get(key));
                        yml.setComments(key, defaults.getComments(key));
                        changed = true;
                    }
                }
                if (changed && file.exists()) yml.save(file);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not merge defaults into " + name + ": " + e.getMessage());
        }
        return yml;
    }

    /** The config-version this build writes; {@link #upgrade} brings older config.yml files up to it. */
    static final int CONFIG_VERSION = 4;

    /** The {@code queue.music.tracks} default up to config-version 2 (replaced by version 3 when unchanged). */
    static final List<String> OLD_MUSIC_TRACKS = List.of(
            "music_disc.pigstep 148", "music_disc.otherside 195", "music_disc.relic 218", "music_disc.creator 176",
            "music_disc.creator_music_box 73", "music_disc.precipice 299", "music_disc.tears 175",
            "music_disc.lava_chicken 134", "music_disc.cat 185", "music_disc.blocks 345", "music_disc.chirp 185",
            "music_disc.far 174", "music_disc.mall 197", "music_disc.mellohi 96", "music_disc.stal 150",
            "music_disc.strad 188", "music_disc.ward 251", "music_disc.wait 238", "music_disc.5 178",
            "music_disc.13 178", "music_disc.11 71");

    /** Switches that were true by default before config-version 4 and are off since (the classic start countdown). */
    static final List<String> CLASSIC_COUNTDOWN = List.of(
            "animations.countdown-pop", "animations.fight-sweep", "animations.match-point");

    /** The {@code queue.music.tracks} default since config-version 3 (same as the bundled config.yml). */
    static final List<String> MUSIC_TRACKS = List.of(
            "music_disc.cat 185", "music_disc.blocks 345", "music_disc.chirp 185", "music_disc.mellohi 96",
            "music_disc.stal 150", "music_disc.strad 188", "music_disc.pigstep 148", "music_disc.otherside 195",
            "music_disc.creator 176", "music_disc.tears 175 2x", "music_disc.lava_chicken 134");

    /**
     * Upgrades an existing config.yml whose defaults changed (missing keys are merged anyway). Version 2: the queue
     * menu queues several kits at once and has no unranked queue, so the old defaults {@code queue.allow-multiple:
     * false} and {@code queue.unranked: true} are switched. Version 3: the old default {@code queue.music.tracks}
     * list becomes the new, shorter one (tears at 2x). Version 4: the classic start countdown is back, so the
     * {@code animations.countdown-pop}, {@code fight-sweep} and {@code match-point} switches (true by default before)
     * are turned off. Values changed by hand are left alone. Returns true when something changed.
     */
    static boolean upgrade(YamlConfiguration yml, java.util.logging.Logger log) {
        if (!yml.contains("config-version", true)) return false; // empty or broken file: defaults are merged in
        int version = yml.getInt("config-version", 1);
        if (version >= CONFIG_VERSION) return false;
        if (version < 2) {
            if (yml.isBoolean("queue.allow-multiple") && !yml.getBoolean("queue.allow-multiple")) {
                yml.set("queue.allow-multiple", true);
                log.info("config.yml: queue.allow-multiple is now true (the queue menu's Queue All)");
            }
            if (yml.isBoolean("queue.unranked") && yml.getBoolean("queue.unranked")) {
                yml.set("queue.unranked", false);
                log.info("config.yml: queue.unranked is now false (only kits with ranked: false use it)");
            }
        }
        if (version < 3 && yml.isList("queue.music.tracks")
                && normalized(yml.getStringList("queue.music.tracks")).equals(OLD_MUSIC_TRACKS)) {
            yml.set("queue.music.tracks", MUSIC_TRACKS);
            log.info("config.yml: queue.music.tracks is now the new default disc list (" + MUSIC_TRACKS.size() + " discs)");
        }
        if (version < 4) {
            for (String key : CLASSIC_COUNTDOWN) {
                if (yml.isBoolean(key) && yml.getBoolean(key)) {
                    yml.set(key, false);
                    log.info("config.yml: " + key + " is now false (the classic start countdown)");
                }
            }
        }
        yml.set("config-version", CONFIG_VERSION);
        return true;
    }

    /**
     * messages.yml texts whose bundled default changed, as key → old default. A file still holding the old default
     * gets the new one; a text edited by hand is kept.
     */
    static final Map<String, String> RETIRED_MESSAGES = Map.of(
            "kit-editor.picker.category", "<icon> <text><name></text>");

    /** Replaces every value that still equals its retired default with the current default; true when any did. */
    static boolean retireDefaults(YamlConfiguration yml, YamlConfiguration defaults, Map<String, String> retired) {
        boolean changed = false;
        for (Map.Entry<String, String> e : retired.entrySet()) {
            if (defaults.isString(e.getKey()) && e.getValue().equals(yml.getString(e.getKey()))) {
                yml.set(e.getKey(), defaults.getString(e.getKey()));
                changed = true;
            }
        }
        return changed;
    }

    private static List<String> normalized(List<String> list) {
        return list.stream().map(s -> s.trim().replaceAll("\\s+", " ")).toList();
    }

    /** Sections the user owns completely (don't re-add keys they deleted on purpose). */
    private static boolean isUserMap(String file, String key) {
        return file.equals("tiers.yml") && key.startsWith("kit-thresholds.") && !key.startsWith("kit-thresholds.default.");
    }

    private static TierService parseTiers(YamlConfiguration y) {
        Map<Tier, Double> defaults = doubles(y.getConfigurationSection("kit-thresholds.default"));
        Map<String, Map<Tier, Double>> perKit = new HashMap<>();
        ConfigurationSection kits = y.getConfigurationSection("kit-thresholds");
        if (kits != null) {
            for (String kit : kits.getKeys(false)) {
                if (kit.equals("default")) continue;
                perKit.put(kit.toLowerCase(java.util.Locale.ROOT), doubles(kits.getConfigurationSection(kit)));
            }
        }
        TierLadder ladder = new TierLadder(y.getInt("placement-matches", 5), defaults, perKit);
        Map<Tier, String> formats = new EnumMap<>(Tier.class);
        ConfigurationSection f = y.getConfigurationSection("format");
        if (f != null) {
            for (String key : f.getKeys(false)) {
                Tier t = Tier.parse(key);
                if (t != null) formats.put(t, f.getString(key));
            }
        }
        String unrankedFormat = f == null ? "<gray><tier>" : f.getString("unranked", "<gray><tier>");
        return new TierService(ladder, y.getString("unranked-label", "???"), formats, unrankedFormat);
    }

    private static Map<Tier, Double> doubles(ConfigurationSection s) {
        Map<Tier, Double> map = new EnumMap<>(Tier.class);
        if (s == null) return map;
        for (String key : s.getKeys(false)) {
            Tier t = Tier.parse(key);
            if (t != null) map.put(t, s.getDouble(key));
        }
        return map;
    }

    private static Map<Tier, Integer> ints(ConfigurationSection s) {
        Map<Tier, Integer> map = new EnumMap<>(Tier.class);
        if (s == null) return map;
        for (String key : s.getKeys(false)) {
            Tier t = Tier.parse(key);
            if (t != null) map.put(t, s.getInt(key));
        }
        return map;
    }

    public MainConfig main() {
        return main;
    }

    public Messages messages() {
        return messages;
    }

    public TierService tiers() {
        return tiers;
    }

    public GuiConfig gui() {
        return gui;
    }

    public List<String> regions() {
        return main.regions;
    }
}
