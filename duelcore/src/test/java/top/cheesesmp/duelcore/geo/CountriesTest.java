package top.cheesesmp.duelcore.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** countries.yml: the bundled file (Norway included), YAML's bare NO, codes and names players type, flag ids. */
class CountriesTest {

    private static Countries bundled() throws IOException {
        try (InputStream in = CountriesTest.class.getClassLoader().getResourceAsStream("countries.yml")) {
            assertNotNull(in, "countries.yml");
            return Countries.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theBundledFileLoadsCompletely() throws IOException {
        Countries c = bundled();
        assertTrue(c.problems().isEmpty(), "problems: " + c.problems());
        assertEquals(250, c.size());
        assertEquals(227, c.flags());
        Countries.Country norway = c.get("NO");
        assertNotNull(norway, "NO is Norway, not false");
        assertEquals("Norway", norway.name());
        assertEquals("EU", norway.region());
        assertNotNull(norway.flag());
        assertEquals("Germany", c.get("de").name());
        assertNull(c.get("AQ").region(), "Antarctica has no region");
        assertNull(c.get("BV").flag(), "Bouvet Island has no flag");
        // every region is one players can pick (config.yml leaderboard.regions)
        try (InputStream in = CountriesTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            Map<String, Object> config = new Yaml().load(in);
            List<String> regions = (List<String>) ((Map<String, Object>) config.get("leaderboard")).get("regions");
            for (Countries.Country country : c.all()) {
                if (country.region() != null) assertTrue(regions.contains(country.region()), country.code() + " " + country.region());
                if (country.flag() != null) assertTrue(country.flag().matches("[0-9a-f]{16,80}"), country.code());
            }
        }
    }

    @Test
    void bareYamlBooleansAndNumbersStayText() {
        Countries c = Countries.parse(new StringReader("""
            countries:
              NO: { name: Norway, region: EU, flag: 0123456789012345 }
              de: { name: Germany }
              ON: { name: Onland }
              YES: { name: Nope }
              "1A": { name: Bad }
              FR: France
              IT:
            """));
        assertEquals("Norway", c.get("NO").name());
        assertEquals("0123456789012345", c.get("NO").flag(), "a flag id of digits is not a number");
        assertEquals("Germany", c.get("DE").name(), "lower-case code");
        assertEquals("Onland", c.get("ON").name(), "ON is text, not true");
        assertEquals("France", c.get("FR").name(), "a plain name");
        assertEquals("IT", c.get("IT").name(), "no name: the code");
        assertFalse(c.isKnown("YE"));
        assertEquals(2, c.problems().size(), "YES and 1A: " + c.problems());
    }

    @Test
    void aTreeFromAnotherLoaderStillFindsNorway() {
        // a plain YAML 1.1 loader turns a bare NO into false
        Object tree = new Yaml().load("countries:\n  NO: { name: Norway }\n  SE: { name: Sweden }\n");
        Countries c = Countries.of(tree);
        assertEquals("Norway", c.get("NO").name());
        assertEquals("Sweden", c.get("SE").name());
    }

    @Test
    void badFlagsAreReportedAndLeftOut() {
        Countries c = Countries.parse(new StringReader("""
            countries:
              AT: { name: Austria, flag: "not a texture" }
              BE: { name: Belgium, flag: "http://textures.minecraft.net/texture/C8E0E0DE705D366BA59506B3FB1A04FD3D51AFF90E5CFB4EBBEA66EA5976C0FF" }
              CH: { name: Switzerland, flag: "https://evil.example/texture/c8e0e0de705d366ba59506b3fb1a04fd" }
              DK: { name: Denmark, flag: "" }
            """));
        assertNull(c.get("AT").flag());
        assertEquals("c8e0e0de705d366ba59506b3fb1a04fd3d51aff90e5cfb4ebbea66ea5976c0ff", c.get("BE").flag(), "a whole texture URL");
        assertNull(c.get("CH").flag(), "only textures.minecraft.net");
        assertNull(c.get("DK").flag(), "empty = no flag");
        assertEquals(2, c.problems().size(), c.problems().toString());
        assertEquals(4, c.size());
    }

    @Test
    void notYamlThrows() {
        assertThrows(RuntimeException.class, () -> Countries.parse(new StringReader("countries: [unclosed")));
        assertEquals(0, Countries.parse(new StringReader("")).size());
        assertEquals(1, Countries.parse(new StringReader("countries: 5")).problems().size());
    }

    @Test
    void missingCountriesComeFromTheFallback() throws IOException {
        Countries own = Countries.parse(new StringReader("countries:\n  DE: { name: Deutschland, region: EU }\n"));
        Countries merged = own.withFallback(bundled());
        assertEquals("Deutschland", merged.get("DE").name(), "the file wins");
        assertNull(merged.get("DE").flag(), "the whole entry comes from the file");
        assertEquals("Norway", merged.get("NO").name());
        assertEquals(250, merged.size());
    }

    @Test
    void findsWhatPlayersType() throws IOException {
        Countries c = bundled();
        assertEquals("DE", c.find("de"));
        assertEquals("DE", c.find(" DE "));
        assertEquals("NO", c.find("no"));
        assertEquals("DE", c.find("germany"));
        assertEquals("CI", c.find("Cote d'Ivoire"));
        assertEquals("CI", c.find("côte divoire"));
        assertEquals("BA", c.find("Bosnia and Herzegovina"));
        assertEquals("BA", c.find("bosnia & herzegovina"));
        assertEquals("TR", c.find("Turkiye"));
        assertEquals("TR", c.find("Turkey"));
        assertEquals("GB", c.find("UK"));
        assertEquals("GB", c.find("england"));
        assertEquals("US", c.find("USA"));
        assertEquals("NL", c.find("the Netherlands"));
        assertEquals("GW", c.find("Guinea Bissau"));
        assertNull(c.find("XX"));
        assertNull(c.find("Atlantis"));
        assertNull(c.find(""));
        assertNull(c.find(null));
        assertNull(c.find("x".repeat(100)));
    }

    @Test
    void codesAndTextures() {
        assertEquals("DE", Countries.code("de"));
        assertEquals("DE", Countries.code(" dE "));
        assertNull(Countries.code("DEU"));
        assertNull(Countries.code("D1"));
        assertNull(Countries.code("Ää"));
        assertNull(Countries.code(null));
        assertEquals("abcdef0123456789", Countries.texture("ABCDEF0123456789"));
        assertNull(Countries.texture("abc"));
        assertNull(Countries.texture("xyz0123456789abcdef"));
        assertNull(Countries.texture(null));
    }
}
