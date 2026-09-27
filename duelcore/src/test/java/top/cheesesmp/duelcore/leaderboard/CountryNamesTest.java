package top.cheesesmp.duelcore.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Country names for the country leaderboard, from the bundled countries.yml and hand-edited ones. */
class CountryNamesTest {

    @Test
    void bundledCountriesHaveNames() throws Exception {
        CountryNames names;
        try (InputStream in = CountryNamesTest.class.getResourceAsStream("/countries.yml")) {
            assertNotNull(in, "countries.yml");
            names = CountryNames.parse(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
        assertEquals(250, names.codes().size());
        assertEquals("Germany", names.name("DE"));
        assertEquals("Germany", names.name("de"));
        assertEquals("Norway", names.name("NO"), "quoted keys: NO is not read as false");
        assertEquals("Namibia", names.name("NA"));
        assertTrue(names.known("us"));
        assertFalse(names.known("ZZ"));
        assertNull(names.name("ZZ"));
        for (String code : names.codes()) assertTrue(code.matches("[A-Z]{2}"), code);
    }

    @Test
    void oddEntriesAreSkippedOrFallBackToTheCode() throws Exception {
        YamlConfiguration yml = new YamlConfiguration();
        yml.loadFromString("countries:\n  \"de\": { name: \"  Deutschland \" }\n  \"XK\": { region: EU }\n"
            + "  \"EUR\": { name: \"Europe\" }\n  NO: { name: \"Norway\" }\n");
        CountryNames names = CountryNames.parse(yml);
        assertEquals("Deutschland", names.name("DE"));
        assertEquals("XK", names.name("XK"), "no name: the code");
        assertFalse(names.known("EUR"));
        assertEquals(2, names.codes().size(), "an unquoted NO is read as false and skipped");
        assertEquals(0, CountryNames.parse(new YamlConfiguration()).codes().size());
    }
}
