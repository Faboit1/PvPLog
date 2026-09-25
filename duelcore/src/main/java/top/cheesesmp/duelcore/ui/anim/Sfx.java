package top.cheesesmp.duelcore.ui.anim;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;

/**
 * Small sound phrases for animations. The builders are pure (lists of {@link Note}s, unit tested); {@link #play}
 * sends them to one player only, respecting their {@link Setting#SOUNDS} setting. A phrase with delays runs on the
 * player's {@link Channel#SOUND} channel, so a new phrase cuts off the previous one instead of piling up.
 *
 * <pre>
 * Sfx.play(plugin, player, Sfx.arpeggio(Sfx.PLING, 4, 1.0f, 4, 2, 0.5f));  // four notes stepping up a major third
 * Sfx.play(plugin, player, Sfx.tick(step, steps, true));                    // one tick, pitch rising with step
 * Sfx.play(plugin, player, Sfx.flourish());                                  // level-up + amethyst chime
 * </pre>
 */
public final class Sfx {

    public static final String PLING = "minecraft:block.note_block.pling";
    public static final String CHIME = "minecraft:block.note_block.chime";
    public static final String BELL = "minecraft:block.note_block.bell";
    public static final String HAT = "minecraft:block.note_block.hat";
    public static final String BASS = "minecraft:block.note_block.bass";
    public static final String AMETHYST = "minecraft:block.amethyst_block.chime";
    public static final String LEVEL_UP = "minecraft:entity.player.levelup";
    public static final String ORB = "minecraft:entity.experience_orb.pickup";

    /** One sound: vanilla sound key, pitch (0.5–2), volume and delay in ticks from the start of the phrase. */
    public record Note(String key, float pitch, float volume, int delay) {
    }

    private Sfx() {
    }

    // ------------------------------------------------------------------ builders (pure)

    /** {@code base} raised by {@code semitones} (negative lowers), clamped to Minecraft's 0.5–2. */
    public static float pitch(float base, int semitones) {
        return (float) Math.clamp(base * Math.pow(2, semitones / 12.0), 0.5, 2.0);
    }

    /** {@code steps} notes of {@code key}, each {@code semitoneStep} higher and {@code spacing} ticks after the last. */
    public static List<Note> arpeggio(String key, int steps, float basePitch, int semitoneStep, int spacing, float volume) {
        List<Note> out = new ArrayList<>(Math.max(0, steps));
        for (int i = 0; i < steps; i++) out.add(new Note(key, pitch(basePitch, i * semitoneStep), volume, i * spacing));
        return out;
    }

    /**
     * The tick of a counting step: a quiet hat-like pling whose pitch walks up (or down) an octave over
     * {@code steps}. Step 0 is the lowest (or highest).
     */
    public static Note tick(int step, int steps, boolean up) {
        int n = Math.max(1, steps - 1);
        int semis = (int) Math.round(12.0 * Math.clamp(step, 0, n) / n);
        return new Note(PLING, up ? pitch(0.8f, semis) : pitch(1.6f, -semis), 0.35f, 0);
    }

    /** Completion flourish: a rising chime arpeggio, then level-up and an amethyst chime on top. */
    public static List<Note> flourish() {
        List<Note> out = new ArrayList<>(arpeggio(CHIME, 3, 1.0f, 4, 2, 0.6f));
        out.add(new Note(LEVEL_UP, 1.25f, 0.6f, 6));
        out.add(new Note(AMETHYST, 1.5f, 1.0f, 7));
        out.add(new Note(AMETHYST, 1.9f, 0.8f, 11));
        return out;
    }

    /** A short, soft "done" for a finished count: two notes, rising for gains, falling for losses. */
    public static List<Note> settle(boolean up) {
        return up ? List.of(new Note(CHIME, 1.5f, 0.45f, 0), new Note(CHIME, 2.0f, 0.45f, 3))
            : List.of(new Note(BASS, 0.9f, 0.4f, 0), new Note(BASS, 0.7f, 0.4f, 4));
    }

    /** Demotion: a soft falling pair, nothing dramatic. */
    public static List<Note> demotion() {
        return List.of(new Note(BELL, 0.8f, 0.35f, 0), new Note(BELL, 0.6f, 0.35f, 5));
    }

    /** Ticks until the last note of a phrase. */
    public static int length(List<Note> notes) {
        int max = 0;
        for (Note n : notes) max = Math.max(max, n.delay());
        return max;
    }

    // ------------------------------------------------------------------ playing

    /** Whether the player wants sound effects ({@link Setting#SOUNDS}). */
    public static boolean enabled(DuelCorePlugin plugin, Player player) {
        PlayerProfile profile = plugin.profiles().get(player);
        return profile == null || profile.setting(Setting.SOUNDS);
    }

    /** Plays one note right now (its delay is ignored), to this player only. */
    public static void play(DuelCorePlugin plugin, Player player, Note note) {
        if (!enabled(plugin, player)) return;
        emit(player, note);
    }

    /** Plays a phrase on the player's {@link Channel#SOUND} channel (replacing a phrase still playing there). */
    public static void play(DuelCorePlugin plugin, Player player, List<Note> notes) {
        if (notes.isEmpty() || !enabled(plugin, player)) return;
        int last = length(notes);
        if (last == 0) {
            for (Note n : notes) emit(player, n);
            return;
        }
        plugin.anim().start(player, Channel.SOUND, new Animation() {
            @Override
            public boolean frame(Player p, int tick) {
                for (Note n : notes) if (n.delay() == tick) emit(p, n);
                return tick < last;
            }

            @Override
            public int period() {
                return 1;
            }

            @Override
            public boolean survivesWorldChange() {
                return true;
            }
        });
    }

    private static void emit(Player player, Note n) {
        player.playSound(player.getLocation(), n.key(), SoundCategory.MASTER, n.volume(), n.pitch());
    }
}
