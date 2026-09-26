package top.cheesesmp.duelcore.ui;

import java.util.List;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;

/**
 * Plays a {@link SoundPool} pick to one player, only to them, respecting their sounds setting ({@link #playMatch}: and
 * their match sounds setting).
 */
public final class MatchSounds {

    private MatchSounds() {
    }

    /**
     * Whether the player wants match sounds: {@link Setting#SOUNDS} and {@link Setting#MATCH_SOUNDS} (match found,
     * countdown, "FIGHT!", round and match results, kills).
     */
    public static boolean matchSounds(DuelCorePlugin plugin, Player player) {
        PlayerProfile profile = plugin.profiles().get(player);
        return profile == null || profile.setting(Setting.SOUNDS) && profile.setting(Setting.MATCH_SOUNDS);
    }

    /** {@link #play} for a match sound: nothing when the player turned match sounds off. */
    public static void playMatch(DuelCorePlugin plugin, Player player, List<SoundPool.Played> sounds, int baseDelay) {
        if (matchSounds(plugin, player)) play(plugin, player, sounds, baseDelay);
    }

    /** Plays {@code sounds} at the player, each after its own delay plus {@code baseDelay} ticks. */
    public static void play(DuelCorePlugin plugin, Player player, List<SoundPool.Played> sounds, int baseDelay) {
        if (sounds.isEmpty()) return;
        PlayerProfile profile = plugin.profiles().get(player);
        if (profile != null && !profile.setting(Setting.SOUNDS)) return;
        for (SoundPool.Played s : sounds) {
            int delay = baseDelay + s.delay();
            if (delay <= 0) {
                player.playSound(player.getLocation(), s.key(), SoundCategory.MASTER, s.volume(), s.pitch());
            } else {
                player.getScheduler().runDelayed(plugin, task -> {
                    if (player.isOnline()) player.playSound(player.getLocation(), s.key(), SoundCategory.MASTER, s.volume(), s.pitch());
                }, null, delay);
            }
        }
    }
}
