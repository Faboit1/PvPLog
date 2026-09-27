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
import top.cheesesmp.duelcore.geo.Countries;
import top.cheesesmp.duelcore.rating.Tier;
import top.cheesesmp.duelcore.rating.TierLadder;
import top.cheesesmp.duelcore.rating.TierService;

/**
 * Loads config.yml, messages.yml, tiers.yml, gui.yml and countries.yml. Missing keys are filled from the bundled
 * defaults and written back (comments are kept), so upgrading the plugin never leaves a config without new options.
 */
public final class ConfigManager {

    private final JavaPlugin plugin;
    private MainConfig main;
    private Messages messages;
    private TierService tiers;
    private GuiConfig gui;
    private top.cheesesmp.duelcore.chat.ChatFilter chatFilter;
    private volatile Countries countries = Countries.EMPTY;

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
        this.countries = loadCountries();
    }

    public top.cheesesmp.duelcore.chat.ChatFilter chatFilter() {
        return chatFilter;
    }

    /** countries.yml: names, regions and flags by country code (see {@link Countries}). */
    public Countries countries() {
        return countries;
    }

    /**
     * Reads countries.yml from the plugin folder (written there on first start, so owners can edit it). Countries
     * missing from it come from the bundled file; a file that doesn't parse is left alone and the bundled list is used
     * until it is fixed. Entries that can't be used are logged.
     */
    private Countries loadCountries() {
        String name = "countries.yml";
        Countries bundled = Countries.EMPTY;
        try (InputStream in = plugin.getResource(name)) {
            if (in != null) bundled = Countries.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("The bundled " + name + " could not be read: " + e.getMessage());
        }
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) plugin.saveResource(name, false);
        try (java.io.Reader in = java.nio.file.Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Countries own = Countries.parse(in);
            for (String problem : own.problems()) plugin.getLogger().warning(name + ": " + problem);
            return own.withFallback(bundled);
        } catch (Exception e) {
            plugin.getLogger().severe(name + " is invalid, using the bundled list until it is fixed (the file was not"
                + " changed): " + e.getMessage());
            return bundled;
        }
    }

    private YamlConfiguration loadWithDefaults(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) plugin.saveResource(name, false);
        YamlConfiguration yml = new YamlConfiguration();
        // a file that doesn't parse is never written back: saving the merged defaults would wipe the admin's settings
        boolean broken = false;
        try {
            yml.load(file);
        } catch (Exception e) {
            broken = true;
            plugin.getLogger().severe(name + " is invalid, using the bundled defaults until it is fixed (the file was not"
                + " changed): " + e.getMessage());
            yml = new YamlConfiguration();
        }
        try (InputStream in = plugin.getResource(name)) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                boolean changed = name.equals("config.yml") && upgrade(yml, plugin.getLogger());
                if (name.equals("messages.yml")) changed |= retireDefaults(yml, defaults, RETIRED_MESSAGES);
                if (name.equals("gui.yml")) changed |= retireDefaults(yml, defaults, RETIRED_GUI);
                for (String key : defaults.getKeys(true)) {
                    if (defaults.isConfigurationSection(key)) continue;
                    if (!yml.contains(key, true) && !isUserMap(name, key)) {
                        yml.set(key, defaults.get(key));
                        yml.setComments(key, defaults.getComments(key));
                        changed = true;
                    }
                }
                if (changed && !broken && file.exists()) yml.save(file);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not merge defaults into " + name + ": " + e.getMessage());
        }
        return yml;
    }

    /** The config-version this build writes; {@link #upgrade} brings older config.yml files up to it. */
    static final int CONFIG_VERSION = 7;

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

    /**
     * A number whose default changed: a file still holding {@code old} gets {@code now}, written as it is (750 stays a
     * whole number, 0.75 doesn't).
     */
    record NumberDefault(String key, double old, Number now) {
    }

    /** Rating defaults changed in config-version 5 (new players start at 750, nobody drops below 50). */
    static final List<NumberDefault> RATING_DEFAULTS = List.of(
            new NumberDefault("rating.default", 1000, 750),
            new NumberDefault("rating.floor", 100, 50));

    /** Matchmaking defaults changed in config-version 7: a closer ping counts a bit more. */
    static final List<NumberDefault> PING_DEFAULTS = List.of(
            new NumberDefault("matchmaking.ping.penalty-per-ms", 0.5, 0.75));

    /** The {@code animations.respawn-styles} default up to config-version 5. */
    static final List<String> OLD_RESPAWN_STYLES = List.of("throw", "look-down", "spin");
    /** The {@code animations.respawn-styles} default since config-version 6 (same as the bundled config.yml). */
    static final List<String> RESPAWN_STYLES = List.of("throw", "float", "orbit", "swoop", "look-down", "spin");

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
     * are turned off. Version 5: {@code rating.default} 1000 → 750 and {@code rating.floor} 100 → 50 (existing player
     * ratings are not touched). Version 6: the old default {@code animations.respawn-styles} list gets the new
     * float, orbit and swoop animations. Version 7: {@code matchmaking.ping.penalty-per-ms} 0.5 → 0.75 (a closer ping
     * counts a bit more). Values changed by hand are left alone. Returns true when something changed.
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
        if (version < 5) replaceNumbers(yml, RATING_DEFAULTS, log);
        if (version < 6 && yml.isList("animations.respawn-styles")
                && normalized(yml.getStringList("animations.respawn-styles")).equals(OLD_RESPAWN_STYLES)) {
            yml.set("animations.respawn-styles", RESPAWN_STYLES);
            log.info("config.yml: animations.respawn-styles now also has the float, orbit and swoop animations");
        }
        if (version < 7) replaceNumbers(yml, PING_DEFAULTS, log);
        yml.set("config-version", CONFIG_VERSION);
        return true;
    }

    /** Every number that still holds its old default gets the new one (missing keys are filled in by the merge). */
    private static void replaceNumbers(YamlConfiguration yml, List<NumberDefault> defaults, java.util.logging.Logger log) {
        for (NumberDefault d : defaults) {
            if ((yml.isInt(d.key()) || yml.isDouble(d.key())) && yml.getDouble(d.key()) == d.old()) {
                yml.set(d.key(), d.now());
                log.info("config.yml: " + d.key() + " is now " + d.now() + " (new default)");
            }
        }
    }

    /**
     * messages.yml texts whose bundled default changed, as key → old default. A file still holding the old default
     * gets the new one; a text edited by hand is kept. (The texts with {@code <flag>} got it when country flags came.)
     */
    static final Map<String, String> RETIRED_MESSAGES = Map.ofEntries(
            Map.entry("kit-editor.picker.category", "<icon> <text><name></text>"),
            Map.entry("match.found-subtitle", "<kit_icon> <text><opponent></text> <muted>· <tier> · <mode></muted>"),
            Map.entry("results.subtitle", "<text><you>–<opp></text> <muted>vs <opponent></muted>"),
            Map.entry("duel.received",
                "<kit_icon> <text><player></text> <muted>challenged you to</muted> <text><kit></text>  <accept> <deny>"),
            Map.entry("duel.sent", "<muted>Challenge sent to <text><player></text> · <kit_icon> <kit>. It expires in 60s.</muted>"),
            Map.entry("dialog.profile.header",
                "<head> <text><player></text>  <tier> <muted>· <elo> Elo · <region> <country></muted>"),
            Map.entry("dialog.settings.country-value", "<text><country></text>"),
            Map.entry("dialog.settings.country-change-hover", "<muted>Type your country's two letters</muted>"),
            Map.entry("dialog.settings.country-body",
                "<muted>Two letters (DE, US, …), shown on your profile. Leave it empty to remove it.</muted>"),
            Map.entry("dialog.settings.country-label", "Country (2 letters)"),
            Map.entry("dialog.settings.items.country.hover", "Shown on your profile."),
            // leaderboard lines without the flag
            Map.entry("dialog.leaderboard.line-overall",
                "<muted><rank>.</muted> <head> <text><player></text>  <tier> <muted>· <elo> Elo · <wins>W</muted>"),
            Map.entry("dialog.leaderboard.line-kit",
                "<muted><rank>.</muted> <head> <text><player></text>  <tier> <muted>· <value> · <wins>W <losses>L</muted>"),
            // settings texts rewritten for the newer rows (chat lines, menu sounds, the country board, Prefer my country)
            Map.entry("dialog.settings.section-hover.gameplay",
                "<text>In your matches: spectators, the action bar and the results screen.</text>"),
            Map.entry("dialog.settings.section-body.queue",
                "<muted>Region and max ping help the matchmaker find opponents with a similar connection.</muted>"),
            Map.entry("dialog.settings.items.sounds.hover",
                "Every DuelCore sound effect, match sounds included. The music while searching has its own switch."));

    /**
     * gui.yml values whose bundled default changed, as key → old default (a text or a list of lines), handled like
     * {@link #RETIRED_MESSAGES}: the name formats and the match / spectate sidebars got {@code <flag>}, the match
     * health line the HUD's heart sprites.
     */
    static final Map<String, Object> RETIRED_GUI = Map.of(
            "match-health.format", "<#ff5555>❤</#ff5555> <hp_color><hearts></hp_color><absorption>",
            "match-health.absorption", " <#ffcc33>+<hearts></#ffcc33>",
            "tags.chat", "<tier> <text><name></text><muted>:</muted> <message>",
            "tags.tab", "<tier> <text><name></text>",
            "tags.nametag-prefix", "<tier> ",
            "sidebar.match", List.of("", "<kit_icon> <text><kit></text> <muted><mode></muted>", "",
                "<text>You</text>  <accent><you_score></accent>", "<text><opponent></text>  <accent><opp_score></accent>",
                "<muted>First to <first_to> · round <round></muted>", "", "<muted>Time</muted>  <text><time></text>",
                "<muted>Your ping</muted>  <text><ping>ms</text>", "<muted><opponent></muted>  <text><opp_ping>ms</text>", "",
                "<muted>pvp.cheesesmp.top</muted>"),
            "sidebar.spectate", List.of("", "<kit_icon> <text><kit></text> <muted>spectating</muted>", "",
                "<text><red_name></text>  <accent><red_score></accent>", "<text><blue_name></text>  <accent><blue_score></accent>",
                "<muted>Round <round></muted>", "", "<muted>Time</muted>  <text><time></text>", "",
                "<muted>pvp.cheesesmp.top</muted>"));

    /**
     * Replaces every value that still equals its retired default (a text, or a list of lines) with the current
     * default; true when any did.
     */
    static boolean retireDefaults(YamlConfiguration yml, YamlConfiguration defaults, Map<String, ?> retired) {
        boolean changed = false;
        for (Map.Entry<String, ?> e : retired.entrySet()) {
            String key = e.getKey();
            if (e.getValue() instanceof List<?> old) {
                if (defaults.isList(key) && yml.isList(key) && yml.getStringList(key).equals(old)) {
                    yml.set(key, defaults.getStringList(key));
                    changed = true;
                }
            } else if (defaults.isString(key) && String.valueOf(e.getValue()).equals(yml.getString(key))) {
                yml.set(key, defaults.getString(key));
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
