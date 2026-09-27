package top.cheesesmp.duelcore.geo;

import java.io.Reader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * countries.yml: the display name, matchmaking region and flag head texture of every country, by ISO 3166-1
 * alpha-2 code. Pure and immutable.
 *
 * <p>The file is read with every value as plain text, so a bare {@code NO} is Norway, not YAML 1.1's "false", and a
 * texture id of only digits stays text; codes may be written in lower case. A flag is a textures.minecraft.net
 * texture id (a whole {@code http://textures.minecraft.net/texture/<id>} URL works too); an empty one means no flag.
 * Entries that can't be used are listed in {@link #problems()} and left out.
 */
public final class Countries {

    /** One country. {@code region}: as written (upper case), null when none; {@code flag}: a texture id or null. */
    public record Country(String code, String name, @Nullable String region, @Nullable String flag) {
    }

    public static final Countries EMPTY = new Countries(Map.of(), List.of());

    /** Common names that aren't a country's name in the file (checked after the names). */
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("uk", "GB"), Map.entry("great britain", "GB"), Map.entry("britain", "GB"),
        Map.entry("england", "GB"), Map.entry("scotland", "GB"), Map.entry("wales", "GB"),
        Map.entry("northern ireland", "GB"), Map.entry("usa", "US"), Map.entry("america", "US"),
        Map.entry("united states of america", "US"), Map.entry("turkey", "TR"), Map.entry("holland", "NL"),
        Map.entry("czech republic", "CZ"), Map.entry("uae", "AE"), Map.entry("korea", "KR"),
        Map.entry("ivory coast", "CI"), Map.entry("macedonia", "MK"), Map.entry("burma", "MM"));

    private final Map<String, Country> byCode;
    private final Map<String, String> byName;
    private final List<String> problems;

    private Countries(Map<String, Country> byCode, List<String> problems) {
        this.byCode = Collections.unmodifiableMap(new TreeMap<>(byCode));
        Map<String, String> names = new HashMap<>();
        for (Country c : this.byCode.values()) names.putIfAbsent(normalize(c.name()), c.code());
        this.byName = Map.copyOf(names);
        this.problems = List.copyOf(problems);
    }

    // ------------------------------------------------------------------ reading

    /** Reads a countries.yml; throws (a SnakeYAML exception) when it isn't valid YAML. */
    public static Countries parse(Reader in) {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(20);
        Yaml yaml = new Yaml(new SafeConstructor(options), new Representer(new DumperOptions()), new DumperOptions(),
            options, new Resolver() {
                @Override
                protected void addImplicitResolvers() {
                    // none: every plain value is text ("NO" stays Norway, "007" stays "007")
                }
            });
        return of(yaml.load(in));
    }

    /**
     * Reads a loaded YAML tree: a {@code countries:} section of code → {name, region, flag} (the section itself is
     * also taken when the top level is missing). A code key YAML read as {@code false} is NO (Norway).
     */
    public static Countries of(@Nullable Object root) {
        Map<String, Country> out = new HashMap<>();
        List<String> problems = new ArrayList<>();
        Object section = root instanceof Map<?, ?> map && map.containsKey("countries") ? map.get("countries") : root;
        if (!(section instanceof Map<?, ?> entries)) {
            if (section != null) problems.add("no countries section");
            return new Countries(out, problems);
        }
        for (Map.Entry<?, ?> e : entries.entrySet()) {
            Object key = e.getKey();
            String code = Boolean.FALSE.equals(key) ? "NO" : code(key == null ? null : String.valueOf(key));
            if (code == null) {
                problems.add("'" + key + "' is not a two-letter country code");
                continue;
            }
            String name = code;
            String region = null;
            String flag = null;
            if (e.getValue() instanceof Map<?, ?> fields) {
                name = text(fields.get("name"), code);
                String r = text(fields.get("region"), "");
                region = r.isEmpty() ? null : r.toUpperCase(Locale.ROOT);
                String f = text(fields.get("flag"), "");
                flag = f.isEmpty() ? null : texture(f);
                if (!f.isEmpty() && flag == null) problems.add(code + ": '" + f + "' is not a textures.minecraft.net texture id");
            } else if (e.getValue() != null) {
                name = text(e.getValue(), code); // "DE: Germany"
            }
            if (out.put(code, new Country(code, name, region, flag)) != null) problems.add(code + " is listed twice");
        }
        return new Countries(out, problems);
    }

    private static String text(@Nullable Object value, String fallback) {
        String s = value == null ? "" : String.valueOf(value).strip();
        return s.isEmpty() ? fallback : s;
    }

    /** These countries, plus those of {@code fallback} that aren't listed here. */
    public Countries withFallback(Countries fallback) {
        Map<String, Country> merged = new HashMap<>(fallback.byCode);
        merged.putAll(byCode);
        return new Countries(merged, problems);
    }

    // ------------------------------------------------------------------ lookups

    public @Nullable Country get(@Nullable String code) {
        String cc = code(code);
        return cc == null ? null : byCode.get(cc);
    }

    public boolean isKnown(@Nullable String code) {
        return get(code) != null;
    }

    /**
     * The code of what a player typed: a known code in any case ("de"), a country's name without regard to case,
     * accents, punctuation or "&amp;" / "and" ("Cote d'Ivoire", "bosnia and herzegovina"), or a common other name
     * ("UK", "USA", "England"). Null when nothing matches.
     */
    public @Nullable String find(@Nullable String input) {
        if (input == null) return null;
        String s = input.strip();
        if (s.isEmpty() || s.length() > 64) return null;
        String cc = code(s);
        if (cc != null && byCode.containsKey(cc)) return cc;
        String key = normalize(s);
        String byNameCode = byName.get(key);
        if (byNameCode != null) return byNameCode;
        String alias = ALIASES.get(key);
        return alias != null && byCode.containsKey(alias) ? alias : null;
    }

    public Collection<Country> all() {
        return byCode.values();
    }

    public int size() {
        return byCode.size();
    }

    /** Countries that have a flag. */
    public int flags() {
        int n = 0;
        for (Country c : byCode.values()) if (c.flag() != null) n++;
        return n;
    }

    /** Entries that couldn't be used, for the log. */
    public List<String> problems() {
        return problems;
    }

    // ------------------------------------------------------------------ helpers

    /** A two-letter code in upper case ("de" → "DE"), or null when {@code raw} isn't two ASCII letters. */
    public static @Nullable String code(@Nullable String raw) {
        if (raw == null) return null;
        String s = raw.strip();
        if (s.length() != 2) return null;
        char a = Character.toUpperCase(s.charAt(0));
        char b = Character.toUpperCase(s.charAt(1));
        return a >= 'A' && a <= 'Z' && b >= 'A' && b <= 'Z' ? "" + a + b : null;
    }

    /**
     * A textures.minecraft.net texture id (16-80 hex digits, lower-cased) from an id or a whole texture URL, or null
     * when it is neither.
     */
    public static @Nullable String texture(@Nullable String raw) {
        if (raw == null) return null;
        String s = raw.strip().toLowerCase(Locale.ROOT);
        int slash = s.lastIndexOf('/');
        if (slash >= 0) {
            if (!s.startsWith("http://textures.minecraft.net/texture/") && !s.startsWith("https://textures.minecraft.net/texture/")) {
                return null;
            }
            s = s.substring(slash + 1);
        }
        return s.matches("[0-9a-f]{16,80}") ? s : null;
    }

    /**
     * Lower case without accents, apostrophes and a leading "the", other punctuation as spaces, "&amp;" as "and",
     * single spaces: "Côte d'Ivoire" → "cote divoire", "Guinea-Bissau" → "guinea bissau".
     */
    static String normalize(String name) {
        String s = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
            .replace("&", " and ").replaceAll("['’`]", "").replaceAll("[^a-z0-9]+", " ").strip();
        return s.startsWith("the ") ? s.substring(4) : s;
    }
}
