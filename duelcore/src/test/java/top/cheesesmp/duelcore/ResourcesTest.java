package top.cheesesmp.duelcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import top.cheesesmp.duelcore.kit.KitManager;
import top.cheesesmp.duelcore.match.DuelRequestService;
import top.cheesesmp.duelcore.match.SpectateService;
import top.cheesesmp.duelcore.queue.QueueMode;
import top.cheesesmp.duelcore.queue.QueueService;

/** Consistency checks between code and bundled resources. */
class ResourcesTest {

    private static final Pattern LITERAL = Pattern.compile(
        "\"((?:admin|arena|command|dialog|duel|hub|kit|match|mode|profile|queue|results|season|settings|spectate|tier)\\.[a-z0-9_.-]+)\"");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String name) throws IOException {
        try (InputStream in = ResourcesTest.class.getClassLoader().getResourceAsStream(name)) {
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
        Map<String, Object> out = new java.util.HashMap<>();
        flatten(yaml(name), "", out);
        return out;
    }

    @Test
    void everyMessageKeyUsedInCodeExists() throws IOException {
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
            if (key.endsWith(".") || key.endsWith("-")) continue; // dynamic prefixes, expanded below
            if (config.containsKey(key) || key.startsWith("hub.spawn")) continue; // config paths
            if (!messages.containsKey(key)) missing.add(key);
        }
        for (QueueMode m : QueueMode.values()) check(messages, missing, "mode." + m.id());
        for (QueueService.JoinResult r : QueueService.JoinResult.values()) check(messages, missing, "queue.result." + lower(r));
        for (SpectateService.Result r : SpectateService.Result.values()) check(messages, missing, "spectate.result." + lower(r));
        for (DuelRequestService.Result r : DuelRequestService.Result.values()) check(messages, missing, "duel.result." + lower(r));
        for (String o : List.of("victory", "defeat", "draw")) {
            check(messages, missing, "results.title-" + o);
            check(messages, missing, "results.word-" + o);
        }
        assertTrue(missing.isEmpty(), "missing in messages.yml: " + missing);
    }

    private static String lower(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    private static void check(Map<String, Object> messages, List<String> missing, String key) {
        if (!messages.containsKey(key)) missing.add(key);
    }

    @Test
    void everyMessageParses() throws IOException {
        Map<String, Object> messages = flat("messages.yml");
        TagResolver theme = TagResolver.resolver(Placeholder.styling("accent"), Placeholder.styling("text"),
            Placeholder.styling("muted"), Placeholder.styling("good"), Placeholder.styling("bad"));
        MiniMessage mm = MiniMessage.builder().strict(false).build();
        for (Map.Entry<String, Object> e : messages.entrySet()) {
            if (e.getKey().startsWith("theme.")) continue;
            String value = String.valueOf(e.getValue());
            try {
                mm.deserialize(value, theme);
            } catch (RuntimeException ex) {
                fail("messages.yml " + e.getKey() + " does not parse: " + ex.getMessage());
            }
        }
        for (String f : List.of("gui.yml")) {
            Map<String, Object> gui = flat(f);
            for (Object v : gui.values()) {
                if (v instanceof String s) mm.deserialize(s, theme);
                if (v instanceof List<?> l) for (Object o : l) mm.deserialize(String.valueOf(o), theme);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void defaultKitsAreWellFormed() throws IOException {
        Set<String> enchants = Set.of("protection", "blast_protection", "sharpness", "unbreaking", "fire_aspect", "feather_falling",
            "mending", "density", "wind_burst", "power", "punch", "flame", "infinity", "lunge", "efficiency", "quick_charge",
            "piercing", "knockback", "breach");
        Set<String> potions = Set.of("strong_healing", "strong_swiftness", "fire_resistance", "long_fire_resistance",
            "strong_strength", "swiftness", "strength");
        for (String id : KitManager.DEFAULT_KITS) {
            Map<String, Object> kit = yaml("kits/" + id + ".yml");
            assertNotNull(kit.get("display-name"), id + " display-name");
            assertNotNull(org.bukkit.Material.matchMaterial(String.valueOf(kit.get("icon"))), id + " icon");
            int firstTo = (Integer) kit.get("first-to");
            assertTrue(firstTo >= 1 && firstTo <= 15, id + " first-to");
            assertTrue(!((List<String>) kit.get("arena-tags")).isEmpty(), id + " arena-tags");
            Map<String, Object> loadout = (Map<String, Object>) kit.get("loadout");
            List<Object> specs = new ArrayList<>();
            Map<String, Object> armor = (Map<String, Object>) loadout.get("armor");
            if (armor != null) specs.addAll(armor.values());
            Map<Object, Object> items = (Map<Object, Object>) loadout.get("items");
            assertNotNull(items, id + " items");
            for (Map.Entry<Object, Object> e : items.entrySet()) {
                int slot = (Integer) e.getKey();
                assertTrue(slot >= 0 && slot <= 35, id + " slot " + slot);
                specs.add(e.getValue());
            }
            if (loadout.get("offhand") != null) specs.add(loadout.get("offhand"));
            if (loadout.get("fill") instanceof Map<?, ?> fill) specs.add(fill.get("item"));
            for (Object spec : specs) {
                String item = spec instanceof Map<?, ?> m ? String.valueOf(m.get("item")) : String.valueOf(spec);
                assertNotNull(org.bukkit.Material.matchMaterial(item), id + ": unknown item " + item);
                if (spec instanceof Map<?, ?> m) {
                    if (m.get("enchants") instanceof Map<?, ?> en) {
                        for (Object ench : en.keySet()) assertTrue(enchants.contains(String.valueOf(ench)), id + ": enchant " + ench);
                    }
                    if (m.get("potion") != null) {
                        assertTrue(potions.contains(String.valueOf(m.get("potion"))), id + ": potion " + m.get("potion"));
                    }
                }
            }
        }
        assertEquals(15, KitManager.DEFAULT_KITS.size());
    }
}
