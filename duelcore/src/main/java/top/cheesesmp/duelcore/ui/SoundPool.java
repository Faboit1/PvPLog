package top.cheesesmp.duelcore.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * A pool of sound combinations from config.yml, one of which is picked at random each time (so match starts don't
 * always sound the same). Pure parsing, no Bukkit.
 *
 * <p>Each line is one combination: sounds played together, joined with {@code +}. A sound is
 * {@code <key> [pitch | min-max] [volume] [@delay]}: a vanilla sound id ({@code minecraft:} optional), a fixed pitch
 * or a range picked from at random (0.5–2), a volume (default 0.7) and a delay in ticks. Invalid lines are skipped and
 * listed in {@link #problems()}.
 */
public final class SoundPool {

    public static final SoundPool EMPTY = new SoundPool(List.of(), List.of());

    private static final Pattern KEY = Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9_./-]+");
    private static final float DEFAULT_VOLUME = 0.7f;

    /** One sound of a combination, as configured. */
    public record Note(String key, float minPitch, float maxPitch, float volume, int delay) {
    }

    /** One sound ready to play: the pitch is decided. */
    public record Played(String key, float pitch, float volume, int delay) {
    }

    private final List<List<Note>> combos;
    private final List<String> problems;

    private SoundPool(List<List<Note>> combos, List<String> problems) {
        this.combos = combos;
        this.problems = problems;
    }

    public static SoundPool parse(List<String> lines) {
        List<List<Note>> combos = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            try {
                List<Note> combo = new ArrayList<>();
                for (String part : line.split("\\+")) {
                    if (!part.isBlank()) combo.add(note(part));
                }
                if (!combo.isEmpty()) combos.add(List.copyOf(combo));
            } catch (IllegalArgumentException e) {
                problems.add("\"" + line + "\": " + e.getMessage());
            }
        }
        return new SoundPool(List.copyOf(combos), List.copyOf(problems));
    }

    static Note note(String spec) {
        String[] tokens = spec.trim().split("\\s+");
        String key = tokens[0].toLowerCase(Locale.ROOT);
        if (!KEY.matcher(key).matches()) throw new IllegalArgumentException("bad sound key " + tokens[0]);
        if (key.indexOf(':') < 0) key = "minecraft:" + key;
        float min = 1f;
        float max = 1f;
        float volume = DEFAULT_VOLUME;
        int delay = 0;
        int numbers = 0;
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            try {
                if (t.startsWith("@")) {
                    delay = Math.clamp(Integer.parseInt(t.substring(1)), 0, 100);
                } else if (numbers == 0) {
                    int dash = t.indexOf('-', 1);
                    min = Float.parseFloat(dash < 0 ? t : t.substring(0, dash));
                    max = dash < 0 ? min : Float.parseFloat(t.substring(dash + 1));
                    numbers++;
                } else if (numbers == 1) {
                    volume = Float.parseFloat(t);
                    numbers++;
                } else {
                    throw new IllegalArgumentException("unexpected " + t);
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad number " + t);
            }
        }
        if (min > max) {
            float swap = min;
            min = max;
            max = swap;
        }
        return new Note(key, Math.clamp(min, 0.5f, 2f), Math.clamp(max, 0.5f, 2f), Math.clamp(volume, 0f, 4f), delay);
    }

    public boolean isEmpty() {
        return combos.isEmpty();
    }

    public List<List<Note>> combos() {
        return combos;
    }

    public List<String> problems() {
        return problems;
    }

    /** A random combination with its pitches decided; empty when the pool is. */
    public List<Played> pick(RandomGenerator random) {
        if (combos.isEmpty()) return List.of();
        List<Note> combo = combos.get(random.nextInt(combos.size()));
        List<Played> out = new ArrayList<>(combo.size());
        for (Note n : combo) {
            float pitch = n.maxPitch() > n.minPitch() ? n.minPitch() + random.nextFloat() * (n.maxPitch() - n.minPitch())
                : n.minPitch();
            out.add(new Played(n.key(), pitch, n.volume(), n.delay()));
        }
        return out;
    }
}
