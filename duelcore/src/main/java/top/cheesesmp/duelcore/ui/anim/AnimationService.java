package top.cheesesmp.duelcore.ui.anim;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import top.cheesesmp.duelcore.DuelCorePlugin;

/**
 * Runs per-player {@link Animation}s ({@code plugin.anim()}). Every player has one slot per {@link Channel}; starting
 * an animation on a channel ends the one running there. One shared timer (every tick, started with the plugin)
 * drives all of them, so there is no task per animation to leak. Animations end on quit, on a world change (unless
 * they {@link Animation#survivesWorldChange() survive it}) and on plugin disable.
 *
 * <p>Other systems that write to the same place check {@link #busy}: the hotbar hints and the queue's "searching"
 * action bar leave the action bar alone while an animation runs on it. Main thread only.
 */
public final class AnimationService implements Listener, Runnable {

    private static final class Running {
        final Animation animation;
        final int period;
        int age;

        Running(Animation animation, int period) {
            this.animation = animation;
            this.period = period;
        }
    }

    private final DuelCorePlugin plugin;
    private final Map<UUID, EnumMap<Channel, Running>> running = new HashMap<>();
    private long started;

    public AnimationService(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts {@code animation} for {@code player} on {@code channel}, replacing (and ending) what ran there. The first
     * frame is drawn on the next tick.
     */
    public void start(Player player, Channel channel, Animation animation) {
        if (!player.isOnline()) return;
        cancel(player, channel, Animation.End.REPLACED);
        int period = Math.max(channel == Channel.SOUND ? 1 : 2, animation.period());
        running.computeIfAbsent(player.getUniqueId(), k -> new EnumMap<>(Channel.class)).put(channel, new Running(animation, period));
        started++;
    }

    /** True while an animation runs on this player's channel. */
    public boolean busy(Player player, Channel channel) {
        return busy(player.getUniqueId(), channel);
    }

    public boolean busy(UUID uuid, Channel channel) {
        EnumMap<Channel, Running> slots = running.get(uuid);
        return slots != null && slots.containsKey(channel);
    }

    /** Ends the animation on this channel (if any) as cancelled. */
    public void cancel(Player player, Channel channel) {
        cancel(player, channel, Animation.End.CANCELLED);
    }

    private void cancel(Player player, Channel channel, Animation.End reason) {
        EnumMap<Channel, Running> slots = running.get(player.getUniqueId());
        if (slots == null) return;
        Running r = slots.remove(channel);
        if (slots.isEmpty()) running.remove(player.getUniqueId());
        if (r != null) end(player, r, reason);
    }

    /** Ends every animation of a player. */
    public void cancelAll(Player player) {
        EnumMap<Channel, Running> slots = running.remove(player.getUniqueId());
        if (slots == null) return;
        for (Running r : slots.values()) end(player, r, Animation.End.CANCELLED);
    }

    /** Plugin disable: ends everything (cleanups still run for online players). */
    public void cancelAll() {
        for (UUID uuid : new ArrayList<>(running.keySet())) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                cancelAll(p);
            } else {
                running.remove(uuid);
            }
        }
        running.clear();
    }

    @Override
    public void run() {
        if (running.isEmpty()) return;
        for (UUID uuid : new ArrayList<>(running.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                running.remove(uuid); // (quit already ended them; this only catches a missed event)
                continue;
            }
            EnumMap<Channel, Running> slots = running.get(uuid);
            if (slots == null) continue;
            for (Channel channel : new ArrayList<>(slots.keySet())) {
                // a frame may have cancelled or replaced this player's animations: always look them up again
                EnumMap<Channel, Running> current = running.get(uuid);
                if (current == null) break;
                Running r = current.get(channel);
                if (r == null) continue;
                int age = r.age++;
                if (age % r.period != 0) continue;
                boolean more;
                try {
                    more = r.animation.frame(player, age);
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING, "Animation on " + channel + " for " + player.getName() + " failed", t);
                    more = false;
                }
                if (more) continue;
                // the frame may have started a new animation on this channel: only remove our own entry
                EnumMap<Channel, Running> now = running.get(uuid);
                if (now != null && now.get(channel) == r) {
                    now.remove(channel);
                    if (now.isEmpty()) running.remove(uuid);
                    end(player, r, Animation.End.FINISHED);
                }
            }
        }
    }

    private void end(Player player, Running r, Animation.End reason) {
        try {
            r.animation.end(player, reason);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Animation cleanup for " + player.getName() + " failed", t);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cancelAll(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        EnumMap<Channel, Running> slots = running.get(player.getUniqueId());
        if (slots == null) return;
        List<Channel> stop = new ArrayList<>();
        for (Map.Entry<Channel, Running> e : slots.entrySet()) {
            if (!e.getValue().animation.survivesWorldChange()) stop.add(e.getKey());
        }
        for (Channel c : stop) cancel(player, c, Animation.End.CANCELLED);
    }

    /** Animations running now and started since enable (for /duelcore debug). */
    public String stats() {
        int n = 0;
        for (EnumMap<Channel, Running> slots : running.values()) n += slots.size();
        return n + " running, " + started + " started";
    }
}
