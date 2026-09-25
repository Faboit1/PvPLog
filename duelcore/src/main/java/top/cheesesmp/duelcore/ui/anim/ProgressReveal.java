package top.cheesesmp.duelcore.ui.anim;

import java.time.Duration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.GuiConfig;
import top.cheesesmp.duelcore.config.MainConfig;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.profile.ProgressTracker.Reveal;
import top.cheesesmp.duelcore.rating.Tier;

/**
 * The post-match progress animation, played once the player is back in the hub after a ranked match (the results
 * dialog may be open on top; the action bar and title still show). The action bar counts what the match changed up
 * from zero ("+20% towards your tier ▰▰▰▰▱▱▱▱▱▱" in placement, "+18 Elo 1480 → 1498" or a red "−12 Elo" counting down
 * once placed) with a tick per step, holds, blinks and fades out. A better tier gets a title celebration (the tier in
 * its tiers.yml colour, typed in and swept by a shimmer, fireworks only the player sees, a flourish), a first tier a
 * "Placed: HT3!" one, and a lower tier a quiet subtitle. Every part has its own config.yml {@code animations} switch.
 */
public final class ProgressReveal {

    /** Ticks before the count starts (the hub teleport and the results dialog settle first). */
    private static final int DELAY = 10;
    private static final int COUNT = 30;
    private static final int HOLD = 16;
    /** Ticks between two count ticks at most (a tick every frame would be noise). */
    private static final int TICK_SPACING = 4;
    /** Elo counts are split into at most this many audible steps. */
    private static final int ELO_STEPS = 8;
    private static final int TYPE_TICKS = 14;
    private static final int SHIMMER_TICKS = 28;
    private static final int TITLE_TICKS = 40;

    private final DuelCorePlugin plugin;

    public ProgressReveal(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    private MainConfig cfg() {
        return plugin.settings();
    }

    /** Plays the newest pending reveal of this player, if any (called after the hub teleport). */
    public void playPending(Player player) {
        Reveal reveal = plugin.progress().takeLatest(player.getUniqueId());
        if (reveal == null || System.currentTimeMillis() - reveal.at() > 60_000) return;
        play(player, reveal);
    }

    /** Plays a reveal now (also used by /animtest previews). */
    public void play(Player player, Reveal reveal) {
        Kit kit = plugin.kits().get(reveal.kit());
        Component kitName = kit == null ? Component.text(reveal.kit()) : kit.displayName();
        boolean celebrate = false;
        if (reveal.placedNow() && cfg().animPlaced) {
            celebrate = true;
            plugin.anim().start(player, Channel.TITLE, celebration(reveal, kitName, "progress.placed-title",
                "progress.placed-subtitle", true));
        } else if (reveal.tierUp() && cfg().animTierUp) {
            celebrate = true;
            plugin.anim().start(player, Channel.TITLE, celebration(reveal, kitName, "progress.tier-up-title",
                "progress.tier-up-subtitle", false));
        } else if (reveal.tierDown() && cfg().animTierDown) {
            plugin.anim().start(player, Channel.TITLE, demotion(reveal, kitName));
        }
        if (cfg().animProgressReveal) plugin.anim().start(player, Channel.ACTION_BAR, actionBar(reveal, !celebrate));
    }

    // ------------------------------------------------------------------ action bar

    private Animation actionBar(Reveal r, boolean settleSound) {
        GuiConfig gui = plugin.gui();
        BlinkFade settle = new BlinkFade(3, 4, cfg().animProgressFadeTicks, gui.revealFlash, gui.revealFade);
        boolean placement = r.inPlacement() || r.placedNow();
        int delta = r.eloDelta();
        int steps = placement ? gui.revealBar.count() : Math.max(1, Math.min(Math.abs(delta), ELO_STEPS));
        return new Animation() {
            int lastStep = placement ? gui.revealBar.full(r.oldProgress()) : 0;
            int lastTickAt = -TICK_SPACING;
            boolean showing;

            @Override
            public boolean frame(Player p, int tick) {
                if (tick < DELAY) return true;
                int t = tick - DELAY;
                if (t < COUNT + HOLD) {
                    double e = Ease.easeOutCubic(Ease.progress(t, COUNT));
                    int step = placement ? gui.revealBar.full(Ease.lerp(r.oldProgress(), r.newProgress(), e))
                        : (int) Math.floor(e * steps + 1e-9);
                    if (step != lastStep && t - lastTickAt >= TICK_SPACING && step > 0) {
                        lastTickAt = t;
                        if (cfg().animProgressSounds) Sfx.play(plugin, p, Sfx.tick(step, steps, placement || delta >= 0));
                    }
                    lastStep = step;
                    if (t == COUNT && settleSound && cfg().animProgressSounds) Sfx.play(plugin, p, Sfx.settle(placement || delta >= 0));
                    p.sendActionBar(text(r, e));
                    showing = true;
                    return true;
                }
                Component c = settle.apply(text(r, 1), t - COUNT - HOLD);
                if (c == null) return false;
                p.sendActionBar(c);
                return true;
            }

            @Override
            public void end(Player p, End reason) {
                if (showing && reason != End.REPLACED) p.sendActionBar(Component.empty());
            }
        };
    }

    /** The action bar at eased count progress {@code e} (0..1). */
    private Component text(Reveal r, double e) {
        Messages msg = plugin.messages();
        if (r.inPlacement() || r.placedNow()) {
            int gained = (int) Math.round((r.newProgress() - r.oldProgress()) * 100);
            // the same bar as the queue menu's (head segments, new ones highlighted while it fills)
            Component bar = plugin.dialogs().queueMenu().placementBar(r, e, e >= 1 ? 1 : 0);
            return msg.get("progress.placement", Messages.num("percent", Math.round(gained * e)), Messages.comp("bar", bar));
        }
        int delta = r.eloDelta();
        long shown = Math.round(Math.abs(delta) * e);
        long after = Math.round(Ease.lerp(r.oldElo(), r.newElo(), e));
        String key = delta > 0 ? "progress.elo-up" : delta < 0 ? "progress.elo-down" : "progress.elo-same";
        return msg.get(key, Messages.num("delta", shown), Messages.num("before", r.oldElo()), Messages.num("after", after));
    }

    // ------------------------------------------------------------------ title

    /** Tier up / placed: typewriter title with a shimmering tier, subtitle after it, fireworks and a flourish. */
    private Animation celebration(Reveal r, Component kitName, String titleKey, String subtitleKey, boolean placed) {
        Tier tier = r.newTier();
        String label = plugin.tiers().label(tier);
        TextColor base = tierColor(tier);
        TextColor shine = plugin.gui().revealShimmer;
        int wait = DELAY + COUNT;
        return new Animation() {
            boolean shown;

            @Override
            public boolean frame(Player p, int tick) {
                if (tick < wait) return true;
                int t = tick - wait;
                if (t == 0) {
                    if (cfg().animProgressSounds) Sfx.play(plugin, p, Sfx.flourish());
                    if (cfg().animCelebrationParticles && placed) totems(p);
                }
                if (cfg().animCelebrationParticles && (t == 0 || t == 6 || t == 12)) burst(p, base, t / 6);
                Component tierText = TextFx.shimmer(label, base, shine, Ease.easeInOutSine(Ease.progress(t - 4, SHIMMER_TICKS)), 1.6);
                Component title = plugin.messages().get(titleKey, Messages.comp("tier", tierText), Messages.comp("kit", kitName));
                Component subtitle = plugin.messages().get(subtitleKey, Messages.comp("tier", plugin.tiers().format(tier)),
                    Messages.comp("kit", kitName));
                boolean last = t >= TITLE_TICKS;
                p.showTitle(Title.title(TextFx.typewriter(title, Ease.progress(t + 2, TYPE_TICKS)),
                    TextFx.typewriter(subtitle, Ease.progress(t - TYPE_TICKS, 10)),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(last ? 2000 : 600), Duration.ofMillis(last ? 700 : 0))));
                shown = true;
                return !last;
            }

            @Override
            public void end(Player p, End reason) {
                if (shown && reason == End.CANCELLED) p.clearTitle();
            }
        };
    }

    /** A quiet subtitle for a lower tier, after the count. */
    private Animation demotion(Reveal r, Component kitName) {
        return Animation.sequence(Animation.delay(DELAY + COUNT), Animation.once(p -> {
            Component subtitle = plugin.messages().get("progress.tier-down-subtitle", Messages.comp("kit", kitName),
                Messages.comp("before", plugin.tiers().format(r.oldTier())), Messages.comp("after", plugin.tiers().format(r.newTier())));
            p.showTitle(Title.title(Component.empty(), subtitle,
                Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(2500), Duration.ofMillis(1000))));
            if (cfg().animProgressSounds) Sfx.play(plugin, p, Sfx.demotion());
        }));
    }

    // ------------------------------------------------------------------ particles (only the player sees them)

    /** A firework-like burst in front of the player, alternating sides. */
    private static void burst(Player p, TextColor color, int index) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
        dir.normalize();
        Vector side = new Vector(-dir.getZ(), 0, dir.getX()).multiply(index == 1 ? -1.4 : index == 2 ? 1.4 : 0);
        Location at = eye.clone().add(dir.multiply(3.0)).add(side).add(0, 0.8 + index * 0.35, 0);
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(color.value()), 1.3f);
        p.spawnParticle(Particle.FIREWORK, at, 26, 0, 0, 0, 0.13);
        p.spawnParticle(Particle.DUST, at, 18, 0.45, 0.45, 0.45, 0, dust);
        p.spawnParticle(Particle.END_ROD, at, 6, 0.2, 0.2, 0.2, 0.05);
    }

    /** Placement finished: a totem-coloured shower around the player. */
    private static void totems(Player p) {
        p.spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1.2, 0), 60, 0.6, 0.8, 0.6, 0.35);
    }

    /** The tier's colour from tiers.yml (white when it has none). */
    public TextColor tierColor(@Nullable Tier tier) {
        TextColor c = TextFx.firstColor(plugin.tiers().format(tier));
        return c == null ? TextFx.WHITE : c;
    }
}
