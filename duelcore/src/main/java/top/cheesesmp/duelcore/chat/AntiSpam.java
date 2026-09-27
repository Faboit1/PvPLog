package top.cheesesmp.duelcore.chat;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * Chat anti-spam: pure logic (no Bukkit calls besides reading the config section), so it can be unit tested with a fake
 * clock. Thread-safe: chat arrives on async threads, commands on the main thread. Every player's state is guarded by
 * its own lock; the cross-player wave list by one global lock, only ever taken inside a player's lock.
 *
 * <p>A message goes through, in order:
 * <ol>
 *   <li>mute check (muted players can't chat or send private messages),</li>
 *   <li>clean-up: invisible/format characters, private-use glyphs, zalgo marks and odd spaces are removed, runs of the
 *       same character (or short unit: "hahaha") are shortened, mostly-caps messages are lowercased,</li>
 *   <li>rate limit: at most N accepted messages per window and a minimum delay between two,</li>
 *   <li>advertising (IPs and domains, also obfuscated), symbol-only junk,</li>
 *   <li>duplicates and near-duplicates of the player's recent messages (short common phrases are lenient),</li>
 *   <li>waves: the same message from several players in a few seconds.</li>
 * </ol>
 * Violations add decaying "heat"; enough heat starts a temporary mute, longer for every repeat (strike).
 */
public final class AntiSpam {

    /** Why a message was blocked. {@link #key} is the messages.yml key under {@code chat.anti-spam}. */
    public enum Reason {
        MUTED("muted"), TOO_FAST("too-fast"), RATE("rate"), EMPTY("empty"), JUNK("junk"), ADVERTISING("advertising"),
        DUPLICATE("duplicate"), SIMILAR("similar"), WAVE("wave"), COMMANDS("commands");

        public final String key;

        Reason(String key) {
            this.key = key;
        }
    }

    /**
     * Outcome of one message or command.
     *
     * @param text the cleaned message to show (when allowed)
     * @param detail what matched (the advertised domain or IP)
     * @param tell tell the player why (throttled so a macro doesn't flood their own chat)
     * @param warn heat is high: warn that a mute is coming
     * @param mutedMillis this message started a mute of this length (0 = no new mute)
     * @param muteLeftMillis the player is muted for this much longer
     */
    public record Result(boolean allowed, String text, @Nullable Reason reason, @Nullable String detail, boolean tell,
                         boolean warn, long mutedMillis, long muteLeftMillis) {

        static Result ok(String text) {
            return new Result(true, text, null, null, false, false, 0, 0);
        }
    }

    /** What {@code /duelcore antispam status} shows. */
    public record Status(double heat, int strikes, long muteLeftMillis, int recentMessages) {
    }

    /** chat.anti-spam in config.yml. */
    public record Settings(boolean enabled,
                           boolean rateEnabled, int maxMessages, long windowMs, long minDelayMs,
                           boolean dupEnabled, long dupWindowMs, int history, double similarity, int similarAllowed,
                           int minSimilarLength, long lenientMs, Set<String> lenient,
                           boolean floodEnabled, boolean stripUnicode, int maxRepeat, double capsRatio, int capsMinLetters,
                           int maxMarks, int junkMinLength, double junkMaxAlnum,
                           boolean adsEnabled, List<String> whitelist,
                           boolean wavesEnabled, int wavePlayers, long waveWindowMs, int waveMinLength,
                           boolean escalation, double warnHeat, double muteHeat, double decayPerMs, List<Long> muteDurations,
                           long strikeResetMs, Map<Reason, Double> weights,
                           boolean commandsEnabled, int maxCommands, long commandWindowMs) {

        public static final List<String> DEFAULT_LENIENT = List.of("gg", "gg wp", "ggwp", "gf", "wp", "ez", "lol", "lmao",
            "xd", "ty", "thx", "np", "gl", "glhf", "rematch", "1v1", "2v2", "again", "nice", "rip", "ok", "yes", "no",
            "yeah", "nah", "hi", "hey", "hello", "bye", "gn", "o7", "bruh", "same", "what", "why", "lag", "wow", "omg");
        public static final List<String> DEFAULT_WHITELIST = List.of("cheesesmp.top", "pvp.cheesesmp.top",
            "discord.gg/cheesesmp");

        public static Settings defaults() {
            return from(null);
        }

        public static Settings from(@Nullable ConfigurationSection section) {
            ConfigurationSection c = section != null ? section : new MemoryConfiguration();
            Set<String> lenient = new HashSet<>();
            List<String> phrases = c.isList("duplicates.lenient-phrases") ? c.getStringList("duplicates.lenient-phrases")
                : DEFAULT_LENIENT;
            for (String p : phrases) {
                String key = Normalized.of(p).key();
                if (!key.isEmpty()) lenient.add(key);
            }
            List<String> white = c.isList("advertising.whitelist") ? c.getStringList("advertising.whitelist")
                : DEFAULT_WHITELIST;
            List<Long> durations = new ArrayList<>();
            List<String> rawDurations = c.isList("escalation.mute-durations") ? c.getStringList("escalation.mute-durations")
                : List.of("30s", "2m", "10m");
            for (String d : rawDurations) {
                long ms = parseDuration(d);
                if (ms > 0) durations.add(ms);
            }
            if (durations.isEmpty()) durations = List.of(30_000L, 120_000L, 600_000L);
            Map<Reason, Double> weights = new EnumMap<>(Reason.class);
            weights.put(Reason.TOO_FAST, w(c, "too-fast", 1));
            weights.put(Reason.RATE, w(c, "rate", 1));
            weights.put(Reason.EMPTY, w(c, "empty", 1));
            weights.put(Reason.JUNK, w(c, "junk", 1));
            weights.put(Reason.ADVERTISING, w(c, "advertising", 4));
            weights.put(Reason.DUPLICATE, w(c, "duplicate", 1));
            weights.put(Reason.SIMILAR, w(c, "similar", 1));
            weights.put(Reason.WAVE, w(c, "wave", 0.5));
            weights.put(Reason.COMMANDS, w(c, "commands", 0));
            double muteHeat = Math.max(1, c.getDouble("escalation.mute-heat", 6));
            return new Settings(c.getBoolean("enabled", true),
                c.getBoolean("rate-limit.enabled", true),
                Math.clamp(c.getInt("rate-limit.max-messages", 4), 1, 50),
                secs(c.getDouble("rate-limit.window-seconds", 5), 0.5, 120),
                Math.clamp(c.getLong("rate-limit.min-delay-ms", 600), 0, 10_000),
                c.getBoolean("duplicates.enabled", true),
                secs(c.getDouble("duplicates.window-seconds", 30), 1, 600),
                Math.clamp(c.getInt("duplicates.history", 5), 1, 20),
                Math.clamp(c.getDouble("duplicates.similarity", 0.85), 0.5, 1.0),
                Math.clamp(c.getInt("duplicates.similar-allowed", 1), 0, 10),
                Math.clamp(c.getInt("duplicates.min-similar-length", 6), 2, 100),
                secs(c.getDouble("duplicates.lenient-seconds", 3), 0, 600),
                Set.copyOf(lenient),
                c.getBoolean("flood.enabled", true),
                c.getBoolean("flood.strip-unicode", true),
                Math.clamp(c.getInt("flood.max-repeat", 4), 0, 50),
                Math.clamp(c.getDouble("flood.caps-percent", 70), 0, 100) / 100.0,
                Math.clamp(c.getInt("flood.caps-min-letters", 8), 1, 256),
                Math.clamp(c.getInt("flood.max-marks", 2), 0, 8),
                Math.clamp(c.getInt("flood.junk-min-length", 16), 0, 256),
                Math.clamp(c.getDouble("flood.junk-max-alnum-percent", 25), 0, 100) / 100.0,
                c.getBoolean("advertising.enabled", true),
                List.copyOf(white),
                c.getBoolean("waves.enabled", true),
                Math.clamp(c.getInt("waves.players", 3), 2, 100),
                secs(c.getDouble("waves.window-seconds", 10), 1, 300),
                Math.clamp(c.getInt("waves.min-length", 12), 1, 256),
                c.getBoolean("escalation.enabled", true),
                Math.clamp(c.getDouble("escalation.warn-heat", 3), 0, muteHeat),
                muteHeat,
                Math.max(0, c.getDouble("escalation.decay-per-minute", 6)) / 60_000.0,
                List.copyOf(durations),
                (long) (Math.max(1, c.getDouble("escalation.strike-reset-minutes", 30)) * 60_000),
                weights,
                c.getBoolean("commands.enabled", true),
                Math.clamp(c.getInt("commands.max-commands", 8), 1, 100),
                secs(c.getDouble("commands.window-seconds", 3), 0.5, 120));
        }

        private static double w(ConfigurationSection c, String key, double def) {
            return Math.clamp(c.getDouble("escalation.heat." + key, def), 0, 100);
        }

        private static long secs(double value, double min, double max) {
            return (long) (Math.clamp(value, min, max) * 1000);
        }
    }

    /** "30s", "2m", "1h", "90" (seconds); 0 when it can't be read. */
    static long parseDuration(@Nullable String raw) {
        if (raw == null) return 0;
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return 0;
        long unit = 1000;
        char last = s.charAt(s.length() - 1);
        if (Character.isLetter(last)) {
            unit = switch (last) {
                case 's' -> 1000;
                case 'm' -> 60_000;
                case 'h' -> 3_600_000;
                case 'd' -> 86_400_000;
                default -> -1;
            };
            s = s.substring(0, s.length() - 1).strip();
        }
        if (unit < 0) return 0;
        try {
            double v = Double.parseDouble(s);
            return v > 0 && v < 1e7 ? (long) (v * unit) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** "45s", "2m", "1m 30s", "1h 5m". */
    public static String formatDuration(long ms) {
        long s = Math.max(1, (ms + 999) / 1000);
        if (s < 60) return s + "s";
        long m = s / 60;
        s %= 60;
        if (m < 60) return s == 0 ? m + "m" : m + "m " + s + "s";
        long h = m / 60;
        m %= 60;
        return m == 0 ? h + "h" : h + "h " + m + "m";
    }

    /** Longest text looked at (chat lines are at most 256 characters; commands are cut here too). */
    static final int MAX_INPUT = 512;
    /** Most entries in the cross-player wave list (oldest dropped first). */
    private static final int MAX_WAVE = 256;
    /** Player state not touched for this long (and not muted or on a strike) is dropped by {@link #cleanup()}. */
    private static final long IDLE_MS = 10 * 60_000L;
    /** At most one "why" notice per player per this many ms. */
    private static final long NOTICE_MS = 1000;

    private record Entry(String channel, Normalized norm, long at) {
    }

    private record WaveEntry(UUID player, Normalized norm, long at) {
    }

    private static final class State {
        final ArrayDeque<Long> sent = new ArrayDeque<>();
        final ArrayDeque<Long> commands = new ArrayDeque<>();
        final ArrayDeque<Entry> history = new ArrayDeque<>();
        long lastSent = Long.MIN_VALUE / 4;
        double heat;
        long heatAt;
        int strikes;
        long lastMuteAt = Long.MIN_VALUE / 4;
        long mutedUntil = Long.MIN_VALUE / 4;
        long lastNotice = Long.MIN_VALUE / 4;
        long lastSeen;
        /** Dropped by cleanup: whoever still holds it must look the player up again. */
        boolean removed;
    }

    private final Supplier<Settings> settings;
    private final LongSupplier clock;
    private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();
    private final ArrayDeque<WaveEntry> wave = new ArrayDeque<>();

    /**
     * @param settings read on every message, so a reload applies at once (state is kept)
     * @param clock milliseconds
     */
    public AntiSpam(Supplier<Settings> settings, LongSupplier clock) {
        this.settings = settings;
        this.clock = clock;
    }

    private State state(UUID id) {
        return states.computeIfAbsent(id, k -> new State());
    }

    // ------------------------------------------------------------------------------------------------------------
    // checks

    /**
     * Checks one chat line or private message. {@code channel} separates duplicate histories ("chat" for public chat,
     * "pm:name" per recipient, ...); the rate limit, mutes and heat are shared by all channels.
     */
    public Result chat(UUID player, String message, String channel) {
        Settings s = settings.get();
        if (!s.enabled()) return Result.ok(message);
        String raw = message.length() > MAX_INPUT ? message.substring(0, MAX_INPUT) : message;
        long now = clock.getAsLong();
        return locked(player, st -> check(st, s, player, raw, message, channel, now));
    }

    /** Runs {@code body} holding the player's lock, on a state that {@link #cleanup()} hasn't dropped meanwhile. */
    private <R> R locked(UUID player, Function<State, R> body) {
        while (true) {
            State st = state(player);
            synchronized (st) {
                if (!st.removed) return body.apply(st);
            }
        }
    }

    /** Caller holds the player's lock; the wave list lock is only ever taken inside it (one lock order, no deadlock). */
    private Result check(State st, Settings s, UUID player, String raw, String message, String channel, long now) {
        st.lastSeen = now;
        if (now < st.mutedUntil) {
            return new Result(false, message, Reason.MUTED, null, notice(st, now), false, 0, st.mutedUntil - now);
        }
        String cleaned = s.floodEnabled() ? clean(raw, s) : raw;
        if (cleaned.isBlank()) return violation(st, s, now, Reason.EMPTY, null, message);
        if (s.rateEnabled()) {
            if (now - st.lastSent < s.minDelayMs()) return violation(st, s, now, Reason.TOO_FAST, null, message);
            trim(st.sent, now - s.windowMs());
            if (st.sent.size() >= s.maxMessages()) return violation(st, s, now, Reason.RATE, null, message);
        }
        if (s.adsEnabled()) {
            String ad = advert(cleaned, s.whitelist());
            if (ad != null) return violation(st, s, now, Reason.ADVERTISING, ad, message);
        }
        if (s.floodEnabled() && junk(cleaned, s)) return violation(st, s, now, Reason.JUNK, null, message);
        Normalized norm = Normalized.of(cleaned);
        if (s.dupEnabled()) {
            Reason dup = duplicate(st, s, norm, channel, now);
            if (dup != null) return violation(st, s, now, dup, null, message);
        }
        if (s.wavesEnabled() && !lenient(norm, s) && norm.key().length() >= s.waveMinLength()) {
            synchronized (wave) {
                while (!wave.isEmpty() && wave.peekFirst().at() < now - s.waveWindowMs()) wave.pollFirst();
                Set<UUID> others = new HashSet<>();
                for (WaveEntry e : wave) {
                    if (!e.player().equals(player) && similar(e.norm(), norm, s.similarity())) others.add(e.player());
                }
                if (others.size() >= s.wavePlayers()) return violation(st, s, now, Reason.WAVE, null, message);
                wave.addLast(new WaveEntry(player, norm, now));
                while (wave.size() > MAX_WAVE) wave.pollFirst();
            }
        }
        st.lastSent = now;
        st.sent.addLast(now);
        while (st.sent.size() > s.maxMessages()) st.sent.pollFirst();
        st.history.addLast(new Entry(channel, norm, now));
        while (st.history.size() > s.history()) st.history.pollFirst();
        return Result.ok(cleaned);
    }

    /** Command flood check: every command a player runs goes through here. */
    public Result command(UUID player) {
        Settings s = settings.get();
        if (!s.enabled() || !s.commandsEnabled()) return Result.ok("");
        long now = clock.getAsLong();
        return locked(player, st -> {
            st.lastSeen = now;
            trim(st.commands, now - s.commandWindowMs());
            if (st.commands.size() >= s.maxCommands()) return violation(st, s, now, Reason.COMMANDS, null, "");
            st.commands.addLast(now);
            while (st.commands.size() > s.maxCommands()) st.commands.pollFirst();
            return Result.ok("");
        });
    }

    private static void trim(ArrayDeque<Long> times, long before) {
        while (!times.isEmpty() && times.peekFirst() <= before) times.pollFirst();
    }

    private boolean notice(State st, long now) {
        if (now - st.lastNotice < NOTICE_MS) return false;
        st.lastNotice = now;
        return true;
    }

    /** Adds heat for a blocked message and starts a mute when it's too high. Caller holds the player's lock. */
    private Result violation(State st, Settings s, long now, Reason reason, @Nullable String detail, String message) {
        if (!s.escalation()) return new Result(false, message, reason, detail, notice(st, now), false, 0, 0);
        double heat = decayed(st, s, now) + s.weights().getOrDefault(reason, 1.0);
        st.heat = heat;
        st.heatAt = now;
        if (heat >= s.muteHeat()) {
            if (now - st.lastMuteAt > s.strikeResetMs()) st.strikes = 0;
            st.strikes++;
            long length = s.muteDurations().get(Math.min(st.strikes, s.muteDurations().size()) - 1);
            st.mutedUntil = now + length;
            st.lastMuteAt = now;
            st.heat = 0;
            st.lastNotice = now;
            return new Result(false, message, reason, detail, true, false, length, length);
        }
        return new Result(false, message, reason, detail, notice(st, now), heat >= s.warnHeat(), 0, 0);
    }

    private static double decayed(State st, Settings s, long now) {
        return Math.max(0, st.heat - Math.max(0, now - st.heatAt) * s.decayPerMs());
    }

    private static boolean lenient(Normalized n, Settings s) {
        return n.key().length() <= 2 || s.lenient().contains(n.key());
    }

    private static @Nullable Reason duplicate(State st, Settings s, Normalized norm, String channel, long now) {
        boolean lenient = lenient(norm, s);
        int similar = 0;
        for (Entry e : st.history) {
            if (e.at() < now - s.dupWindowMs() || !e.channel().equals(channel)) continue;
            if (e.norm().key().equals(norm.key()) || (!norm.sorted().isEmpty() && e.norm().sorted().equals(norm.sorted()))) {
                if (lenient && now - e.at() >= s.lenientMs()) continue;
                return Reason.DUPLICATE;
            }
            if (!lenient && norm.key().length() >= s.minSimilarLength() && e.norm().key().length() >= s.minSimilarLength()
                && similar(e.norm(), norm, s.similarity())) {
                similar++;
            }
        }
        return similar > s.similarAllowed() ? Reason.SIMILAR : null;
    }

    // ------------------------------------------------------------------------------------------------------------
    // admin

    public Status status(UUID player) {
        Settings s = settings.get();
        State st = states.get(player);
        if (st == null) return new Status(0, 0, 0, 0);
        long now = clock.getAsLong();
        synchronized (st) {
            int strikes = now - st.lastMuteAt > s.strikeResetMs() ? 0 : st.strikes;
            trim(st.sent, now - s.windowMs());
            return new Status(decayed(st, s, now), strikes, Math.max(0, st.mutedUntil - now), st.sent.size());
        }
    }

    /** Lifts a mute and forgives heat and strikes. Returns whether the player was muted. */
    public boolean unmute(UUID player) {
        State st = states.get(player);
        if (st == null) return false;
        long now = clock.getAsLong();
        synchronized (st) {
            boolean was = now < st.mutedUntil;
            st.mutedUntil = Long.MIN_VALUE / 4;
            st.heat = 0;
            st.strikes = 0;
            st.lastMuteAt = Long.MIN_VALUE / 4;
            return was;
        }
    }

    /** Drops state of players idle for a while (mutes and live strikes are kept, so a rejoin doesn't reset them). */
    public void cleanup() {
        Settings s = settings.get();
        long now = clock.getAsLong();
        states.entrySet().removeIf(e -> {
            State st = e.getValue();
            synchronized (st) {
                st.removed = now - st.lastSeen > IDLE_MS && now >= st.mutedUntil && now - st.lastMuteAt > s.strikeResetMs();
                return st.removed;
            }
        });
        synchronized (wave) {
            while (!wave.isEmpty() && wave.peekFirst().at() < now - s.waveWindowMs()) wave.pollFirst();
        }
    }

    int trackedPlayers() {
        return states.size();
    }

    // ------------------------------------------------------------------------------------------------------------
    // clean-up of the visible text

    /** Invisible fillers that aren't in a format category (Hangul fillers, braille blank, ...). */
    private static boolean fillerSpace(int cp) {
        return cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 || cp == 0x2800 || cp == 0x00A0
            || (cp >= 0x2000 && cp <= 0x200A) || cp == 0x202F || cp == 0x205F || cp == 0x3000 || cp == 0x1680;
    }

    /** Characters that are dropped outright: format/bidi controls, zero-width, private use, unassigned, lone surrogates. */
    private static boolean invisible(int cp) {
        int t = Character.getType(cp);
        return t == Character.FORMAT || t == Character.CONTROL || t == Character.PRIVATE_USE || t == Character.UNASSIGNED
            || t == Character.SURROGATE || t == Character.ENCLOSING_MARK
            || cp == 0x034F || cp == 0x17B4 || cp == 0x17B5 || (cp >= 0xFE00 && cp <= 0xFE0F)
            || (cp >= 0xE0100 && cp <= 0xE01EF);
    }

    /** The text others see: unicode abuse stripped, floods shortened, shouting lowercased. */
    static String clean(String text, Settings s) {
        String t = Normalizer.normalize(text, Normalizer.Form.NFC);
        if (s.stripUnicode()) t = stripUnicode(t, s.maxMarks());
        else t = t.replaceAll("\\s+", " ").strip();
        if (s.maxRepeat() > 0) t = collapseRepeats(t, s.maxRepeat());
        if (s.capsRatio() > 0 && s.capsRatio() < 1) t = unshout(t, s.capsRatio(), s.capsMinLetters());
        return t;
    }

    static String stripUnicode(String text, int maxMarks) {
        StringBuilder out = new StringBuilder(text.length());
        int marks = 0;
        boolean space = true; // drops leading spaces and collapses runs
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (invisible(cp)) continue;
            if (Character.isWhitespace(cp) || fillerSpace(cp) || Character.isSpaceChar(cp)) {
                if (!space) out.append(' ');
                space = true;
                marks = 0;
                continue;
            }
            if (Character.getType(cp) == Character.NON_SPACING_MARK) {
                if (space || ++marks > maxMarks) continue; // zalgo: only a couple of marks per letter
                out.appendCodePoint(cp);
                continue;
            }
            marks = 0;
            space = false;
            out.appendCodePoint(cp);
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == ' ') end--;
        out.setLength(end);
        return out.toString();
    }

    /** Runs of the same character or unit of up to 4 ("hahaha") longer than {@code max} are cut to {@code max}. */
    static String collapseRepeats(String text, int max) {
        int[] cps = text.codePoints().toArray();
        int n = cps.length;
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        outer:
        while (i < n) {
            for (int len = 1; len <= 4 && i + len * (max + 1) <= n; len++) {
                if (!repeatable(cps, i, len)) continue;
                int reps = 1;
                while (i + (reps + 1) * len <= n && Arrays.equals(cps, i, i + len, cps, i + reps * len, i + (reps + 1) * len)) {
                    reps++;
                }
                if (reps > max) {
                    for (int r = 0; r < max; r++) for (int k = 0; k < len; k++) out.appendCodePoint(cps[i + k]);
                    i += reps * len;
                    continue outer;
                }
            }
            out.appendCodePoint(cps[i++]);
        }
        return out.toString();
    }

    /** Numbers are never shortened; units of one repeated character are handled with length 1. */
    private static boolean repeatable(int[] cps, int from, int len) {
        boolean allSame = true;
        boolean allSpace = true;
        for (int k = 0; k < len; k++) {
            int cp = cps[from + k];
            if (Character.isDigit(cp)) return false;
            if (cp != ' ') allSpace = false;
            if (cp != cps[from]) allSame = false;
        }
        return !allSpace && (len == 1 || !allSame);
    }

    static String unshout(String text, double ratio, int minLetters) {
        int upper = 0;
        int letters = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isUpperCase(cp) || Character.isTitleCase(cp)) {
                upper++;
                letters++;
            } else if (Character.isLowerCase(cp)) {
                letters++;
            }
        }
        return letters >= minLetters && upper > ratio * letters ? text.toLowerCase(Locale.ROOT) : text;
    }

    static boolean junk(String text, Settings s) {
        if (s.junkMinLength() <= 0) return false;
        int total = 0;
        int alnum = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == ' ') continue;
            total++;
            if (Character.isLetterOrDigit(cp)) alnum++;
        }
        return total >= s.junkMinLength() && alnum < s.junkMaxAlnum() * total;
    }

    // ------------------------------------------------------------------------------------------------------------
    // normalization for comparing messages

    /** Leetspeak for comparing messages (not for display). */
    private static char leet(char c) {
        return switch (c) {
            case '0' -> 'o';
            case '1', '!', '|' -> 'i';
            case '3' -> 'e';
            case '4', '@' -> 'a';
            case '5', '$' -> 's';
            case '7', '+' -> 't';
            case '8' -> 'b';
            default -> c;
        };
    }

    /** Symbols only stand for letters inside a word ("fr!ends", "$hop"), not as punctuation ("stuff!!!"). */
    private static boolean leetSymbol(char c) {
        return c == '!' || c == '|' || c == '@' || c == '$' || c == '+';
    }

    /** Folds one code point to a lowercase latin-ish char: accents, fullwidth, look-alike letters from other scripts. */
    static char fold(int cp) {
        int lower = Character.toLowerCase(cp);
        if (lower < 128) return (char) lower;
        if (lower <= 0xFFFF) {
            Character g = EXTRA_GLYPHS.get((char) lower);
            if (g == null) g = ChatFilter.HOMOGLYPHS.get((char) lower);
            if (g != null) return g;
        }
        String folded = Normalizer.normalize(new String(Character.toChars(lower)), Normalizer.Form.NFKD);
        for (int i = 0; i < folded.length(); i++) {
            char c = Character.toLowerCase(folded.charAt(i));
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            Character g = ChatFilter.HOMOGLYPHS.get(c);
            if (g == null) g = EXTRA_GLYPHS.get(c);
            return g != null ? g : c;
        }
        return lower <= 0xFFFF ? (char) lower : ' ';
    }

    private static final Map<Character, Character> EXTRA_GLYPHS = Map.ofEntries(
        Map.entry('һ', 'h'), Map.entry('ԝ', 'w'), Map.entry('ɑ', 'a'), Map.entry('ʏ', 'y'), Map.entry('ɩ', 'i'),
        Map.entry('ɡ', 'g'), Map.entry('ӏ', 'l'), Map.entry('ⅼ', 'l'), Map.entry('ı', 'i'), Map.entry('ɒ', 'a'),
        Map.entry('з', '3'), Map.entry('ч', '4'), Map.entry('б', '6'), Map.entry('п', 'n'), Map.entry('л', 'n'),
        Map.entry('и', 'n'), Map.entry('ш', 'w'), Map.entry('щ', 'w'), Map.entry('ь', 'b'), Map.entry('д', 'a'),
        Map.entry('ʟ', 'l'), Map.entry('ꜱ', 's'), Map.entry('ᴅ', 'd'), Map.entry('ᴄ', 'c'), Map.entry('ʜ', 'h'),
        Map.entry('ᴍ', 'm'), Map.entry('ᴘ', 'p'), Map.entry('ᴡ', 'w'), Map.entry('ꜰ', 'f'), Map.entry('ᴠ', 'v'),
        Map.entry('ᴢ', 'z'), Map.entry('ʙ', 'b'), Map.entry('ᴊ', 'j'));

    /**
     * A message reduced for comparing: folded, leetspeak mapped, only letters and digits, repeated characters
     * collapsed. {@code key} has no spaces ("h e l l o" = "hello"), {@code sorted} is the words in order (so
     * reordering words doesn't dodge the check).
     */
    record Normalized(String key, String sorted) {

        static Normalized of(String text) {
            StringBuilder folded = new StringBuilder(Math.min(text.length(), MAX_INPUT));
            int count = 0;
            for (int i = 0; i < text.length() && count < MAX_INPUT; ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                count++;
                if (invisible(cp) || Character.getType(cp) == Character.NON_SPACING_MARK) continue;
                folded.append(fold(cp));
            }
            StringBuilder b = new StringBuilder(folded.length());
            char prev = 0;
            for (int i = 0; i < folded.length(); i++) {
                char f = folded.charAt(i);
                char c;
                if (leetSymbol(f)) {
                    boolean before = i > 0 && Character.isLetterOrDigit(folded.charAt(i - 1));
                    boolean after = i + 1 < folded.length() && Character.isLetterOrDigit(folded.charAt(i + 1));
                    boolean inWord = after && (before || f == '$' || f == '@');
                    c = inWord ? leet(f) : ' ';
                } else {
                    c = leet(f);
                }
                if (!Character.isLetterOrDigit(c)) c = ' ';
                if (c == prev) continue; // "heyyyy" = "hey", and runs of separators
                b.append(c);
                prev = c;
            }
            String words = b.toString().strip();
            String key = squeeze(words.replace(" ", "")); // "f r e e" = "free" = "fre"

            if (key.isEmpty()) {
                // only symbols or emoji: compare what's there, without spaces or repeats
                StringBuilder sym = new StringBuilder();
                int last = -1;
                for (int i = 0; i < text.length() && sym.length() < MAX_INPUT; ) {
                    int cp = text.codePointAt(i);
                    i += Character.charCount(cp);
                    if (Character.isWhitespace(cp) || invisible(cp) || cp == last) continue;
                    sym.appendCodePoint(cp);
                    last = cp;
                }
                return new Normalized(sym.isEmpty() ? "" : "#" + sym, "");
            }
            String[] parts = words.split(" ");
            for (int i = 0; i < parts.length; i++) parts[i] = squeeze(parts[i]);
            String sorted = "";
            if (parts.length >= 3) {
                Arrays.sort(parts);
                sorted = String.join("", parts);
            }
            return new Normalized(key, sorted);
        }
    }

    /** Collapses runs of the same character: "freee" = "fre". */
    private static String squeeze(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (b.isEmpty() || b.charAt(b.length() - 1) != c) b.append(c);
        }
        return b.toString();
    }

    static boolean similar(Normalized a, Normalized b, double threshold) {
        if (a.key().equals(b.key())) return true;
        if (similarity(a.key(), b.key(), threshold) >= threshold) return true;
        return !a.sorted().isEmpty() && !b.sorted().isEmpty() && similarity(a.sorted(), b.sorted(), threshold) >= threshold;
    }

    /**
     * 1 - edit distance / longer length, or 0 when below {@code threshold} (the banded distance gives up early). A
     * message that contains another long one with only a little added ("buy now" + " pls pls") counts as similar too.
     */
    static double similarity(String a, String b, double threshold) {
        if (a.equals(b)) return 1;
        int max = Math.max(a.length(), b.length());
        int min = Math.min(a.length(), b.length());
        if (max == 0) return 1;
        if (min >= 8 && min >= 0.6 * max && (a.length() > b.length() ? a.contains(b) : b.contains(a))) {
            return Math.max(threshold, (double) min / max);
        }
        int limit = (int) Math.floor((1 - threshold) * max);
        int d = levenshtein(a, b, limit);
        return d > limit ? 0 : 1 - (double) d / max;
    }

    /** Edit distance, or {@code limit + 1} as soon as it's known to be above {@code limit}. O(n * m) on short input. */
    static int levenshtein(CharSequence a, CharSequence b, int limit) {
        int n = a.length();
        int m = b.length();
        int big = limit + 1;
        if (Math.abs(n - m) > limit) return big;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = Math.min(j, big);
        for (int i = 1; i <= n; i++) {
            Arrays.fill(cur, big);
            cur[0] = Math.min(i, big);
            int rowMin = cur[0];
            int from = Math.max(1, i - limit);
            int to = Math.min(m, i + limit);
            char ca = a.charAt(i - 1);
            for (int j = from; j <= to; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                cur[j] = Math.min(v, big);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > limit) return big;
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return Math.min(prev[m], big);
    }

    // ------------------------------------------------------------------------------------------------------------
    // advertising

    /** TLDs servers and link spammers use. Short English words that are TLDs (it, is, to, in, so, my, no, ...) aren't here. */
    private static final String TLDS = "com|net|org|gg|io|co|me|xyz|club|top|pro|fun|eu|tk|ml|ga|cf|gq|online|site|store"
        + "|shop|tv|cc|biz|info|host|space|live|world|ru|de|uk|fr|nl|pl|br|ca|au|cz|sk|dev|app|games|network|ly|ws"
        + "|link|icu|vip|one|click|land|fyi|su|es|lt|lv|ro|hu|se|fi|dk|gs|vg|nu|pw|mx|ar|tr|ua|by|kz|jp|kr|cn|tw|asia"
        + "|rip|lol|wtf|win|today|plus|zone|studio|cloud|gold|run|red|blue|pink|best|codes|mc|craft|minecraft|party"
        + "|world|city|team|tech|page|ovh|nz|in";
    private static final Pattern DOMAIN = Pattern.compile(
        "(?<![a-z0-9])((?:[a-z0-9]{1,63}\\.){1,8}(" + TLDS + "))(?![a-z0-9])");
    private static final Pattern INVITE = Pattern.compile(
        "(?<![a-z0-9])(discord(?:app)?\\s?\\.?\\s?(?:gg|com|io|me|li|link)|dsc\\.gg|discord\\.gg)\\s?/\\s?(?:invite\\s?/\\s?)?([a-z0-9]{2,32})");
    private static final Pattern IP = Pattern.compile("(?<![0-9])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?![0-9])");
    private static final Pattern IP_LOOSE = Pattern.compile("(?<![0-9a-z])([0-9oli]{1,3})\\.([0-9oli]{1,3})\\.([0-9oli]{1,3})\\.([0-9oli]{1,3})(?![0-9a-z])");
    /** Whole words that aren't an ad in front of .gg ("nice.gg" is someone saying gg). */
    private static final Set<String> GG_WORDS = Set.of("nice", "good", "well", "lol", "lmao", "thanks", "thx", "cya", "bye",
        "again", "all", "guys", "man", "bro", "xd", "ok", "okay", "yes", "yeah", "close", "wow", "damn", "gg", "ggs", "ez",
        "wp", "gf", "ty", "np", "gl", "rip", "haha", "great", "fun", "sure");

    /** The two views of the text being checked, always the same length so a match in one maps onto the other. */
    private static final class Twin {
        String plain;
        String leet;

        Twin(String plain, String leet) {
            this.plain = plain;
            this.leet = leet;
        }

        /** Replaces every match of {@code p} in the leet view (and the same range of the plain view). */
        void replace(Pattern p, String with) {
            Matcher m = p.matcher(leet);
            StringBuilder a = null;
            StringBuilder b = null;
            int last = 0;
            while (m.find()) {
                if (a == null) {
                    a = new StringBuilder(plain.length());
                    b = new StringBuilder(leet.length());
                }
                a.append(plain, last, m.start()).append(with);
                b.append(leet, last, m.start()).append(with);
                last = m.end();
            }
            if (a == null) return;
            a.append(plain, last, plain.length());
            b.append(leet, last, leet.length());
            plain = a.toString();
            leet = b.toString();
        }
    }

    private static final Pattern NOISE = Pattern.compile("[-_*~^'\"`=\\\\]+");
    private static final Pattern SPACES = Pattern.compile(" {2,}");
    private static final Pattern BRACKET_DOT = Pattern.compile(" ?[(\\[{<] ?(?:\\.|dot|d0t|punto|point) ?[)\\]}>] ?");
    private static final Pattern WORD_DOT = Pattern.compile(" (?:dot|punto) ");
    private static final Pattern GLUED_DOT = Pattern.compile("(?<=[a-z0-9]) ?dot(?=(?:" + TLDS + ")(?![a-z0-9]))");
    private static final Pattern SPACE_BEFORE_DOT = Pattern.compile(" \\. ?");
    private static final Pattern SPACE_AFTER_DOT = Pattern.compile(
        "(?<=[a-z0-9])\\. (?=(?:com|net|org|xyz|club|online|site|store|shop|fun|pro)(?![a-z0-9]))");
    private static final Pattern COMMA_DOT = Pattern.compile(",(?=(?:com|net|org|xyz)(?![a-z0-9]))");
    private static final Pattern DOTS = Pattern.compile("\\.{2,}(?=[a-z0-9])");

    /** The advertised domain, invite or IP in {@code text}, or null. Whitelisted ones are allowed. */
    static @Nullable String advert(String text, List<String> whitelist) {
        Twin t = adText(text);
        // Discord invites: allowed only when the exact invite is whitelisted
        Matcher inv = INVITE.matcher(t.leet);
        while (inv.find()) {
            String code = t.plain.substring(inv.start(2), inv.end(2));
            if (!whitelistedInvite(code, whitelist)) return t.plain.substring(inv.start(), inv.end());
        }
        Matcher m = DOMAIN.matcher(t.leet);
        while (m.find()) {
            String domain = t.plain.substring(m.start(1), m.end(1));
            String tld = m.group(2);
            if (tld.equals("gg")) {
                String rest = t.leet.substring(m.start(1), m.end(1) - 3);
                if (rest.length() < 3 || GG_WORDS.contains(rest)) continue;
            }
            if (!whitelistedDomain(domain, whitelist)) return domain;
        }
        // IPs, also with the spaces taken out ("1 2 3 . 4 5 . 6 7 . 8 9") and with o/l/i for 0/1
        String compact = t.plain.replace(" ", "");
        Matcher ip = IP.matcher(compact);
        while (ip.find()) {
            if (validIp(ip) && !whitelist.contains(ip.group())) return ip.group();
        }
        Matcher loose = IP_LOOSE.matcher(compact);
        while (loose.find()) {
            String g = loose.group();
            int digits = 0;
            for (int i = 0; i < g.length(); i++) if (Character.isDigit(g.charAt(i))) digits++;
            if (digits * 2 < g.length() - 3) continue; // "lol.lol.io.oi" is not an IP
            String fixed = g.replace('o', '0').replace('l', '1').replace('i', '1');
            Matcher again = IP.matcher(fixed);
            if (again.matches() && validIp(again) && !whitelist.contains(fixed)) return fixed;
        }
        return null;
    }

    private static boolean validIp(Matcher ip) {
        for (int g = 1; g <= 4; g++) if (Integer.parseInt(ip.group(g)) > 255) return false;
        return true;
    }

    /** Builds the plain (folded, lowercase) and leet views of a message, with dot tricks turned into real dots. */
    private static Twin adText(String text) {
        StringBuilder plain = new StringBuilder(text.length());
        int count = 0;
        for (int i = 0; i < text.length() && count < MAX_INPUT; ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            count++;
            if (invisible(cp) || Character.getType(cp) == Character.NON_SPACING_MARK) continue;
            char c;
            if (cp == '。' || cp == '｡' || cp == '․' || cp == '‧' || cp == '·' || cp == '∙' || cp == '⋅' || cp == '•'
                || cp == '●' || cp == '・' || cp == '﹒' || cp == '．') {
                c = '.';
            } else if (Character.isWhitespace(cp) || fillerSpace(cp) || Character.isSpaceChar(cp)) {
                c = ' ';
            } else {
                c = fold(cp);
            }
            plain.append(c);
        }
        String p = plain.toString();
        StringBuilder leet = new StringBuilder(p.length());
        for (int i = 0; i < p.length(); i++) leet.append(leet(p.charAt(i)));
        Twin t = new Twin(p, leet.toString());
        t.replace(NOISE, "");
        t.replace(SPACES, " ");
        joinSpacedLetters(t);
        t.replace(BRACKET_DOT, ".");
        t.replace(WORD_DOT, ".");
        t.replace(GLUED_DOT, ".");
        t.replace(SPACE_BEFORE_DOT, ".");
        t.replace(SPACE_AFTER_DOT, ".");
        t.replace(COMMA_DOT, ".");
        t.replace(DOTS, ".");
        return t;
    }

    /** "e x a m p l e . c o m" → "example.com": runs of 3+ one-character words are joined. */
    private static void joinSpacedLetters(Twin t) {
        String[] plainWords = t.plain.split(" ", -1);
        String[] leetWords = t.leet.split(" ", -1);
        if (plainWords.length != leetWords.length || plainWords.length < 3) return;
        StringBuilder a = new StringBuilder(t.plain.length());
        StringBuilder b = new StringBuilder(t.leet.length());
        int i = 0;
        while (i < plainWords.length) {
            int j = i;
            while (j < plainWords.length && tiny(leetWords[j])) j++;
            // "evil.c o m", "evil. c o m": the run belongs to the word before it
            boolean glue = i > 0 && j - i >= 2 && GLUE_TO.matcher(leetWords[i - 1]).find();
            if (j - i >= 3 || glue) {
                if (i > 0 && !glue) {
                    a.append(' ');
                    b.append(' ');
                }
                for (int k = i; k < j; k++) {
                    a.append(plainWords[k]);
                    b.append(leetWords[k]);
                }
                i = j;
                continue;
            }
            if (i > 0) {
                a.append(' ');
                b.append(' ');
            }
            a.append(plainWords[i]);
            b.append(leetWords[i]);
            i++;
        }
        t.plain = a.toString();
        t.leet = b.toString();
    }

    private static final Pattern GLUE_TO = Pattern.compile("[a-z0-9]\\.[a-z0-9]?$");

    private static boolean tiny(String word) {
        if (word.isEmpty() || word.length() > 2) return false;
        int alnum = 0;
        for (int i = 0; i < word.length(); i++) if (Character.isLetterOrDigit(word.charAt(i))) alnum++;
        return alnum <= 1;
    }

    private static String canonical(String entry) {
        StringBuilder b = new StringBuilder();
        String e = entry.strip().toLowerCase(Locale.ROOT);
        for (String prefix : List.of("https://", "http://", "www.")) if (e.startsWith(prefix)) e = e.substring(prefix.length());
        for (int i = 0; i < e.length(); i++) {
            char c = e.charAt(i);
            if (c != '-' && c != '_') b.append(c);
        }
        return b.toString();
    }

    private static boolean whitelistedDomain(String domain, List<String> whitelist) {
        String d = domain.toLowerCase(Locale.ROOT);
        if (d.startsWith("www.")) d = d.substring(4);
        for (String entry : whitelist) {
            String host = canonical(entry);
            int slash = host.indexOf('/');
            if (slash >= 0) host = host.substring(0, slash);
            if (host.isEmpty()) continue;
            if (d.equals(host) || d.endsWith("." + host)) return true;
        }
        return false;
    }

    private static boolean whitelistedInvite(String code, List<String> whitelist) {
        String c = code.toLowerCase(Locale.ROOT);
        for (String entry : whitelist) {
            String e = canonical(entry);
            int slash = e.lastIndexOf('/');
            if (slash < 0) continue;
            String host = e.substring(0, slash);
            if ((host.startsWith("discord") || host.equals("dsc.gg")) && e.substring(slash + 1).equals(c)) return true;
        }
        return false;
    }
}
