package top.cheesesmp.duelcore.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.debug.TesterMode;

/**
 * The sound of a menu press ({@code plugin.menuSounds()}): every {@code duelcore:*} click (dialog buttons, chat
 * buttons, Escape) and hub hotbar item plays one to the presser only, respecting gui.yml {@code menu-sounds.enabled}
 * and the player's sounds setting.
 *
 * <p>At most one menu sound per player per tick ({@link Gate}): a handler that knows better (a toggle's new state, a
 * refusal) plays its kind first, then ClickRouter / HubListener play the press's default kind ({@link MenuSound#forClick})
 * as a fallback, which is skipped. Features with their own press sounds (the kit editor) {@link #claim} the tick.
 */
public final class MenuSounds implements Listener {

    private final DuelCorePlugin plugin;
    private final Gate gate = new Gate();

    public MenuSounds(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** Logs gui.yml problems and registers the {@code /animtest play menu-sounds} preview (every kind in a row). */
    public void enable(TesterMode tester) {
        for (String problem : plugin.gui().menuSounds.problems()) plugin.getLogger().warning(problem);
        tester.preview("menu-sounds", p -> {
            MenuSound[] kinds = MenuSound.values();
            for (int i = 0; i < kinds.length; i++) {
                MenuSound kind = kinds[i];
                p.getScheduler().runDelayed(plugin, t -> play(p, kind), null, 1L + i * 12L);
            }
        });
    }

    /** Plays {@code kind} to the player, unless a menu sound already played to them this tick. */
    public void play(Player player, MenuSound kind) {
        MenuSoundStyle style = plugin.gui().menuSounds;
        if (!style.enabled() || !gate.claim(player.getUniqueId(), Bukkit.getCurrentTick())) return;
        MatchSounds.play(plugin, player, style.sound(kind).pick(ThreadLocalRandom.current()), 0);
    }

    /** Marks that the player's press already has its own sound this tick, so the fallback {@link #play} is skipped. */
    public void claim(Player player) {
        gate.claim(player.getUniqueId(), Bukkit.getCurrentTick());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        gate.forget(event.getPlayer().getUniqueId());
    }

    /** One sound per player per tick: the first {@link #claim} of a tick wins. Pure. */
    static final class Gate {

        private final Map<UUID, Integer> last = new HashMap<>();

        /** True when nothing claimed {@code tick} for this player yet (it is claimed now). */
        boolean claim(UUID player, int tick) {
            Integer prev = last.put(player, tick);
            if (last.size() > 512) last.values().removeIf(t -> t != tick);
            return prev == null || prev != tick;
        }

        void forget(UUID player) {
            last.remove(player);
        }

        int size() {
            return last.size();
        }
    }
}
