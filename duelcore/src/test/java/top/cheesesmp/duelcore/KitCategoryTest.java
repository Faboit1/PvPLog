package top.cheesesmp.duelcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.kit.KitManager;

/** Queue menu categories: parsing (including the old main/extra values) and the default kits' tabs. */
class KitCategoryTest {

    @Test
    void parsesNewAndOldIds() {
        assertEquals(Kit.Category.WEAPONS, Kit.Category.parse("weapons"));
        assertEquals(Kit.Category.VANILLA, Kit.Category.parse(" Vanilla "));
        assertEquals(Kit.Category.SKILLS, Kit.Category.parse("skills"));
        assertEquals(Kit.Category.WEAPONS, Kit.Category.parse("main"));
        assertEquals(Kit.Category.VANILLA, Kit.Category.parse("extra"));
        assertNull(Kit.Category.parse("nope"));
        assertNull(Kit.Category.parse(null));
        for (Kit.Category c : Kit.Category.values()) assertEquals(c, Kit.Category.parse(c.id()));
    }

    @Test
    void defaultKitsSitInTheirTabs() throws IOException {
        Map<String, Kit.Category> expected = Map.ofEntries(
            Map.entry("sword", Kit.Category.WEAPONS), Map.entry("spear", Kit.Category.WEAPONS),
            Map.entry("mace", Kit.Category.WEAPONS), Map.entry("shield", Kit.Category.WEAPONS),
            Map.entry("bow", Kit.Category.WEAPONS),
            Map.entry("crystal", Kit.Category.VANILLA), Map.entry("smp", Kit.Category.VANILLA),
            Map.entry("diasmp", Kit.Category.VANILLA), Map.entry("cart", Kit.Category.VANILLA),
            Map.entry("creeper", Kit.Category.VANILLA), Map.entry("earlygame", Kit.Category.VANILLA),
            Map.entry("lategame", Kit.Category.VANILLA), Map.entry("endgame", Kit.Category.VANILLA),
            Map.entry("pot", Kit.Category.SKILLS), Map.entry("nethpot", Kit.Category.SKILLS));
        for (String id : KitManager.DEFAULT_KITS) {
            try (InputStream in = KitCategoryTest.class.getClassLoader().getResourceAsStream("kits/" + id + ".yml")) {
                assertNotNull(in, id);
                Map<String, Object> kit = new Yaml().load(in);
                assertEquals(expected.get(id), Kit.Category.parse(String.valueOf(kit.get("category"))), id);
            }
        }
    }
}
