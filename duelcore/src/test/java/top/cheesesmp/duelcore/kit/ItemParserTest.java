package top.cheesesmp.duelcore.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ItemParserTest {

    /** Regression: nested maps in a slot's values arrive as sections, which silently dropped every enchantment. */
    @Test
    void nestedEnchantSectionsAreReadAsMaps() throws InvalidConfigurationException {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("items:\n  0: { item: diamond_sword, enchants: { sharpness: 5, unbreaking: 3 } }\n");
        ConfigurationSection slot = y.getConfigurationSection("items.0");
        assertNotNull(slot);
        Map<?, ?> spec = ItemParser.asMap(slot);
        assertNotNull(spec);
        Map<?, ?> enchants = ItemParser.asMap(spec.get("enchants"));
        assertNotNull(enchants, "enchants must be readable as a map");
        assertEquals(5, enchants.get("sharpness"));
    }
}
