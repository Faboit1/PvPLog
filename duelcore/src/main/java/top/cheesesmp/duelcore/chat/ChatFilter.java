package top.cheesesmp.duelcore.chat;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/**
 * Fuzzy word filter. Every message is first normalized character by character (so matches map back onto the original
 * text): look-alike letters from other scripts, accents, fullwidth forms and zero-width characters are folded to plain
 * lowercase latin. Each configured term then becomes a regex that also catches
 * <ul>
 *   <li>leetspeak ({@code n1gg3r}, {@code f@g}, {@code $h!t}),</li>
 *   <li>stretched letters ({@code niiiigger}),</li>
 *   <li>up to three separator characters between letters ({@code n.i.g g-e_r}),</li>
 *   <li>common endings (s, z, es, ed, er, ers, a, as, ah, uh, y, ing).</li>
 * </ul>
 * Terms match whole words; a leading or trailing {@code *} lets them match inside a longer word. Words on the allow
 * list (e.g. "raccoon", "snigger") are never flagged. Entries starting with {@code re:} are used as raw regexes on the
 * normalized text.
 */
public final class ChatFilter {

    /** How a message is handled. */
    public enum Verdict { CLEAN, CENSORED, BLOCKED }

    public record Result(Verdict verdict, String text, @Nullable String matched) {
    }

    private static final Map<Character, String> LEET = Map.ofEntries(
        Map.entry('a', "a4@"), Map.entry('b', "b8"), Map.entry('c', "c(k"), Map.entry('e', "e3"),
        Map.entry('g', "g69q"), Map.entry('i', "i1!|l"), Map.entry('l', "l1|i"), Map.entry('o', "o0"),
        Map.entry('s', "s5$z"), Map.entry('t', "t7+"), Map.entry('z', "z2s"), Map.entry('k', "kc"),
        Map.entry('u', "uv"), Map.entry('v', "vu"), Map.entry('x', "x"));

    /** Look-alikes NFKD doesn't fold (Cyrillic, Greek, IPA). */
    private static final Map<Character, Character> HOMOGLYPHS = Map.ofEntries(
        Map.entry('а', 'a'), Map.entry('в', 'b'), Map.entry('е', 'e'), Map.entry('ё', 'e'), Map.entry('к', 'k'),
        Map.entry('м', 'm'), Map.entry('н', 'h'), Map.entry('о', 'o'), Map.entry('р', 'p'), Map.entry('с', 'c'),
        Map.entry('т', 't'), Map.entry('у', 'y'), Map.entry('х', 'x'), Map.entry('і', 'i'), Map.entry('ї', 'i'),
        Map.entry('ј', 'j'), Map.entry('ԁ', 'd'), Map.entry('ɡ', 'g'), Map.entry('ѕ', 's'), Map.entry('ԛ', 'q'),
        Map.entry('ɢ', 'g'), Map.entry('ɪ', 'i'), Map.entry('ɴ', 'n'), Map.entry('ʀ', 'r'), Map.entry('ᴇ', 'e'),
        Map.entry('ᴀ', 'a'), Map.entry('ᴏ', 'o'), Map.entry('ᴜ', 'u'), Map.entry('ᴛ', 't'), Map.entry('ᴋ', 'k'),
        Map.entry('α', 'a'), Map.entry('β', 'b'), Map.entry('ε', 'e'), Map.entry('ι', 'i'), Map.entry('κ', 'k'),
        Map.entry('ν', 'v'), Map.entry('ο', 'o'), Map.entry('ρ', 'p'), Map.entry('τ', 't'), Map.entry('υ', 'u'),
        Map.entry('χ', 'x'), Map.entry('ı', 'i'), Map.entry('ß', 's'), Map.entry('ø', 'o'), Map.entry('đ', 'd'),
        Map.entry('ł', 'l'), Map.entry('ŋ', 'n'), Map.entry('¡', 'i'), Map.entry('€', 'e'), Map.entry('£', 'l'),
        Map.entry('¥', 'y'), Map.entry('¢', 'c'));

    private static final String ENDINGS = "(?:s|z|es|ed|er|ers|a|as|az|ah|uh|y|ing|in)?";
    private static final String SEP = "[^\\p{L}\\p{N}]{0,3}";

    /** {@code openStart}: may start inside a word, but then only when written without separators. */
    private record Rule(String term, Pattern pattern, boolean openStart) {
    }

    private final boolean enabled;
    private final List<Rule> block;
    private final List<Rule> censor;
    private final Set<String> allow;
    private final char mask;
    private final List<String> commands;

    public ChatFilter(@Nullable ConfigurationSection c, java.util.logging.Logger log) {
        enabled = c != null && c.getBoolean("enabled", true);
        block = c == null ? List.of() : compile(c.getStringList("block"), log);
        censor = c == null ? List.of() : compile(c.getStringList("censor"), log);
        allow = c == null ? Set.of() : Set.copyOf(c.getStringList("allow").stream()
            .map(w -> w.toLowerCase(Locale.ROOT).strip()).toList());
        String m = c == null ? "*" : c.getString("mask", "*");
        mask = m == null || m.isEmpty() ? '*' : m.charAt(0);
        commands = c == null ? List.of() : List.copyOf(c.getStringList("on-block-commands"));
    }

    /** Console commands run when a message is blocked ({@code <player>} = sender). */
    public List<String> commands() {
        return commands;
    }

    public boolean enabled() {
        return enabled;
    }

    public int size() {
        return block.size() + censor.size();
    }

    private static List<Rule> compile(List<String> terms, java.util.logging.Logger log) {
        List<Rule> rules = new ArrayList<>();
        for (String raw : terms) {
            if (raw == null || raw.isBlank()) continue;
            try {
                rules.add(new Rule(raw, Pattern.compile(raw.startsWith("re:") ? raw.substring(3) : fuzzy(raw)),
                    raw.strip().startsWith("*")));
            } catch (PatternSyntaxException e) {
                log.warning("chat-filter: bad entry '" + raw + "': " + e.getDescription());
            }
        }
        return List.copyOf(rules);
    }

    /** Turns a plain term ("nigg*", "kill yourself") into the fuzzy regex described on the class. */
    static String fuzzy(String raw) {
        String term = raw.toLowerCase(Locale.ROOT).strip();
        boolean openStart = term.startsWith("*");
        boolean openEnd = term.endsWith("*");
        term = term.replace("*", "");
        StringBuilder re = new StringBuilder(openStart ? "" : "(?<![\\p{L}\\p{N}])");
        boolean first = true;
        for (char ch : term.toCharArray()) {
            if (ch == ' ') {
                re.append("[^\\p{L}\\p{N}]*"); // "kill yourself", "k y s"
                continue;
            }
            if (!first) re.append(SEP);
            first = false;
            String cls = LEET.getOrDefault(ch, String.valueOf(ch));
            re.append('[');
            for (char k : cls.toCharArray()) {
                if (!Character.isLetterOrDigit(k)) re.append('\\');
                re.append(k);
            }
            re.append("]+");
        }
        if (!openEnd) re.append(ENDINGS).append("(?![\\p{L}\\p{N}])");
        return re.toString();
    }

    /** Folds one message; {@code index[i]} is the original index of normalized char i. */
    private static String normalize(String text, int[] index) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '​' || c == '‌' || c == '‍' || c == '‎' || c == '‏' || c == '⁠'
                || c == '﻿' || c == '­' || c == '͏' || Character.getType(c) == Character.NON_SPACING_MARK) {
                continue; // invisible: drop without leaving a gap
            }
            char lower = Character.toLowerCase(c);
            Character glyph = HOMOGLYPHS.get(lower);
            if (glyph != null) {
                lower = glyph;
            } else if (lower > 127) {
                String folded = Normalizer.normalize(String.valueOf(lower), Normalizer.Form.NFKD);
                if (!folded.isEmpty()) lower = Character.toLowerCase(folded.charAt(0));
                Character again = HOMOGLYPHS.get(lower);
                if (again != null) lower = again;
            }
            index[out.length()] = i;
            out.append(lower);
        }
        return out.toString();
    }

    /** Checks a message: blocked if any block term matches, else censored terms are masked. */
    public Result check(String text) {
        if (!enabled || text.isEmpty()) return new Result(Verdict.CLEAN, text, null);
        int[] index = new int[text.length() + 1];
        String norm = normalize(text, index);
        for (Rule r : block) {
            Matcher m = r.pattern().matcher(norm);
            while (m.find()) {
                if (!ignored(r, norm, m)) return new Result(Verdict.BLOCKED, text, r.term());
            }
        }
        char[] masked = null;
        String first = null;
        for (Rule r : censor) {
            Matcher m = r.pattern().matcher(norm);
            while (m.find()) {
                if (m.end() <= m.start() || ignored(r, norm, m)) continue;
                if (masked == null) masked = text.toCharArray();
                if (first == null) first = r.term();
                int from = index[m.start()];
                int to = index[m.end() - 1];
                for (int i = from; i <= to; i++) if (!Character.isWhitespace(masked[i])) masked[i] = mask;
            }
        }
        return masked == null ? new Result(Verdict.CLEAN, text, null) : new Result(Verdict.CENSORED, new String(masked), first);
    }

    private boolean ignored(Rule r, String norm, Matcher m) {
        if (r.openStart() && m.start() > 0 && Character.isLetterOrDigit(norm.charAt(m.start() - 1))) {
            // inside a word ("sandnigger") only counts when the letters are joined, not "an iggy"
            for (int i = m.start(); i < m.end(); i++) if (!Character.isLetterOrDigit(norm.charAt(i))) return true;
        }
        return allowed(norm, m.start(), m.end());
    }

    /** True if the match lies inside a word on the allow list. */
    private boolean allowed(String norm, int start, int end) {
        if (allow.isEmpty()) return false;
        int s = start;
        int e = end;
        while (s > 0 && Character.isLetterOrDigit(norm.charAt(s - 1))) s--;
        while (e < norm.length() && Character.isLetterOrDigit(norm.charAt(e))) e++;
        String word = norm.substring(s, e);
        if (allow.contains(word)) return true;
        // the match may span several words ("sn igger" is not a thing, but "cocoon s" is); check each touched word
        for (String w : norm.substring(start, end).split("[^\\p{L}\\p{N}]+")) {
            if (!w.isEmpty() && allow.contains(w)) return true;
        }
        return false;
    }
}
