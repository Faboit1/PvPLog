package top.cheesesmp.duelcore.queue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The queue music pool from config.yml ({@code queue.music.tracks}): one line per track,
 * {@code <sound id> <length> [speed]}, the length of the recording in seconds ({@code 148}) or minutes and seconds
 * ({@code 2:28}) and an optional playback speed ({@code 2x}, 0.5-2; it is the sound's pitch, so 2x also plays an octave
 * higher and takes half as long). The next track starts when one ends, so the length should match the recording. Pure parsing, no Bukkit; invalid lines are skipped and listed in
 * {@link #problems()}.
 */
public final class MusicTracks {

    public static final MusicTracks EMPTY = new MusicTracks(List.of(), List.of());

    private static final Pattern KEY = Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9_./-]+");

    /** A sound id ({@code minecraft:} added when missing), the recording's length and the playback speed (pitch). */
    public record Track(String key, int seconds, float speed) {

        public Track(String key, int seconds) {
            this(key, seconds, 1f);
        }

        /** How long it actually plays at its speed, in milliseconds. */
        public long playMillis() {
            return Math.round(seconds * 1000.0 / speed);
        }
    }

    private final List<Track> tracks;
    private final List<String> problems;

    private MusicTracks(List<Track> tracks, List<String> problems) {
        this.tracks = tracks;
        this.problems = problems;
    }

    public static MusicTracks parse(List<String> lines) {
        List<Track> tracks = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            try {
                tracks.add(track(line));
            } catch (IllegalArgumentException e) {
                problems.add("\"" + line + "\": " + e.getMessage());
            }
        }
        return new MusicTracks(List.copyOf(tracks), List.copyOf(problems));
    }

    static Track track(String line) {
        String[] tokens = line.trim().split("\\s+");
        if (tokens.length != 2 && tokens.length != 3) throw new IllegalArgumentException("expected <sound id> <seconds> [speed]");
        String key = tokens[0].toLowerCase(Locale.ROOT);
        if (!KEY.matcher(key).matches()) throw new IllegalArgumentException("bad sound key " + tokens[0]);
        if (key.indexOf(':') < 0) key = "minecraft:" + key;
        int seconds;
        try {
            String t = tokens[1];
            int colon = t.indexOf(':');
            seconds = colon < 0 ? Integer.parseInt(t)
                : Integer.parseInt(t.substring(0, colon)) * 60 + Integer.parseInt(t.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad length " + tokens[1]);
        }
        if (seconds < 5 || seconds > 1800) throw new IllegalArgumentException("length must be 5-1800 seconds");
        float speed = 1f;
        if (tokens.length == 3) {
            String t = tokens[2].toLowerCase(Locale.ROOT);
            try {
                speed = Float.parseFloat(t.endsWith("x") ? t.substring(0, t.length() - 1) : t);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad speed " + tokens[2]);
            }
            if (!(speed >= 0.5f && speed <= 2f)) throw new IllegalArgumentException("speed must be 0.5x-2x");
        }
        return new Track(key, seconds, speed);
    }

    /** The same pool without the tracks {@code keep} rejects (e.g. sounds this server version doesn't have). */
    public MusicTracks filter(java.util.function.Predicate<Track> keep) {
        List<Track> kept = new ArrayList<>();
        for (Track t : tracks) if (keep.test(t)) kept.add(t);
        return new MusicTracks(List.copyOf(kept), problems);
    }

    public boolean isEmpty() {
        return tracks.isEmpty();
    }

    public List<Track> tracks() {
        return tracks;
    }

    public List<String> problems() {
        return problems;
    }

    /** A random track, never {@code previous} again right away when there is another one; null when empty. */
    public @Nullable Track pick(RandomGenerator random, @Nullable Track previous) {
        if (tracks.isEmpty()) return null;
        if (tracks.size() == 1 || previous == null || !tracks.contains(previous)) {
            return tracks.get(random.nextInt(tracks.size()));
        }
        int i = random.nextInt(tracks.size() - 1);
        Track t = tracks.get(i);
        return t.equals(previous) ? tracks.getLast() : t;
    }
}
