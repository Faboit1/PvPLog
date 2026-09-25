package top.cheesesmp.duelcore.queue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.GuiConfig;
import top.cheesesmp.duelcore.config.MainConfig;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.ui.anim.Animation;
import top.cheesesmp.duelcore.ui.anim.Channel;
import top.cheesesmp.duelcore.ui.anim.Ease;
import top.cheesesmp.duelcore.ui.anim.TextFx;

/**
 * What a searching player sees: the "searching" action bar and the searching boss bar.
 * <ul>
 *   <li>The action bar ({@code queue.searching-action-bar}) is animated with {@code animations.searching-bar}: a
 *       spinner, "Searching" with pulsing dots and a soft shimmer, the colours slowly cycling (gui.yml
 *       {@code searching}), plus the kits, the wait and the rating range. It is drawn every {@value #FRAME} ticks
 *       (the plain bar every 2 seconds, as before) and leaves the action bar to a hotbar hint right after a switch
 *       and to action bar animations ({@code plugin.anim().busy}).</li>
 *   <li>The boss bar ({@code animations.searching-boss-bar}, "Searching · Sword +2 · 0:12") fills as the rating
 *       window widens, then sweeps back and forth, its colour stepping along. It runs on {@link Channel#BOSS_BAR},
 *       so quit, world change and disable remove it too.</li>
 * </ul>
 * {@link #stop} removes both the moment a player stops searching (left the queue or matched). One timer (every
 * 2 ticks, started by {@link QueueService}) serves every searching player. Main thread only.
 */
public final class SearchingFeedback implements Runnable {

    /** Ticks between frames of the animated bars (5 per second). */
    static final int FRAME = 4;
    /** Ticks between two plain (not animated) action bars. */
    private static final int PLAIN_EVERY = 40;
    /** Ticks per lit dot of "Searching..." */
    private static final int DOT_TICKS = 8;
    /** Ticks for one pass of the shimmer over "Searching". */
    private static final int SHIMMER_TICKS = 40;
    /** Boss bar: ticks for one sweep once the rating window has fully widened. */
    private static final int SWEEP_TICKS = 40;
    /** The tester preview's length and how long its made-up window takes to widen. */
    private static final int PREVIEW_TICKS = 200;
    private static final int PREVIEW_WIDEN_TICKS = 100;

    /**
     * What a player searches for right now: the kits, the mode of the longest-waiting entry, its wait and rating
     * range (infinite when unlimited), and how far that range has widened, 0..1 (1 once at the maximum or unlimited).
     */
    public record Search(List<Kit> kits, QueueMode mode, double waitSeconds, double range, double widened) {
    }

    private final DuelCorePlugin plugin;
    /** Players whose action bar shows the searching bar (cleared once when they stop searching). */
    private final Set<UUID> drawn = new HashSet<>();
    /** The boss bar animation running for each player (removed in its end()). */
    private final Map<UUID, Animation> bossBars = new HashMap<>();
    private int ticks;

    SearchingFeedback(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        ticks += 2;
        List<UUID> searching = plugin.queue().queuedPlayers();
        if (searching.isEmpty()) return;
        MainConfig c = plugin.settings();
        boolean barFrame = c.queueSearchingActionBar && ticks % (c.animSearchingBar ? FRAME : PLAIN_EVERY) == 0;
        long now = System.currentTimeMillis();
        for (UUID uuid : searching) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || plugin.matches().match(uuid) != null) continue;
            if (c.animSearchingBossBar && !plugin.anim().busy(p, Channel.BOSS_BAR)
                && !plugin.messages().raw("queue.searching-boss-bar").isBlank()) {
                start(p, new Bars(uuid, t -> plugin.queue().search(uuid, System.currentTimeMillis()), false, Integer.MAX_VALUE));
            }
            if (!barFrame || plugin.hints().recent(uuid) || plugin.anim().busy(p, Channel.ACTION_BAR)) continue;
            Search s = plugin.queue().search(uuid, now);
            Component bar = s == null ? null : actionBar(s, ticks);
            if (bar == null) continue;
            p.sendActionBar(bar);
            drawn.add(uuid);
        }
    }

    /** The player stopped searching: the boss bar goes and the searching action bar is cleared right away. */
    public void stop(UUID uuid) {
        boolean had = drawn.remove(uuid);
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) {
            bossBars.remove(uuid);
            return;
        }
        if (bossBars.containsKey(uuid)) plugin.anim().cancel(p, Channel.BOSS_BAR);
        if (had && !plugin.anim().busy(p, Channel.ACTION_BAR) && !plugin.hints().recent(uuid)) p.sendActionBar(Component.empty());
    }

    /**
     * {@code /animtest play searching}: ten seconds of both bars for a made-up search in the first three kits (the
     * window widens in five seconds, then the boss bar sweeps). Follows the animation switches like the real bars.
     */
    public void preview(Player player) {
        List<Kit> enabled = plugin.kits().enabled();
        if (enabled.isEmpty()) return;
        List<Kit> kits = List.copyOf(enabled.subList(0, Math.min(3, enabled.size())));
        MainConfig c = plugin.settings();
        start(player, new Bars(player.getUniqueId(), t -> {
            double wait = t / 20.0;
            double widened = Ease.progress(t, PREVIEW_WIDEN_TICKS);
            return new Search(kits, QueueMode.RANKED, wait, Ease.lerp(c.mmWindowInitial, c.mmWindowMax, widened), widened);
        }, true, PREVIEW_TICKS));
    }

    private void start(Player player, Bars bars) {
        plugin.anim().start(player, Channel.BOSS_BAR, bars);
        if (plugin.anim().busy(player, Channel.BOSS_BAR)) bossBars.put(player.getUniqueId(), bars);
    }

    // ------------------------------------------------------------------ texts

    /**
     * The placeholders of a search: {@code <kit_icon>} (every kit's icon, up to 6), {@code <kit>} (the kit, or
     * "N kits"), {@code <count>}, {@code <mode>}, {@code <wait>} and {@code <range>}. A fresh, modifiable list.
     */
    public List<TagResolver> tags(Search s) {
        List<TagResolver> tags = new ArrayList<>();
        List<Component> icons = new ArrayList<>();
        for (Kit kit : s.kits().subList(0, Math.min(s.kits().size(), 6))) icons.add(kit.sprite());
        tags.add(Messages.comp("kit_icon", Component.join(JoinConfiguration.noSeparators(), icons)));
        tags.add(Messages.comp("kit", s.kits().size() == 1 ? s.kits().getFirst().displayName()
            : plugin.messages().get("queue.kit-count", Messages.num("count", s.kits().size()))));
        tags.add(Messages.num("count", s.kits().size()));
        tags.add(Messages.text("mode", plugin.messages().raw("mode." + s.mode().id())));
        tags.add(Messages.text("wait", QueueService.formatWait(s.waitSeconds())));
        tags.add(Messages.text("range", Double.isInfinite(s.range()) ? "∞" : String.valueOf((int) s.range())));
        return tags;
    }

    /** The action bar at {@code tick}: animated or plain (null when its message is empty). */
    private @Nullable Component actionBar(Search s, int tick) {
        boolean animated = plugin.settings().animSearchingBar;
        String key = animated ? "queue.searching-animated" : "queue.searching";
        if (plugin.messages().raw(key).isBlank()) return null;
        List<TagResolver> tags = tags(s);
        if (animated) {
            TextColor color = cycle(tick);
            List<String> spinner = plugin.gui().searchingSpinner;
            tags.add(Messages.comp("spinner", Component.text(spinner.get((tick / FRAME) % spinner.size()), color)));
            tags.add(Messages.comp("searching", word(color, tick, true)));
        }
        return plugin.messages().get(key, tags.toArray(TagResolver[]::new));
    }

    /**
     * "Searching" in {@code color}: with a shimmer sweeping over it and three dots lighting up one after another
     * (the unlit ones dim, so the width never changes), or plain for the boss bar.
     */
    private Component word(TextColor color, int tick, boolean animated) {
        String word = PlainTextComponentSerializer.plainText().serialize(plugin.messages().get("queue.searching-word"));
        if (!animated) return Component.text(word, color);
        TextColor shine = TextFx.lerp(color, TextFx.WHITE, 0.65);
        TextComponent.Builder out = Component.text()
            .append(TextFx.shimmer(word, color, shine, Ease.progress(tick % (SHIMMER_TICKS + 10), SHIMMER_TICKS), 1.5));
        int lit = (tick / DOT_TICKS) % 4;
        TextColor dim = TextFx.lerp(color, TextColor.color(0x2A2C30), 0.7);
        for (int i = 0; i < 3; i++) out.append(Component.text(".", i < lit ? color : dim));
        return out.build();
    }

    /** The gui.yml searching colours, one after another, blending smoothly over one cycle. */
    private TextColor cycle(int tick) {
        GuiConfig gui = plugin.gui();
        List<TextColor> colors = gui.searchingColors;
        if (colors.size() == 1) return colors.getFirst();
        double pos = (double) (tick % gui.searchingCycleTicks) / gui.searchingCycleTicks * colors.size();
        int i = (int) pos;
        return TextFx.lerp(colors.get(i % colors.size()), colors.get((i + 1) % colors.size()), Ease.easeInOutSine(pos - i));
    }

    /**
     * The boss bar's fill: the widened share of the rating window (at least a sliver), then, once fully widened, an
     * eased sweep back and forth ({@value #SWEEP_TICKS} ticks each way). Rounded to 1%, so small changes send nothing.
     */
    static float bossProgress(double widened, int tick) {
        double v;
        if (widened < 1) {
            v = Math.max(0.02, widened);
        } else {
            double phase = (double) (Math.max(0, tick) % (2 * SWEEP_TICKS)) / SWEEP_TICKS;
            v = Ease.easeInOutSine(phase <= 1 ? phase : 2 - phase);
        }
        return (float) (Math.round(Ease.clamp01(v) * 100) / 100.0);
    }

    private static String clock(double seconds) {
        int s = (int) seconds;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    // ------------------------------------------------------------------ boss bar

    /**
     * The boss bar (and, for the preview, the action bar too) of one player. {@code source} gives the search at a
     * tick since the start; null ends the animation (no longer searching).
     */
    private final class Bars implements Animation {

        private final UUID uuid;
        private final IntFunction<@Nullable Search> source;
        private final boolean withActionBar;
        private final int length;
        private @Nullable BossBar bar;
        private boolean actionBarShown;

        Bars(UUID uuid, IntFunction<@Nullable Search> source, boolean withActionBar, int length) {
            this.uuid = uuid;
            this.source = source;
            this.withActionBar = withActionBar;
            this.length = length;
        }

        @Override
        public boolean frame(Player p, int tick) {
            MainConfig c = plugin.settings();
            if (tick >= length || plugin.matches().match(uuid) != null) return false;
            Search s = source.apply(tick);
            if (s == null) return false;
            if (withActionBar && c.queueSearchingActionBar && !plugin.anim().busy(p, Channel.ACTION_BAR)) {
                Component text = actionBar(s, tick);
                if (text != null) {
                    p.sendActionBar(text);
                    actionBarShown = true;
                }
            }
            if (!c.animSearchingBossBar) return withActionBar; // switched off (reload): the real bar ends here
            if (plugin.messages().raw("queue.searching-boss-bar").isBlank()) return withActionBar;
            Component name = name(s, tick);
            float progress = bossProgress(s.widened(), tick);
            BossBar.Color color = color(s, tick);
            if (bar == null) {
                bar = BossBar.bossBar(name, progress, color, plugin.gui().searchingBossOverlay);
                p.showBossBar(bar);
            } else {
                // unchanged values send nothing
                bar.name(name);
                bar.progress(progress);
                bar.color(color);
                bar.overlay(plugin.gui().searchingBossOverlay);
            }
            return true;
        }

        /** The title; its colour only changes once a second (with the clock), so it isn't resent every frame. */
        private Component name(Search s, int tick) {
            List<TagResolver> tags = tags(s);
            Component kits = s.kits().isEmpty() ? Component.empty() : s.kits().size() == 1 ? s.kits().getFirst().displayName()
                : plugin.messages().get("queue.kits-more", Messages.comp("kit", s.kits().getFirst().displayName()),
                    Messages.num("more", s.kits().size() - 1));
            tags.add(Messages.comp("kits", kits));
            tags.add(Messages.text("clock", clock(s.waitSeconds())));
            tags.add(Messages.comp("searching", word(cycle(tick - tick % 20), tick, false)));
            return plugin.messages().get("queue.searching-boss-bar", tags.toArray(TagResolver[]::new));
        }

        /** Steps through the gui.yml colours as the window widens; once wide, one colour per sweep. */
        private BossBar.Color color(Search s, int tick) {
            List<BossBar.Color> colors = plugin.gui().searchingBossColors;
            int n = colors.size();
            if (s.widened() < 1) return colors.get(Math.min(n - 1, (int) (s.widened() * n)));
            return colors.get((n - 1 + tick / (2 * SWEEP_TICKS)) % n);
        }

        @Override
        public int period() {
            return FRAME;
        }

        @Override
        public void end(Player p, End reason) {
            bossBars.remove(uuid, this);
            if (bar != null) p.hideBossBar(bar);
            if (actionBarShown && reason != End.REPLACED && !plugin.anim().busy(p, Channel.ACTION_BAR)) {
                p.sendActionBar(Component.empty());
            }
        }
    }
}
