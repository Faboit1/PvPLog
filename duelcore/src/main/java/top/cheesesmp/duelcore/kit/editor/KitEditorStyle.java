package top.cheesesmp.duelcore.kit.editor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.ui.SoundPool;

/**
 * The kit editor's look from gui.yml ({@code kit-editor}): the items of its fixed slots, the kit picker's columns and
 * width, and its sounds (SoundPool lines: {@code "<sound> [pitch] [volume] [@delay]"}, several joined with +).
 */
public record KitEditorStyle(Material filler, Material offhandLabel, Material emptyArmor, Material info, Material save,
                             Material reset, Material clear, Material cancel, int pickerColumns, int pickerWidth,
                             Map<String, SoundPool> sounds, List<String> problems) {

    /** Every sound the editor plays. */
    public static final List<String> SOUNDS = List.of("open", "pick", "place", "swap", "deny", "save", "reset", "clear", "cancel");

    public static KitEditorStyle parse(YamlConfiguration root) {
        ConfigurationSection y = root.getConfigurationSection("kit-editor");
        if (y == null) y = new YamlConfiguration();
        List<String> problems = new ArrayList<>();
        Map<String, SoundPool> sounds = new HashMap<>();
        for (String name : SOUNDS) {
            String line = y.getString("sounds." + name, "");
            SoundPool pool = SoundPool.parse(line == null || line.isBlank() ? List.of() : List.of(line));
            for (String p : pool.problems()) problems.add("gui.yml kit-editor.sounds." + name + ": " + p);
            sounds.put(name, pool);
        }
        return new KitEditorStyle(
            material(y, "filler", Material.GRAY_STAINED_GLASS_PANE, problems),
            material(y, "offhand-label", Material.LIGHT_BLUE_STAINED_GLASS_PANE, problems),
            material(y, "empty-armor", Material.BLACK_STAINED_GLASS_PANE, problems),
            material(y, "info", Material.BOOK, problems),
            material(y, "save", Material.LIME_CONCRETE, problems),
            material(y, "reset", Material.ORANGE_CONCRETE, problems),
            material(y, "clear", Material.BARRIER, problems),
            material(y, "cancel", Material.RED_CONCRETE, problems),
            Math.clamp(y.getInt("picker-columns", 3), 1, 6),
            Math.clamp(y.getInt("picker-width", 310), 100, 1024),
            Map.copyOf(sounds), List.copyOf(problems));
    }

    private static Material material(ConfigurationSection y, String key, Material def, List<String> problems) {
        String raw = y.getString(key);
        if (raw == null || raw.isBlank()) return def;
        Material m = Material.matchMaterial(raw);
        if (m == null || !m.isItem() || m.isAir()) {
            problems.add("gui.yml kit-editor." + key + ": unknown item '" + raw + "'");
            return def;
        }
        return m;
    }

    /** The sound {@code name} (one of {@link #SOUNDS}); empty when unset. */
    public SoundPool sound(String name) {
        @Nullable SoundPool pool = sounds.get(name);
        return pool == null ? SoundPool.EMPTY : pool;
    }
}
