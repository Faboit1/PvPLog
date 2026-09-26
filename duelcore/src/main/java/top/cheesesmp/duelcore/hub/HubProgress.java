package top.cheesesmp.duelcore.hub;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;
import top.cheesesmp.duelcore.rating.Tier;
import top.cheesesmp.duelcore.rating.TierLadder;
import top.cheesesmp.duelcore.ui.anim.Ease;
import top.cheesesmp.duelcore.ui.anim.Sfx;

/**
 * The hub XP bar as overall progress ({@code animations.hub-xp-bar}): the level is the overall Elo, or the placement
 * games of the kit closest to its first tier while nothing is ranked; the bar is the way to the next overall tier (or
 * through placement). {@link HubService#prepare} shows it after {@code KitManager.resetState} cleared it, so matches
 * and spectating never show it and the hub always puts it back.
 *
 * <p>When it changed since the player was last in the hub (a match), the bar fills from the old value to the new one
 * with soft XP orb sounds ({@code animations.hub-xp-fill}), and a kit that reached a better tier gets a sparkle ring
 * in the tier's colour rising from the player's feet ({@code animations.tier-ring}; only they see it). Both run on
 * this service's own 2-tick timer, stop as soon as the player leaves the lobby, and everything is per-player memory
 * cleared on quit. Players who turned off {@link Setting#PROGRESS_REVEAL} get the bar without fill or ring. Testers (/animtest) see the fill on every return to the hub, even after unrated matches.
 */
public final class HubProgress implements Listener, Runnable {

    /** Ticks between two steps (the service runs every 2 ticks). */
    public static final int PERIOD = 2;
    /** Fill: wait for the hub teleport, then fill; about when the post-match action bar counts. */
    private static final int FILL_DELAY = 10;
    private static final int FILL_TICKS = 30;
    private static final int ORB_SPACING = 6;
    /** Ring: starts with the progress reveal's tier-up title. */
    private static final int RING_DELAY = 40;
    private static final int RING_TICKS = 20;
    private static final int RING_POINTS = 8;

    /** What the bar shows: overall Elo (ranked) or placement games as the level, and the bar 0..1. */
    record Shown(boolean ranked, int level, double bar) {
    }

    private static final class Fill {
        final Shown from;
        final Shown to;
        int age;
        int lastOrb = -ORB_SPACING;

        Fill(Shown from, Shown to) {
            this.from = from;
            this.to = to;
        }
    }

    private static final class Ring {
        final TextColor color;
        int age;

        Ring(TextColor color, int delay) {
            this.color = color;
            this.age = -delay;
        }
    }

    private final DuelCorePlugin plugin;
    private final Map<UUID, Shown> shown = new HashMap<>();
    /** Kit tiers when the player was last in the hub, to spot a better one. */
    private final Map<UUID, Map<String, Tier>> kitTiers = new HashMap<>();
    private final Map<UUID, Fill> fills = new HashMap<>();
    private final Map<UUID, Ring> rings = new HashMap<>();

    public HubProgress(DuelCorePlugin plugin) {
        this.plugin = plugin;
        plugin.tester().preview("xp-fill", this::previewFill);
        plugin.tester().preview("tier-ring", p -> {
            PlayerProfile profile = plugin.profiles().get(p);
            ring(p, profile == null || profile.overall() == null ? Tier.HT3 : profile.overall(), 0);
        });
    }

    // ------------------------------------------------------------------ showing

    /** Puts the bar on a player who is (back) in the lobby; animates it when it changed. Called by HubService.prepare. */
    public void show(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerProfile profile = plugin.profiles().get(player);
        if (profile == null) {
            forget(uuid);
            return;
        }
        Map<String, Tier> tiers = kitTiers(profile);
        Map<String, Tier> old = kitTiers.put(uuid, tiers);
        Tier better = old == null ? null : better(old, tiers);
        boolean animate = profile.setting(Setting.PROGRESS_REVEAL); // the player's "Rank-up animations"
        if (better != null && animate && plugin.settings().animTierRing) ring(player, better, RING_DELAY);
        if (!plugin.settings().animHubXp) {
            shown.remove(uuid);
            fills.remove(uuid);
            return; // (resetState already emptied the bar)
        }
        Shown now = current(profile);
        Shown before = shown.put(uuid, now);
        if (now.equals(before) && plugin.tester().enabled(uuid)) {
            before = shown(now.ranked(), now.ranked() ? now.level() - 25 : Math.max(0, now.level() - 1)); // as if gained
        }
        if (animate && plugin.settings().animHubXpFill && before != null && !before.equals(now)) {
            apply(player, before);
            fills.put(uuid, new Fill(before, now));
        } else {
            fills.remove(uuid);
            apply(player, now);
        }
    }

    /** Updates the bar without animations or ring (after a reload); clears it when the bar was switched off. */
    public void refresh(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerProfile profile = plugin.profiles().get(player);
        fills.remove(uuid);
        if (profile != null) kitTiers.put(uuid, kitTiers(profile));
        if (!plugin.settings().animHubXp || profile == null) {
            if (shown.remove(uuid) != null) apply(player, new Shown(false, 0, 0));
            return;
        }
        Shown now = current(profile);
        shown.put(uuid, now);
        apply(player, now);
    }

    private Shown current(PlayerProfile profile) {
        if (profile.overall() != null) return shown(true, profile.elo());
        int pm = plugin.tiers().placementMatches();
        int games = 0;
        for (KitStats s : profile.allStats().values()) games = Math.max(games, Math.min(s.games, pm));
        return shown(false, games);
    }

    /** The bar for an overall Elo (ranked) or a number of placement games. */
    private Shown shown(boolean ranked, int level) {
        int pm = plugin.tiers().placementMatches();
        return new Shown(ranked, level, ranked ? eloBar(level) : pm <= 0 ? 1 : Ease.clamp01((double) level / pm));
    }

    /** Way from the current overall tier's threshold to the next one (full at HT1). */
    private double eloBar(double elo) {
        TierLadder ladder = plugin.tiers().ladder();
        Tier tier = ladder.kitTier("overall", elo);
        if (tier == Tier.HT1) return 1;
        double hi = ladder.nextThreshold("overall", tier);
        double lo = ladder.threshold("overall", tier);
        if (tier == Tier.LT5) {
            // the floor tier starts at 0: give it the width of the tier above instead
            lo = hi - (ladder.nextThreshold("overall", Tier.values()[tier.ordinal() - 1]) - hi);
        }
        return hi <= lo ? 1 : Ease.clamp01((elo - lo) / (hi - lo));
    }

    private Map<String, Tier> kitTiers(PlayerProfile profile) {
        Map<String, Tier> out = new HashMap<>();
        for (Map.Entry<String, KitStats> e : profile.allStats().entrySet()) {
            Tier t = plugin.tiers().kitTier(e.getKey(), e.getValue());
            if (t != null) out.put(e.getKey(), t);
        }
        return out;
    }

    /** The best kit tier that is new or better than before, or null. */
    static @Nullable Tier better(Map<String, Tier> before, Map<String, Tier> after) {
        Tier best = null;
        for (Map.Entry<String, Tier> e : after.entrySet()) {
            Tier old = before.get(e.getKey());
            if (old != null && !e.getValue().isBetterThan(old)) continue;
            if (best == null || e.getValue().isBetterThan(best)) best = e.getValue();
        }
        return best;
    }

    private static void apply(Player player, Shown s) {
        player.setLevel(Math.max(0, s.level()));
        player.setExp((float) Ease.clamp01(s.bar()));
    }

    // ------------------------------------------------------------------ animations

    private void ring(Player player, Tier tier, int delay) {
        rings.put(player.getUniqueId(), new Ring(plugin.progressReveal().tierColor(tier), delay));
    }

    private void previewFill(Player player) {
        PlayerProfile profile = plugin.profiles().get(player);
        if (profile == null) return;
        Shown now = current(profile);
        shown.put(player.getUniqueId(), now);
        Shown from = shown(now.ranked(), now.ranked() ? now.level() - 40 : 0);
        apply(player, from);
        fills.put(player.getUniqueId(), new Fill(from, now));
    }

    @Override
    public void run() {
        if (fills.isEmpty() && rings.isEmpty()) return;
        for (Iterator<Map.Entry<UUID, Fill>> it = fills.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Fill> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !inLobby(p) || !fill(p, e.getValue())) it.remove();
        }
        for (Iterator<Map.Entry<UUID, Ring>> it = rings.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Ring> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !inLobby(p) || !ring(p, e.getValue())) it.remove();
        }
    }

    /** One fill step; false when done (the final value is set). */
    private boolean fill(Player p, Fill f) {
        int t = f.age - FILL_DELAY;
        f.age += PERIOD;
        if (t < 0) return true;
        double e = Ease.easeOutCubic(Ease.progress(t, FILL_TICKS));
        Shown from = f.from;
        Shown to = f.to;
        if (from.ranked() && to.ranked()) {
            double elo = Ease.lerp(from.level(), to.level(), e);
            apply(p, new Shown(true, (int) Math.round(elo), eloBar(elo)));
        } else if (!from.ranked() && !to.ranked()) {
            apply(p, new Shown(false, (int) Ease.count(from.level(), to.level(), t, FILL_TICKS, Ease.OUT_CUBIC),
                Ease.lerp(from.bar(), to.bar(), e)));
        } else {
            apply(p, new Shown(to.ranked(), to.level(), to.bar() * e)); // placed now: the new bar fills from empty
        }
        boolean up = to.ranked() != from.ranked() || to.level() >= from.level();
        if (t < FILL_TICKS && t - f.lastOrb >= ORB_SPACING && plugin.settings().animHubSounds) {
            f.lastOrb = t;
            float pitch = up ? Sfx.pitch(0.8f, (int) Math.round(12 * e)) : Sfx.pitch(1.2f, -(int) Math.round(7 * e));
            Sfx.play(plugin, p, new Sfx.Note(Sfx.ORB, pitch, 0.25f, 0));
        }
        return t < FILL_TICKS;
    }

    /** One ring step: points on a circle that widens and rises from the feet; false when done. */
    private boolean ring(Player p, Ring r) {
        int t = r.age;
        r.age += PERIOD;
        if (t < 0) return true;
        if (t >= RING_TICKS) return false;
        double e = Ease.easeOutCubic(Ease.progress(t, RING_TICKS));
        Location feet = p.getLocation();
        double radius = 0.45 + 0.6 * e;
        double y = 0.1 + 1.4 * e;
        double turn = t * 0.25;
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(r.color.value()), 1.1f - 0.4f * (float) e);
        for (int i = 0; i < RING_POINTS; i++) {
            double a = turn + i * Math.PI * 2 / RING_POINTS;
            p.spawnParticle(Particle.DUST, feet.clone().add(Math.cos(a) * radius, y, Math.sin(a) * radius), 1, 0, 0, 0, 0, dust);
        }
        if (t == 0) {
            p.spawnParticle(Particle.END_ROD, feet.clone().add(0, 0.3, 0), 6, 0.3, 0.1, 0.3, 0.03);
            if (plugin.settings().animHubSounds) Sfx.play(plugin, p, new Sfx.Note(Sfx.AMETHYST, 1.8f, 0.5f, 0));
        }
        return true;
    }

    private boolean inLobby(Player p) {
        return plugin.matches().match(p.getUniqueId()) == null && plugin.spectate().spectating(p.getUniqueId()) == null;
    }

    // ------------------------------------------------------------------ lifecycle

    private void forget(UUID uuid) {
        shown.remove(uuid);
        kitTiers.remove(uuid);
        fills.remove(uuid);
        rings.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    /** Plugin disable: takes the bar off players in the lobby (it is saved with the player) and forgets everything. */
    public void clearAll() {
        for (UUID uuid : shown.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && inLobby(p)) apply(p, new Shown(false, 0, 0));
        }
        shown.clear();
        kitTiers.clear();
        fills.clear();
        rings.clear();
    }
}
