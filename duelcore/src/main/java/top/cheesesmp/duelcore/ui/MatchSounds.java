package top.cheesesmp.duelcore.ui;

import java.util.List;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;

/** Plays a {@link SoundPool} pick to one player, only to them, respecting their sounds setting. */
public final class MatchSounds {

    private MatchSounds() {
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
