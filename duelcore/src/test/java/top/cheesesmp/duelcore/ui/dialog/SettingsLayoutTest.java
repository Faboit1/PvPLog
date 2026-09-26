package top.cheesesmp.duelcore.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import top.cheesesmp.duelcore.profile.Setting;
import top.cheesesmp.duelcore.ui.dialog.SettingsLayout.Entry;
import top.cheesesmp.duelcore.ui.dialog.SettingsLayout.Section;

/** The settings menu's sections: every setting shown once, texts and icons for every row, the max-ping steps. */
class SettingsLayoutTest {

    @Test
    void everySettingIsShownOnce() {
        Map<Setting, Integer> seen = new EnumMap<>(Setting.class);
        Set<String> ids = new HashSet<>();
        int duel = 0;
        for (Section s : Section.values()) {
            assertTrue(!s.entries().isEmpty(), s + " is empty");
            for (Entry e : s.entries()) {
                assertTrue(ids.add(e.id()), "duplicate row " + e.id());
                if (e.kind() == SettingsLayout.Kind.DUEL_REQUESTS) duel++;
                if (e.kind() == SettingsLayout.Kind.TOGGLE) {
                    assertNotNull(e.setting(), e.id());
                    assertEquals(SettingsLayout.id(e.setting()), e.id());
                    seen.merge(e.setting(), 1, Integer::sum);
                }
            }
        }
        assertEquals(1, duel, "one duel request row");
        for (Setting s : Setting.values()) {
            // the two bits of the duel request choice are its own row
            int expected = s == Setting.DUEL_REQUESTS || s == Setting.DUEL_FRIENDS_ONLY ? 0 : 1;
            assertEquals(expected, seen.getOrDefault(s, 0), s + " rows");
        }
    }

    @Test
    void lookupsTolerateBadPayloads() {
        assertEquals(Section.GAMEPLAY, Section.parse(null));
        assertEquals(Section.GAMEPLAY, Section.parse("nope"));
        assertEquals(Section.QUEUE, Section.parse("queue"));
        assertNull(SettingsLayout.find("nope"));
        assertNull(SettingsLayout.find(null));
        assertEquals(Setting.AUTO_GG, SettingsLayout.find("auto-gg").setting());
    }

    @Test
    void maxPingSteps() {
        assertEquals(75, SettingsLayout.stepPing(50, 1));
        assertEquals(50, SettingsLayout.stepPing(50, -1), "the strictest stays");
        assertEquals(0, SettingsLayout.stepPing(500, 1), "past the loosest step: any");
        assertEquals(0, SettingsLayout.stepPing(0, 1), "any stays any");
        assertEquals(500, SettingsLayout.stepPing(0, -1));
        // between two steps (the old slider went in 25s): to the neighbours
        assertEquals(150, SettingsLayout.stepPing(125, 1));
        assertEquals(100, SettingsLayout.stepPing(125, -1));
        assertEquals(0, SettingsLayout.stepPing(800, 1));
        assertEquals(500, SettingsLayout.stepPing(800, -1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyRowHasTextsAndAnIcon() throws IOException {
        Map<String, Object> messages = load("messages.yml");
        Map<String, Object> settings = (Map<String, Object>) ((Map<String, Object>) messages.get("dialog")).get("settings");
        Map<String, Object> items = (Map<String, Object>) settings.get("items");
        Map<String, Object> icons = (Map<String, Object>) ((Map<String, Object>) load("gui.yml").get("settings-menu")).get("icons");
        List<String> missing = new ArrayList<>();
        for (Section s : Section.values()) {
            for (String group : List.of("sections", "section-hover", "section-body")) {
                if (!((Map<String, Object>) settings.get(group)).containsKey(s.id())) missing.add("dialog.settings." + group + "." + s.id());
            }
            if (!icons.containsKey("section-" + s.id())) missing.add("gui.yml icon section-" + s.id());
            for (Entry e : s.entries()) {
                Map<String, Object> item = (Map<String, Object>) items.get(e.id());
                if (item == null || !item.containsKey("name") || !item.containsKey("hover")) missing.add("dialog.settings.items." + e.id());
                if (!icons.containsKey(e.id())) missing.add("gui.yml icon " + e.id());
            }
        }
        for (String who : List.of("everyone", "friends", "nobody")) {
            if (!((Map<String, Object>) settings.get("duel")).containsKey(who)) missing.add("dialog.settings.duel." + who);
        }
        assertTrue(missing.isEmpty(), "missing: " + missing);
    }

    private static Map<String, Object> load(String name) throws IOException {
        try (InputStream in = SettingsLayoutTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name);
            return new Yaml().load(in);
        }
    }
}
