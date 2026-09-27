package top.cheesesmp.duelcore.match;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;
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
import top.cheesesmp.duelcore.queue.MusicTracks;

/**
 * Music during matches (config.yml {@code match.music}): when a round's countdown starts, the match picks a random
 * track (a high-energy one, or with {@code medium-chance} a medium one; never the previous round's) and everyone in
 * it and watching it with {@link Setting#MATCH_MUSIC} on hears it; the next round picks another. A track that ends
 * mid-round is followed by another. It stops when the match ends, for anyone who leaves it, and with
 * {@code /stopmusic} (which also switches the setting off; {@code /playmusic} switches it back on and starts the
 * round's track right away). Main thread only.
 */
public final class MatchMusic implements Listener, Runnable {

    /** The track of one round of one match (everyone in it hears the same one). */
    private record RoundTrack(int round, MusicTracks.Track track, long endsAt) {
    }

    /** What a player hears now. */
    private record Playing(Match match, int round, MusicTracks.Track track, Key key) {
    }

    private final DuelCorePlugin plugin;
    private final Map<Match, RoundTrack> rounds = new HashMap<>();
    private final Map<UUID, Playing> playing = new HashMap<>();
    private MusicTracks high = MusicTracks.EMPTY;
    private MusicTracks medium = MusicTracks.EMPTY;

    public MatchMusic(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** Re-reads both pools; returns the configured tracks this server has no sound for. */
    public List<String> reload() {
        List<String> problems = new ArrayList<>();
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT);
        high = plugin.settings().matchMusicHigh.filter(t -> known(registry, t, "high", problems));
        medium = plugin.settings().matchMusicMedium.filter(t -> known(registry, t, "medium", problems));
        return problems;
    }

    private static boolean known(org.bukkit.Registry<?> registry, MusicTracks.Track t, String pool, List<String> problems) {
        NamespacedKey key = NamespacedKey.fromString(t.key());
        boolean known = key != null && registry.get(key) != null;
        if (!known) problems.add("match.music." + pool + ": unknown sound " + t.key() + " (skipped)");
        return known;
    }

    /** Every 10 ticks: the round's track for everyone who should hear it, silence for everyone else. */
    @Override
    public void run() {
        long now = System.currentTimeMillis();
        rounds.keySet().removeIf(Match::isOver);
        for (Iterator<Map.Entry<UUID, Playing>> it = playing.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Playing> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {
                it.remove();
            } else if (matchOf(p) != e.getValue().match() || !wants(p)) {
                it.remove();
                silence(p, e.getValue());
            }
        }
        if (!enabled()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Match m = matchOf(p);
            if (m == null || !wants(p) || !started(m)) continue;
            play(p, m, now);
        }
    }

    /** {@code /playmusic}: the setting on and the round's track now (when in or watching a match). */
    public void playNow(Player player) {
        PlayerProfile profile = plugin.profiles().get(player);
        if (profile == null) return;
        profile.setting(Setting.MATCH_MUSIC, true);
        plugin.profiles().saveSettings(profile);
        Match m = matchOf(player);
        if (m != null && enabled() && started(m)) play(player, m, System.currentTimeMillis());
    }

    /** {@code /stopmusic}: silence now and the setting off. */
    public void stopNow(Player player) {
        PlayerProfile profile = plugin.profiles().get(player);
        if (profile != null) {
            profile.setting(Setting.MATCH_MUSIC, false);
            plugin.profiles().saveSettings(profile);
        }
        Playing p = playing.remove(player.getUniqueId());
        if (p != null) silence(player, p);
    }

    /** Plugin disable: silence everyone. */
    public void stopAll() {
        for (Map.Entry<UUID, Playing> e : playing.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) silence(p, e.getValue());
        }
        playing.clear();
        rounds.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        playing.remove(event.getPlayer().getUniqueId());
    }

    private boolean enabled() {
        return plugin.settings().matchMusicEnabled && !(high.isEmpty() && medium.isEmpty());
    }

    private @Nullable Match matchOf(Player p) {
        Match m = plugin.matches().match(p.getUniqueId());
        if (m == null) m = plugin.spectate().spectating(p.getUniqueId());
        return m == null || m.isOver() ? null : m;
    }

    /** The first countdown has begun (music starts with the match, not while it is still being set up). */
    private static boolean started(Match m) {
        return m.round() > 1 || m.state() == Match.State.COUNTDOWN || m.state() == Match.State.FIGHTING
            || m.state() == Match.State.ROUND_END;
    }

    private boolean wants(Player p) {
        PlayerProfile profile = plugin.profiles().get(p);
        return profile != null && profile.setting(Setting.MATCH_MUSIC);
    }

    private void play(Player player, Match m, long now) {
        RoundTrack rt = roundTrack(m, now);
        if (rt == null) return;
        Playing current = playing.get(player.getUniqueId());
        if (current != null && current.match() == m && current.round() == rt.round() && current.track().equals(rt.track())) return;
        if (current != null) silence(player, current);
        Key key = Key.key(rt.track().key());
        player.playSound(Sound.sound(key, Sound.Source.RECORD, plugin.settings().matchMusicVolume, rt.track().speed()), Sound.Emitter.self());
        playing.put(player.getUniqueId(), new Playing(m, rt.round(), rt.track(), key));
    }

    /** The match's track for its current round: a new one each round, and a new one when it ran out. */
    private @Nullable RoundTrack roundTrack(Match m, long now) {
        RoundTrack rt = rounds.get(m);
        if (rt != null && rt.round() == m.round() && now < rt.endsAt()) return rt;
        MusicTracks.Track next = pick(ThreadLocalRandom.current(), rt == null ? null : rt.track());
        if (next == null) return null;
        rt = new RoundTrack(m.round(), next, now + next.playMillis() + 1500);
        rounds.put(m, rt);
        return rt;
    }

    private MusicTracks.@Nullable Track pick(RandomGenerator random, MusicTracks.@Nullable Track previous) {
        return pick(high, medium, plugin.settings().matchMusicMediumChance, random, previous);
    }

    /** A medium track with {@code mediumChance} (when there are any), else a high one; not {@code previous} if avoidable. */
    static MusicTracks.@Nullable Track pick(MusicTracks high, MusicTracks medium, double mediumChance,
                                            RandomGenerator random, MusicTracks.@Nullable Track previous) {
        boolean useMedium = !medium.isEmpty() && (high.isEmpty() || random.nextDouble() < mediumChance);
        MusicTracks pool = useMedium ? medium : high;
        MusicTracks.Track t = pool.pick(random, previous);
        if (t != null && t.equals(previous) && !(useMedium ? high : medium).isEmpty()) {
            t = (useMedium ? high : medium).pick(random, previous); // a one-track pool: take the other one
        }
        return t;
    }

    private static void silence(Player player, Playing p) {
        player.stopSound(SoundStop.namedOnSource(p.key(), Sound.Source.RECORD));
    }
}
