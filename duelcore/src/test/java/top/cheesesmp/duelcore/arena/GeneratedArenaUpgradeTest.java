package top.cheesesmp.duelcore.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Existing servers get the new built-in maps; hand-made and edited arenas are never touched. */
class GeneratedArenaUpgradeTest {

    private static final RelPos SPAWN = new RelPos(1, 1, 1, 0, 0);

    /** A version 1 template as the old generator wrote it: no generator-version key. */
    private static void legacy(File dir, String name, List<String> tags, String extra) throws Exception {
        ArenaSnapshot.empty(2, 2, 2).write(new File(dir, name + ".dca").toPath());
        StringBuilder yml = new StringBuilder("display-name: My " + name + "\ntags:\n");
        for (String t : tags) yml.append("- ").append(t).append('\n');
        yml.append("enabled: false\nbuild-height: 24\n")
            .append("spawn1: {x: 1.0, y: 1.0, z: 1.0, yaw: 0.0, pitch: 0.0}\n")
            .append("spawn2: {x: 1.0, y: 1.0, z: 1.0, yaw: 0.0, pitch: 0.0}\n")
            .append(extra);
        Files.writeString(new File(dir, name + ".yml").toPath(), yml);
    }

    private static YamlConfiguration yml(File dir, String name) {
        return YamlConfiguration.loadConfiguration(new File(dir, name + ".yml"));
    }

    @Test
    void oldBuiltInMapsAreRegeneratedAndBlossomRemoved(@TempDir Path tmp) throws Exception {
        File dir = tmp.toFile();
        legacy(dir, "dunes", List.of("terrain", "extra"), "");
        legacy(dir, "blossom", List.of("terrain"), "");
        legacy(dir, "tundra", List.of("terrain"), "edited: true\n"); // the owner opted out
        legacy(dir, "mesa", List.of("open"), "");                     // same name, but hand-made
        legacy(dir, "castle", List.of("terrain"), "");                // hand-made
        List<String> errors = new ArrayList<>();
        List<String> changes = ArenaManager.upgradeGenerated(dir, errors);
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(2, changes.size(), changes.toString());

        YamlConfiguration dunes = yml(dir, "dunes");
        assertEquals(ArenaGenerator.VERSION, dunes.getInt(ArenaManager.GENERATOR_VERSION));
        assertFalse(dunes.getBoolean(ArenaManager.EDITED, true));
        assertFalse(dunes.getBoolean("enabled", true), "the owner's enabled flag stays");
        assertEquals("My dunes", dunes.getString("display-name"));
        assertEquals(List.of("terrain", "extra"), dunes.getStringList("tags"));
        ArenaSnapshot snap = ArenaSnapshot.read(new File(dir, "dunes.dca").toPath());
        assertEquals(ArenaGenerator.HEIGHT, snap.sizeY());
        assertEquals(snap.sizeX() + "x" + snap.sizeY() + "x" + snap.sizeZ(), dunes.getString("size"));
        assertTrue(dunes.getDouble("spawn1.y") > 20, "spawns moved up with the deeper ground");

        assertFalse(new File(dir, "blossom.yml").exists());
        assertFalse(new File(dir, "blossom.dca").exists());
        // the replaced and removed files are kept aside, never lost
        File backup = new File(dir, ArenaManager.BACKUP_FOLDER);
        for (String kept : List.of("dunes-v1.yml", "dunes-v1.dca", "blossom-v1.yml", "blossom-v1.dca")) {
            assertTrue(new File(backup, kept).isFile(), kept);
        }
        assertEquals("My dunes", YamlConfiguration.loadConfiguration(new File(backup, "dunes-v1.yml")).getString("display-name"));
        assertEquals(2, ArenaSnapshot.read(new File(backup, "dunes-v1.dca").toPath()).sizeY());
        for (String untouched : List.of("tundra", "mesa", "castle")) {
            assertEquals(2, ArenaSnapshot.read(new File(dir, untouched + ".dca").toPath()).sizeY(), untouched);
            assertFalse(yml(dir, untouched).contains(ArenaManager.GENERATOR_VERSION), untouched);
        }
        // up-to-date maps are left alone on the next start, and deleted ones are not recreated
        assertTrue(ArenaManager.upgradeGenerated(dir, errors).isEmpty());
        assertFalse(new File(dir, "greenfield.yml").exists());
    }

    @Test
    void editorSaveMarksABuiltInMapEdited(@TempDir Path tmp) throws Exception {
        File dir = tmp.toFile();
        ArenaSnapshot tiny = ArenaSnapshot.empty(2, 2, 2);
        ArenaManager.writeTemplate(dir, "savanna", "Savanna", List.of("terrain"), SPAWN, SPAWN, 24, tiny, null, 1);
        assertTrue(ArenaManager.outdatedGenerated(yml(dir, "savanna")));
        ArenaManager.writeTemplate(dir, "savanna", "Savanna", List.of("terrain"), SPAWN, SPAWN, 24, tiny, null, 0);
        YamlConfiguration y = yml(dir, "savanna");
        assertTrue(y.getBoolean(ArenaManager.EDITED));
        assertFalse(y.contains(ArenaManager.GENERATOR_VERSION));
        assertFalse(ArenaManager.outdatedGenerated(y));
        // a plain hand-made arena gets neither key
        ArenaManager.writeTemplate(dir, "castle", "Castle", List.of("terrain"), SPAWN, SPAWN, 12, tiny, null, 0);
        assertFalse(yml(dir, "castle").contains(ArenaManager.EDITED));
        assertFalse(yml(dir, "castle").contains(ArenaManager.GENERATOR_VERSION));
    }
}
