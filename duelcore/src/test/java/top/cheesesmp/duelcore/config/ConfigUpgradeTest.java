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
        assertEquals(3, y.getInt("config-version"));
    }

    @Test
    void emptyOrCurrentFilesAreLeftAlone() throws Exception {
        assertFalse(ConfigManager.upgrade(new YamlConfiguration(), LOG));
        YamlConfiguration current = yml("config-version: 3\nqueue:\n  unranked: true\n");
        assertFalse(ConfigManager.upgrade(current, LOG));
        assertTrue(current.getBoolean("queue.unranked"));
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
        assertEquals(3, y.getInt("config-version"));
    }

    @Test
    void customMusicListIsKept() throws Exception {
        List<String> custom = List.of("music_disc.ward 251", "music_disc.11 71");
        YamlConfiguration y = yml("config-version: 2\nqueue:\n  unranked: true\n" + tracks(custom).substring("queue:\n".length()));
        assertTrue(ConfigManager.upgrade(y, LOG));
        assertEquals(custom, y.getStringList("queue.music.tracks"));
        assertTrue(y.getBoolean("queue.unranked")); // the version 2 step does not run again
        assertEquals(3, y.getInt("config-version"));
    }

    @Test
    void version3MusicListIsLeftAlone() throws Exception {
        YamlConfiguration y = yml("config-version: 3\n" + tracks(ConfigManager.OLD_MUSIC_TRACKS));
        assertFalse(ConfigManager.upgrade(y, LOG));
        assertEquals(ConfigManager.OLD_MUSIC_TRACKS, y.getStringList("queue.music.tracks"));
    }

    @Test
    void bundledConfigMatchesTheUpgrade() throws Exception {
        try (var in = ConfigUpgradeTest.class.getResourceAsStream("/config.yml")) {
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertEquals(ConfigManager.CONFIG_VERSION, bundled.getInt("config-version"));
            assertEquals(ConfigManager.MUSIC_TRACKS, bundled.getStringList("queue.music.tracks"));
        }
    }
}
