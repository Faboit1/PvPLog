package top.cheesesmp.duelcore.party;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Every party message used in code exists (ResourcesTest covers the other sections), plus the party gui/config keys. */
class PartyResourcesTest {

    private static final Pattern LITERAL = Pattern.compile("\"(party\\.[a-z0-9_.-]+)\"");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flat(String name) throws IOException {
        try (InputStream in = PartyResourcesTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name + " missing");
            Map<String, Object> out = new HashMap<>();
            flatten(new Yaml().load(in), "", out);
            return out;
        }
    }

    @SuppressWarnings("unchecked")
    private static void flatten(Map<String, Object> map, String prefix, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (e.getValue() instanceof Map<?, ?> m) flatten((Map<String, Object>) m, key, out);
            else out.put(key, e.getValue());
        }
    }

    @Test
    void everyPartyMessageExists() throws IOException {
        Map<String, Object> messages = flat("messages.yml");
        Map<String, Object> config = flat("config.yml");
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = LITERAL.matcher(Files.readString(f));
                while (m.find()) used.add(m.group(1));
            }
        }
        List<String> missing = new ArrayList<>();
        for (String key : used) {
            if (key.endsWith(".") || key.endsWith("-")) continue; // dynamic, expanded below
            if (config.containsKey(key)) continue; // config.yml paths
            if (!messages.containsKey(key)) missing.add(key);
        }
        for (PartyService.Result r : PartyService.Result.values()) {
            String key = "party.result." + r.name().toLowerCase(Locale.ROOT);
            if (!messages.containsKey(key)) missing.add(key);
        }
        for (PartyService.Mode m : PartyService.Mode.values()) {
            for (String key : List.of("party.dialog.mode-" + m.id(), "party.dialog.mode-" + m.id() + "-tooltip",
                "party.dialog.kit-body-" + m.id())) {
                if (!messages.containsKey(key)) missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "missing in messages.yml: " + missing);
        assertEquals("", messages.get("party.result.ok"));
    }

    @Test
    void partySectionSitsBeforeTier() throws IOException {
        try (InputStream in = PartyResourcesTest.class.getClassLoader().getResourceAsStream("messages.yml")) {
            assertNotNull(in);
            List<String> top = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (!line.isEmpty() && Character.isLetter(line.charAt(0))) top.add(line.substring(0, line.indexOf(':')));
            }
            assertEquals(top.indexOf("tier") - 1, top.indexOf("party"));
        }
    }

    @Test
    void hubItemAndSettingsExist() throws IOException {
        Map<String, Object> gui = flat("gui.yml");
        assertEquals(1, gui.get("hotbar.party.slot"));
        assertNotNull(org.bukkit.Material.matchMaterial(String.valueOf(gui.get("hotbar.party.item"))));
        assertFalse(String.valueOf(gui.get("hotbar.party.action-bar")).isBlank());
        assertTrue(gui.get("sidebar.match-ffa") instanceof List<?> l && !l.isEmpty());
        assertTrue(gui.get("sidebar.spectate-ffa") instanceof List<?> l && !l.isEmpty());
        assertNotNull(gui.get("party-menu.members-per-page"));
        Map<String, Object> config = flat("config.yml");
        assertEquals(20, config.get("party.max-size"));
        assertEquals(60, config.get("party.invite-seconds"));
    }
}
