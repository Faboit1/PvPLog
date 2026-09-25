package top.cheesesmp.duelcore.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(2, y.getInt("config-version"));
    }

    @Test
    void emptyOrCurrentFilesAreLeftAlone() throws Exception {
        assertFalse(ConfigManager.upgrade(new YamlConfiguration(), LOG));
        YamlConfiguration current = yml("config-version: 2\nqueue:\n  unranked: true\n");
        assertFalse(ConfigManager.upgrade(current, LOG));
        assertTrue(current.getBoolean("queue.unranked"));
    }
}
