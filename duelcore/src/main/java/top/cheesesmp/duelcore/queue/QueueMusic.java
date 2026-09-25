package top.cheesesmp.duelcore.queue;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.MainConfig;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;

/**
 * Music while searching: a random music disc from {@code queue.music.tracks}, played to the queued player only
 * (record source, emitted from the player so it follows them). When a track ends and they are still searching the
 * next random one starts. It stops the moment they are matched, leave every queue, start spectating, quit, or turn
 * {@link Setting#QUEUE_MUSIC} off. Main thread only.
 */
public final class QueueMusic implements Listener, Runnable {

    /** Pause between two tracks. */
    private static final long GAP_MILLIS = 1500;

    private record Playing(MusicTracks.Track track, Key key, long endsAt) {
    }

    private final DuelCorePlugin plugin;
    private final Map<UUID, Playing> playing = new HashMap<>();
    private MusicTracks tracks = MusicTracks.EMPTY;

    public QueueMusic(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** Re-reads the track pool; returns the configured tracks this server has no sound for. */
    public List<String> reload() {
        List<String> problems = new ArrayList<>();
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT);
        tracks = plugin.settings().queueMusicTracks.filter(t -> {
            NamespacedKey key = NamespacedKey.fromString(t.key());
            boolean known = key != null && registry.get(key) != null;
            if (!known) problems.add("queue.music.tracks: unknown sound " + t.key() + " (skipped)");
            return known;
        });
        return problems;
    }

    public int playingCount() {
        return playing.size();
    }

    /** The track a player hears, or null. */
    public @Nullable String track(UUID uuid) {
        Playing p = playing.get(uuid);
        return p == null ? null : p.track().key();
    }

    /** Checks every queued player: starts the next track when one ended, stops it for anyone no longer searching. */
    /** Ticks between two "stop the game's background music" packets to everyone (the client starts a new song now and then). */
    private static final int CLIENT_MUSIC_EVERY = 200;
    private int clientMusicTicks;

    @Override
    public void run() {
        // the timer runs every 10 ticks
        clientMusicTicks += 10;
        if (plugin.settings().stopClientMusic && clientMusicTicks >= CLIENT_MUSIC_EVERY) {
            clientMusicTicks = 0;
            for (Player p : Bukkit.getOnlinePlayers()) p.stopSound(org.bukkit.SoundCategory.MUSIC);
        }
        for (Iterator<Map.Entry<UUID, Playing>> it = playing.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Playing> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {
                it.remove();
            } else if (!wanted(p)) {
                it.remove();
                silence(p, e.getValue());
            }
        }
        if (!plugin.settings().queueMusicEnabled || tracks.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (UUID uuid : plugin.queue().queuedPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) play(p, now);
        }
    }

    /** Starts or stops the music right away after a player's queue state or setting changed. */
    public void refresh(Player player) {
        if (wanted(player)) play(player, System.currentTimeMillis());
        else stop(player.getUniqueId());
    }

    /** Stops the music of a player (matched, left the queues, spectating, …). */
    public void stop(UUID uuid) {
        Playing p = playing.remove(uuid);
        if (p == null) return;
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) silence(player, p);
    }

    /** Plugin disable: silence everyone. */
    public void stopAll() {
        for (UUID uuid : new ArrayList<>(playing.keySet())) stop(uuid);
        playing.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        if (plugin.settings().stopClientMusic) event.getPlayer().stopSound(org.bukkit.SoundCategory.MUSIC);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        playing.remove(event.getPlayer().getUniqueId());
    }

    private boolean wanted(Player player) {
        MainConfig c = plugin.settings();
        if (!c.queueMusicEnabled || tracks.isEmpty() || !player.isOnline()) return false;
        UUID uuid = player.getUniqueId();
        if (!plugin.queue().isQueued(uuid) || plugin.matches().match(uuid) != null
            || plugin.spectate().spectating(uuid) != null) {
            return false;
        }
        PlayerProfile profile = plugin.profiles().get(uuid);
        return profile != null && profile.setting(Setting.QUEUE_MUSIC);
    }

    private void play(Player player, long now) {
        Playing current = playing.get(player.getUniqueId());
        if (current != null && now < current.endsAt()) return;
        if (!wanted(player)) return;
        MusicTracks.Track next = tracks.pick(ThreadLocalRandom.current(), current == null ? null : current.track());
        if (next == null) return;
        if (current != null) silence(player, current);
        Key key = Key.key(next.key());
        player.playSound(Sound.sound(key, Sound.Source.RECORD, plugin.settings().queueMusicVolume, next.speed()), Sound.Emitter.self());
        playing.put(player.getUniqueId(), new Playing(next, key, now + next.playMillis() + GAP_MILLIS));
    }

    private static void silence(Player player, Playing p) {
        player.stopSound(SoundStop.namedOnSource(p.key(), Sound.Source.RECORD));
    }
}
