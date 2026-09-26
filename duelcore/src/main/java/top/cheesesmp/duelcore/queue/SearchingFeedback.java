package top.cheesesmp.duelcore.queue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;
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
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;
import top.cheesesmp.duelcore.ui.anim.Animation;
import top.cheesesmp.duelcore.ui.anim.Channel;
import top.cheesesmp.duelcore.ui.anim.Ease;
import top.cheesesmp.duelcore.ui.anim.TextFx;

/**
 * What a searching player sees: the "searching" action bar ({@code queue.searching-action-bar}), animated with
 * {@code animations.searching-bar}: a spinner, "Searching" with pulsing dots and a soft shimmer, the colours slowly
 * cycling (gui.yml {@code searching}), plus the kits, the wait and the rating range. It is drawn every
 * {@value #FRAME} ticks (the plain bar every 2 seconds) and leaves the action bar to a hotbar hint right after a
 * switch and to action bar animations ({@code plugin.anim().busy}). {@link #stop} clears it the moment a player stops
 * searching (left the queue or matched). One timer (every 2 ticks, started by {@link QueueService}) serves every
 * searching player. Players who turned off {@link Setting#SEARCHING_BAR} don't get it. Main thread only.
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
            if (!barFrame || !wantsBar(p) || plugin.hints().recent(uuid) || plugin.anim().busy(p, Channel.ACTION_BAR)) continue;
            Search s = plugin.queue().search(uuid, now);
            Component bar = s == null ? null : actionBar(s, ticks);
            if (bar == null) continue;
            p.sendActionBar(bar);
            drawn.add(uuid);
        }
    }

    /** Whether the player is searching and gets the searching action bar (config.yml and their own setting). */
    public boolean showsBar(Player player) {
        return plugin.settings().queueSearchingActionBar && plugin.queue().isQueued(player.getUniqueId()) && wantsBar(player);
    }

    private boolean wantsBar(Player player) {
        PlayerProfile profile = plugin.profiles().get(player);
        return profile == null || profile.setting(Setting.SEARCHING_BAR);
    }

    /** The player stopped searching (or turned the bar off): the searching action bar is cleared right away. */
    public void stop(UUID uuid) {
        boolean had = drawn.remove(uuid);
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return;
        if (had && !plugin.anim().busy(p, Channel.ACTION_BAR) && !plugin.hints().recent(uuid)) p.sendActionBar(Component.empty());
    }

    /**
     * {@code /animtest play searching}: ten seconds of the searching bar for a made-up search in the first three kits
     * (the window widens in five seconds). Follows the animation switches like the real bar.
     */
    public void preview(Player player) {
        List<Kit> enabled = plugin.kits().enabled();
        if (enabled.isEmpty()) return;
        List<Kit> kits = List.copyOf(enabled.subList(0, Math.min(3, enabled.size())));
        MainConfig c = plugin.settings();
        plugin.anim().start(player, Channel.ACTION_BAR, new Preview(t -> {
            double wait = t / 20.0;
            double widened = Ease.progress(t, PREVIEW_WIDEN_TICKS);
            return new Search(kits, QueueMode.RANKED, wait, Ease.lerp(c.mmWindowInitial, c.mmWindowMax, widened), widened);
        }));
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
            tags.add(Messages.comp("searching", word(color, tick)));
        }
        return plugin.messages().get(key, tags.toArray(TagResolver[]::new));
    }

    /**
     * "Searching" in {@code color}: with a shimmer sweeping over it and three dots lighting up one after another
     * (the unlit ones dim, so the width never changes).
     */
    private Component word(TextColor color, int tick) {
        String word = PlainTextComponentSerializer.plainText().serialize(plugin.messages().get("queue.searching-word"));
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


    // ------------------------------------------------------------------ preview

    /** The {@code /animtest} searching preview: the animated action bar for a made-up search. */
    private final class Preview implements Animation {

        private final IntFunction<@Nullable Search> source;

        Preview(IntFunction<@Nullable Search> source) {
            this.source = source;
        }

        @Override
        public boolean frame(Player p, int tick) {
            if (tick >= PREVIEW_TICKS || plugin.matches().match(p.getUniqueId()) != null) return false;
            Search s = source.apply(tick);
            if (s == null || !plugin.settings().queueSearchingActionBar) return false;
            Component text = actionBar(s, tick);
            if (text != null) p.sendActionBar(text);
            return true;
        }

        @Override
        public int period() {
            return FRAME;
        }

        @Override
        public void end(Player p, End reason) {
            if (reason != End.REPLACED) p.sendActionBar(Component.empty());
        }
    }
}
