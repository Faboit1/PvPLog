package top.cheesesmp.duelcore.ui;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import top.cheesesmp.duelcore.ui.anim.Ease;
import top.cheesesmp.duelcore.ui.anim.Sfx;
import top.cheesesmp.duelcore.ui.anim.TextFx;

/**
 * The pure parts of {@link MatchFx} (no Bukkit, unit tested): countdown colours, the "FIGHT!" sweep, match point,
 * heartbeat timing and the victory / defeat phrases.
 */
public final class MatchFxMath {

    private MatchFxMath() {
    }

    /**
     * The countdown colour for {@code secs} seconds left: {@code bySecond} lists the colours for 1, 2, 3, … seconds
     * left; more seconds than colours use the last one (e.g. red, yellow, green: 1 red, 2 yellow, 3+ green).
     */
    public static TextColor countdownColor(List<TextColor> bySecond, int secs, TextColor fallback) {
        if (bySecond.isEmpty()) return fallback;
        return bySecond.get(Math.clamp(secs - 1, 0, bySecond.size() - 1));
    }

    /**
     * Semitones above the first countdown tick for {@code secs} seconds left of a countdown that shows
     * {@code shown} numbers: two per second, so the last second is the highest.
     */
    public static int countdownSemitones(int secs, int shown) {
        return 2 * Math.max(0, shown - Math.max(1, secs));
    }

    /**
     * {@code text} coloured by a gradient across its characters ({@code gradient}, left to right) with a bright band
     * ({@code shine}) at {@code phase} (0 just left of the text, 1 just right, see {@link TextFx#glow}).
     */
    public static Component sweep(String text, List<TextColor> gradient, TextColor shine, double phase, double width,
                                  TextDecoration... decorations) {
        int total = text.codePointCount(0, text.length());
        TextComponent.Builder out = Component.text();
        for (TextDecoration d : decorations) out.decoration(d, true);
        int i = 0;
        for (int offset = 0; offset < text.length(); i++) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            TextColor base = gradientAt(gradient, total <= 1 ? 0 : (double) i / (total - 1), shine);
            out.append(Component.text(Character.toString(cp), TextFx.lerp(base, shine, TextFx.glow(i, total, phase, width))));
        }
        return out.build();
    }

    /** A colour along evenly spaced stops at {@code t} (0..1). */
    public static TextColor gradientAt(List<TextColor> stops, double t, TextColor fallback) {
        if (stops.isEmpty()) return fallback;
        if (stops.size() == 1) return stops.getFirst();
        double pos = Ease.clamp01(t) * (stops.size() - 1);
        int i = Math.min((int) Math.floor(pos), stops.size() - 2);
        return TextFx.lerp(stops.get(i), stops.get(i + 1), pos - i);
    }

    /** Teams one round from winning ({@code firstTo − 1} rounds). Empty for single-round matches. */
    public static List<Integer> matchPoint(int[] score, int firstTo) {
        List<Integer> teams = new ArrayList<>();
        if (firstTo <= 1) return teams;
        for (int t = 0; t < score.length; t++) if (score[t] == firstTo - 1) teams.add(t);
        return teams;
    }

    // ------------------------------------------------------------------ heartbeat

    /** Ticks between two heartbeats at {@code hp} (of {@code threshold}): 16 near death, 32 at the threshold. */
    public static int heartbeatInterval(double hp, double threshold) {
        double f = threshold <= 0 ? 0 : Ease.clamp01(hp / threshold);
        return (int) Math.round(Ease.lerp(16, 32, f));
    }

    /** Whether {@code hp} is low enough for the heartbeat (dead or no threshold: no). */
    public static boolean lowHealth(double hp, double threshold) {
        return hp > 0 && threshold > 0 && hp <= threshold;
    }

    /** How bright the heartbeat bar is {@code tick} ticks into a beat, 0..1: a "lub" at 0, a softer "dub" at 6. */
    public static double pulse(int tick) {
        return Math.max(beat(tick), 0.6 * beat(tick - 6));
    }

    private static double beat(int tick) {
        if (tick < 0) return 0;
        return 1 - Ease.easeOutQuad(Ease.progress(tick, 6));
    }

    // ------------------------------------------------------------------ phrases

    /** Victory jingle: a major arpeggio up an octave, then a bright chord on top. */
    public static List<Sfx.Note> victoryJingle() {
        List<Sfx.Note> out = new ArrayList<>();
        int[] steps = {0, 4, 7, 12};
        for (int i = 0; i < steps.length; i++) out.add(new Sfx.Note(Sfx.BELL, Sfx.pitch(0.8f, steps[i]), 0.55f, i * 3));
        out.add(new Sfx.Note(Sfx.CHIME, Sfx.pitch(0.8f, 16), 0.5f, 12));
        out.add(new Sfx.Note(Sfx.LEVEL_UP, 1.2f, 0.45f, 12));
        out.add(new Sfx.Note(Sfx.AMETHYST, 1.6f, 0.9f, 15));
        return out;
    }

    /** Defeat: three quiet notes walking down. */
    public static List<Sfx.Note> defeatPhrase() {
        return List.of(new Sfx.Note(Sfx.BELL, 0.9f, 0.3f, 0), new Sfx.Note(Sfx.BELL, 0.75f, 0.3f, 5),
            new Sfx.Note(Sfx.BELL, 0.6f, 0.3f, 10));
    }
}
