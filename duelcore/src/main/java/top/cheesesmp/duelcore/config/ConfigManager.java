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
    static final int CONFIG_VERSION = 2;

    /**
     * Upgrades an existing config.yml whose defaults changed (missing keys are merged anyway). Version 2: the queue
     * menu queues several kits at once and has no unranked queue, so the old defaults {@code queue.allow-multiple:
     * false} and {@code queue.unranked: true} are switched (values changed by hand are left alone). Returns true when
     * something changed.
     */
    static boolean upgrade(YamlConfiguration yml, java.util.logging.Logger log) {
        if (!yml.contains("config-version", true)) return false; // empty or broken file: defaults are merged in
        int version = yml.getInt("config-version", 1);
        if (version >= CONFIG_VERSION) return false;
        if (yml.isBoolean("queue.allow-multiple") && !yml.getBoolean("queue.allow-multiple")) {
            yml.set("queue.allow-multiple", true);
            log.info("config.yml: queue.allow-multiple is now true (the queue menu's Queue All)");
        }
        if (yml.isBoolean("queue.unranked") && yml.getBoolean("queue.unranked")) {
            yml.set("queue.unranked", false);
            log.info("config.yml: queue.unranked is now false (only kits with ranked: false use it)");
        }
        yml.set("config-version", CONFIG_VERSION);
        return true;
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
