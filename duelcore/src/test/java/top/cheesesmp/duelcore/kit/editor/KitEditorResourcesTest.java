package top.cheesesmp.duelcore.kit.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import top.cheesesmp.duelcore.ui.SoundPool;

/** The kit editor's messages, gui.yml entries and permission exist (ResourcesTest covers the other sections). */
class KitEditorResourcesTest {

    private static final Pattern LITERAL = Pattern.compile("\"(kit-editor\\.[a-z0-9_.-]+)\"");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String name) throws IOException {
        try (InputStream in = KitEditorResourcesTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name + " missing");
            return new Yaml().load(in);
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

    private static Map<String, Object> flat(String name) throws IOException {
        Map<String, Object> out = new HashMap<>();
        flatten(load(name), "", out);
        return out;
    }

    @Test
    void everyKitEditorMessageExists() throws IOException {
        Map<String, Object> messages = flat("messages.yml");
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = LITERAL.matcher(Files.readString(f));
                while (m.find()) used.add(m.group(1));
            }
        }
        List<String> missing = new ArrayList<>();
        for (String key : used) {
            if (key.endsWith(".")) continue; // dynamic, expanded below
            if (!messages.containsKey(key)) missing.add(key);
        }
        for (String piece : List.of("helmet", "chestplate", "leggings", "boots")) {
            if (!messages.containsKey("kit-editor.item.pieces." + piece)) missing.add("kit-editor.item.pieces." + piece);
        }
        assertTrue(used.size() > 20, "found the literals: " + used);
        assertTrue(missing.isEmpty(), "missing in messages.yml: " + missing);
    }

    @Test
    void hotbarItemHasItsOwnSlot() throws IOException {
        Map<String, Object> gui = flat("gui.yml");
        assertEquals(3, gui.get("hotbar.kit-editor.slot"));
        assertNotNull(gui.get("hotbar.kit-editor.action-bar"));
        Set<Object> taken = new java.util.HashSet<>();
        for (Map.Entry<String, Object> e : gui.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("hotbar.") || !k.endsWith(".slot")) continue;
            if (k.contains("leave-queue") || k.contains("stop-spectating")) continue; // replace another item
            assertTrue(taken.add(e.getValue()), "two hub items in slot " + e.getValue() + " (" + k + ")");
        }
    }

    @Test
    void soundsParse() throws IOException {
        Map<String, Object> gui = flat("gui.yml");
        for (String name : KitEditorStyle.SOUNDS) {
            Object line = gui.get("kit-editor.sounds." + name);
            assertNotNull(line, "gui.yml kit-editor.sounds." + name);
            SoundPool pool = SoundPool.parse(List.of(String.valueOf(line)));
            assertTrue(pool.problems().isEmpty(), name + ": " + pool.problems());
            assertEquals(1, pool.combos().size(), name);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void permissionIsForEveryone() throws IOException {
        Map<String, Object> perms = (Map<String, Object>) load("paper-plugin.yml").get("permissions");
        Map<String, Object> node = (Map<String, Object>) perms.get(KitEditor.PERMISSION);
        assertNotNull(node, KitEditor.PERMISSION);
        assertEquals(true, node.get("default"));
        Map<String, Object> player = (Map<String, Object>) ((Map<String, Object>) perms.get("duelcore.player")).get("children");
        assertEquals(true, player.get(KitEditor.PERMISSION));
    }
}
