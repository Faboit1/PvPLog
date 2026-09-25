package top.cheesesmp.duelcore.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** config.yml files from before the queue menu get its new defaults once; hand-made choices stay. */
class ConfigUpgradeTest {

    private static final Logger LOG = Logger.getLogger("test");

    private static YamlConfiguration yml(String text) throws InvalidConfigurationException {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        return y;
    }

    @Test
    void oldDefaultsAreSwitched() throws Exception {
        YamlConfiguration y = yml("config-version: 1\nqueue:\n  allow-multiple: false\n  ranked: true\n  unranked: true\n");
        assertTrue(ConfigManager.upgrade(y, LOG));
        assertTrue(y.getBoolean("queue.allow-multiple"));
        assertFalse(y.getBoolean("queue.unranked"));
        assertTrue(y.getBoolean("queue.ranked"));
        assertEquals(ConfigManager.CONFIG_VERSION, y.getInt("config-version"));
        // only once
        y.set("queue.unranked", true);
        assertFalse(ConfigManager.upgrade(y, LOG));
        assertTrue(y.getBoolean("queue.unranked"));
    }

    @Test
    void valuesAlreadyLikeTheNewDefaultsOnlyBumpTheVersion() throws Exception {
        YamlConfiguration y = yml("config-version: 1\nqueue:\n  allow-multiple: true\n  unranked: false\n");
        assertTrue(ConfigManager.upgrade(y, LOG));
        assertTrue(y.getBoolean("queue.allow-multiple"));
        assertFalse(y.getBoolean("queue.unranked"));
        assertEquals(ConfigManager.CONFIG_VERSION, y.getInt("config-version"));
    }

    @Test
    void emptyOrCurrentFilesAreLeftAlone() throws Exception {
        assertFalse(ConfigManager.upgrade(new YamlConfiguration(), LOG));
        YamlConfiguration current = yml("config-version: 4\nqueue:\n  unranked: true\nanimations:\n  countdown-pop: true\n");
        assertFalse(ConfigManager.upgrade(current, LOG));
        assertTrue(current.getBoolean("queue.unranked"));
        assertTrue(current.getBoolean("animations.countdown-pop"));
    }

    private static String tracks(List<String> list) {
        StringBuilder b = new StringBuilder("queue:\n  music:\n    tracks:\n");
        for (String t : list) b.append("      - \"").append(t).append("\"\n");
        return b.toString();
    }

    @Test
    void oldDefaultMusicListIsReplaced() throws Exception {
        List<String> spaced = ConfigManager.OLD_MUSIC_TRACKS.stream().map(t -> " " + t.replace(" ", "   ") + " ").toList();
        YamlConfiguration y = yml("config-version: 2\n" + tracks(spaced));
        assertTrue(ConfigManager.upgrade(y, LOG));
        assertEquals(ConfigManager.MUSIC_TRACKS, y.getStringList("queue.music.tracks"));
        assertEquals(ConfigManager.CONFIG_VERSION, y.getInt("config-version"));
    }

    @Test
    void customMusicListIsKept() throws Exception {
        List<String> custom = List.of("music_disc.ward 251", "music_disc.11 71");
        YamlConfiguration y = yml("config-version: 2\nqueue:\n  unranked: true\n" + tracks(custom).substring("queue:\n".length()));
        assertTrue(ConfigManager.upgrade(y, LOG));
        assertEquals(custom, y.getStringList("queue.music.tracks"));
        assertTrue(y.getBoolean("queue.unranked")); // the version 2 step does not run again
        assertEquals(ConfigManager.CONFIG_VERSION, y.getInt("config-version"));
    }

    @Test
    void version3MusicListIsLeftAlone() throws Exception {
        YamlConfiguration y = yml("config-version: 3\n" + tracks(ConfigManager.OLD_MUSIC_TRACKS));
        assertTrue(ConfigManager.upgrade(y, LOG)); // only the version 4 step runs
        assertEquals(ConfigManager.OLD_MUSIC_TRACKS, y.getStringList("queue.music.tracks"));
        assertEquals(ConfigManager.CONFIG_VERSION, y.getInt("config-version"));
    }

    @Test
    void classicCountdownIsRestoredOnce() throws Exception {
        YamlConfiguration y = yml("config-version: 3\nanimations:\n  countdown-pop: true\n  fight-sweep: true\n"
            + "  match-point: false\n  round-banner: true\n");
        assertTrue(ConfigManager.upgrade(y, LOG));
        for (String key : ConfigManager.CLASSIC_COUNTDOWN) assertFalse(y.getBoolean(key, true), key);
        assertTrue(y.getBoolean("animations.round-banner")); // other animations are untouched
        assertEquals(4, y.getInt("config-version"));
        // turned back on by hand afterwards: stays on
        y.set("animations.fight-sweep", true);
        assertFalse(ConfigManager.upgrade(y, LOG));
        assertTrue(y.getBoolean("animations.fight-sweep"));
    }

    @Test
    void classicCountdownStepSkipsMissingKeys() throws Exception {
        YamlConfiguration y = yml("config-version: 3\nanimations:\n  heartbeat: true\n");
        assertTrue(ConfigManager.upgrade(y, LOG));
        for (String key : ConfigManager.CLASSIC_COUNTDOWN) assertFalse(y.contains(key), key); // defaults fill them in
    }

    @Test
    void bundledConfigMatchesTheUpgrade() throws Exception {
        try (var in = ConfigUpgradeTest.class.getResourceAsStream("/config.yml")) {
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertEquals(ConfigManager.CONFIG_VERSION, bundled.getInt("config-version"));
            assertEquals(ConfigManager.MUSIC_TRACKS, bundled.getStringList("queue.music.tracks"));
            for (String key : ConfigManager.CLASSIC_COUNTDOWN) {
                assertTrue(bundled.isBoolean(key), key);
                assertFalse(bundled.getBoolean(key), key);
            }
        }
    }

    @Test
    void retiredMessageDefaultsAreReplacedButEditsKept() throws Exception {
        java.util.Map<String, String> retired = java.util.Map.of("a.b", "old", "a.c", "old too");
        YamlConfiguration defaults = yml("a:\n  b: \"new\"\n  c: \"new too\"\n");
        YamlConfiguration y = yml("a:\n  b: \"old\"\n  c: \"my own\"\n");
        assertTrue(ConfigManager.retireDefaults(y, defaults, retired));
        assertEquals("new", y.getString("a.b"));
        assertEquals("my own", y.getString("a.c"));
        assertFalse(ConfigManager.retireDefaults(y, defaults, retired));
    }

    @Test
    void bundledMessagesDoNotHoldRetiredDefaults() throws Exception {
        YamlConfiguration bundled = new YamlConfiguration();
        try (var in = new InputStreamReader(ConfigUpgradeTest.class.getResourceAsStream("/messages.yml"), StandardCharsets.UTF_8)) {
            bundled.load(in);
        }
        for (var e : ConfigManager.RETIRED_MESSAGES.entrySet()) {
            assertTrue(bundled.isString(e.getKey()), e.getKey());
            assertFalse(e.getValue().equals(bundled.getString(e.getKey())), e.getKey());
        }
    }
}
