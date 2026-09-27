package top.cheesesmp.duelcore.leaderboard;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Country names by two-letter code, from countries.yml ({@code countries.<code>.name}): the plugin folder's copy when
 * there is one, else the bundled file. Used for the country leaderboard's title and for which codes a board can be
 * filtered by. Read on enable and {@code /duelcore reload}, never while a board is shown.
 */
final class CountryNames {

    private final Map<String, String> names;

    CountryNames(Map<String, String> names) {
        this.names = Collections.unmodifiableMap(new TreeMap<>(names));
    }

    static CountryNames load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "countries.yml");
        if (file.isFile()) {
            try {
                YamlConfiguration yml = new YamlConfiguration();
                yml.load(file);
                return parse(yml);
            } catch (Exception e) {
                plugin.getLogger().warning("countries.yml is invalid, the leaderboard uses the bundled country names: "
                    + e.getMessage());
            }
        }
        try (InputStream in = plugin.getResource("countries.yml")) {
            if (in != null) return parse(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read the bundled countries.yml: " + e.getMessage());
        }
        return new CountryNames(Map.of());
    }

    /** The {@code countries} section: entries whose key isn't two letters are skipped, a missing name is the code. */
    static CountryNames parse(ConfigurationSection yml) {
        Map<String, String> names = new TreeMap<>();
        ConfigurationSection countries = yml.getConfigurationSection("countries");
        if (countries != null) {
            for (String key : countries.getKeys(false)) {
                String code = key.toUpperCase(Locale.ROOT);
                if (!code.matches("[A-Z]{2}")) continue;
                String name = countries.getString(key + ".name", "");
                names.put(code, name == null || name.isBlank() ? code : name.strip());
            }
        }
        return new CountryNames(names);
    }

    /** The name of a listed country (any case), null for other codes. */
    @Nullable String name(String code) {
        return names.get(code.toUpperCase(Locale.ROOT));
    }

    boolean known(String code) {
        return names.containsKey(code.toUpperCase(Locale.ROOT));
    }

    /** Every listed code, A–Z. */
    Set<String> codes() {
        return names.keySet();
    }
}
