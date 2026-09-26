package top.cheesesmp.duelcore.ui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The menu press sounds from gui.yml ({@code menu-sounds}): the switch and one SoundPool line per {@link MenuSound}
 * ({@code "<sound> [pitch] [volume] [@delay]"}, several joined with +, "" = none). A missing line uses the bundled
 * default.
 */
public record MenuSoundStyle(boolean enabled, Map<MenuSound, SoundPool> sounds, List<String> problems) {

    public static MenuSoundStyle parse(YamlConfiguration root) {
        ConfigurationSection y = root.getConfigurationSection("menu-sounds");
        if (y == null) y = new YamlConfiguration();
        List<String> problems = new ArrayList<>();
        Map<MenuSound, SoundPool> sounds = new EnumMap<>(MenuSound.class);
        for (MenuSound kind : MenuSound.values()) {
            String line = y.getString(kind.id(), kind.defaultLine());
            SoundPool pool = SoundPool.parse(line == null || line.isBlank() ? List.of() : List.of(line));
            for (String p : pool.problems()) problems.add("gui.yml menu-sounds." + kind.id() + ": " + p);
            sounds.put(kind, pool);
        }
        return new MenuSoundStyle(y.getBoolean("enabled", true), Map.copyOf(sounds), List.copyOf(problems));
    }

    /** The sound of {@code kind}; empty when set to "". */
    public SoundPool sound(MenuSound kind) {
        return sounds.getOrDefault(kind, SoundPool.EMPTY);
    }
}
