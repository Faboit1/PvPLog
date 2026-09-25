package top.cheesesmp.duelcore.ui.dialog;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.PotionContents;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Input;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.debug.TesterMode;
import top.cheesesmp.duelcore.config.GuiConfig;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.match.Participant;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.ProgressTracker.Reveal;
import top.cheesesmp.duelcore.profile.Setting;
import top.cheesesmp.duelcore.queue.QueueEntry;
import top.cheesesmp.duelcore.queue.QueueMode;
import top.cheesesmp.duelcore.queue.QueuePrefs;
import top.cheesesmp.duelcore.queue.QueueService;
import top.cheesesmp.duelcore.rating.Tier;
import top.cheesesmp.duelcore.ui.Icons;
import top.cheesesmp.duelcore.ui.anim.Animation;
import top.cheesesmp.duelcore.ui.anim.Channel;
import top.cheesesmp.duelcore.ui.anim.Ease;
import top.cheesesmp.duelcore.ui.anim.ProgressBar;
import top.cheesesmp.duelcore.ui.anim.Sfx;
import top.cheesesmp.duelcore.ui.anim.TextFx;

/**
 * The queue menu: a notice dialog with a header, category tabs (Favorites / Weapons / Vanilla / Skills), the
 * "Queue All" and "Keep Queuing" toggles and one item row per kit. Clicking a kit's text joins or leaves its queue
 * and re-opens the menu with the new state. Every click is a text click event {@code duelcore:queue/<action>}
 * (routed here through the "queue" prefix of {@link ClickRouter}); payloads are re-validated.
 *
 * <p>After a match ({@code animations.queue-progress}): when {@code plugin.progress()} has a reveal for a kit of the
 * opened tab, that kit's standing is animated by re-showing the menu every 2 ticks for about 1.7 s: the placement bar
 * fills segment by segment from the old to the new value (the new segments highlighted, then settling), or the Elo
 * counts up ("+18 Elo", the tier switching at the end), with a rising tick per step and a flourish at the end. The
 * reveal is consumed when it starts, so it plays once. The Close button (while animating), any click, key press
 * (movement, jump, sneak), hotbar or inventory action, command, camera turn or step stops it at once (Escape tells
 * the server nothing, so these are the signs that the menu is gone), and so do a match, a world change, a reload and
 * disable.
 */
public final class QueueDialog {

    public static final String FAVORITES = "favorites";

    /** Progress animation: ticks before the fill starts, of the fill (count) and of the settle (highlight fading). */
    private static final int ANIM_DELAY = 6;
    private static final int ANIM_COUNT = 20;
    private static final int ANIM_SETTLE = 8;
    /** Elo changes are counted in at most this many audible steps. */
    private static final int ELO_STEPS = 8;
    /** Ticks after the start during which walking on the ground doesn't stop the animation (the player settling). */
    private static final int MOVE_GRACE = 6;
    /** The end of a count that isn't a new tier: a short rising chime. */
    private static final List<Sfx.Note> SMALL_FLOURISH = List.of(new Sfx.Note(Sfx.CHIME, 1.26f, 0.45f, 0),
        new Sfx.Note(Sfx.CHIME, 1.59f, 0.45f, 2), new Sfx.Note(Sfx.CHIME, 2.0f, 0.45f, 4), new Sfx.Note(Sfx.AMETHYST, 1.7f, 0.8f, 5));

    /**
     * One frame of the progress animation: the reveals being animated (by kit), the eased count progress (0..1) and
     * how far the settle is (0..1).
     */
    private record MenuFrame(Map<String, Reveal> reveals, double count, double settle) {
    }

    private final DuelCorePlugin plugin;
    /** Players whose menu is animating, with the server tick it started. */
    private final Map<UUID, Integer> animating = new HashMap<>();

    QueueDialog(DuelCorePlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(new Interrupts(), plugin);
        // /animtest play <name>: the queue experience (the searching bars and match found too: QueueService and
        // MatchService are created before plugin.tester())
        TesterMode tester = plugin.tester();
        tester.preview("queue-placement", p -> preview(p, Sample.PLACEMENT));
        tester.preview("queue-placed", p -> preview(p, Sample.PLACED));
        tester.preview("queue-elo-up", p -> preview(p, Sample.ELO_UP));
        tester.preview("queue-elo-down", p -> preview(p, Sample.ELO_DOWN));
        tester.preview("queue-tier-up", p -> preview(p, Sample.TIER_UP));
        tester.preview("searching", p -> plugin.queue().searching().preview(p));
        tester.preview("match-found", p -> plugin.matches().foundReveal().preview(p));
    }

    private Messages msg() {
        return plugin.messages();
    }

    // ------------------------------------------------------------------ open

    /** Opens the menu on {@code tab}, or on the player's last tab when null. Favourites are loaded first. */
    public void open(Player player, @Nullable String tab) {
        QueuePrefs prefs = plugin.queue().prefs();
        String shown = validTab(tab != null ? tab : prefs.tab(player.getUniqueId()));
        prefs.tab(player.getUniqueId(), shown);
        if (prefs.favorites(player.getUniqueId()) != null) {
            show(player, shown);
            return;
        }
        prefs.load(player).thenRun(() -> {
            if (player.isOnline() && plugin.matches().match(player.getUniqueId()) == null) show(player, shown);
        });
    }

    /** All tab ids in menu order: favorites, then the kit categories. */
    private static List<String> tabs() {
        List<String> tabs = new ArrayList<>();
        tabs.add(FAVORITES);
        for (Kit.Category c : Kit.Category.values()) tabs.add(c.id());
        return tabs;
    }

    private String validTab(@Nullable String tab) {
        if (tab != null && tabs().contains(tab)) return tab;
        for (Kit.Category c : Kit.Category.values()) if (!kits(c.id(), Set.of()).isEmpty()) return c.id();
        return Kit.Category.WEAPONS.id();
    }

    /** Kits listed in a tab: enabled kits that have an open queue. */
    private List<Kit> kits(String tab, Set<String> favorites) {
        QueueService queue = plugin.queue();
        List<Kit> list = new ArrayList<>();
        for (Kit kit : plugin.kits().enabled()) {
            if (queue.modeFor(kit) == null) continue;
            if (tab.equals(FAVORITES) ? favorites.contains(kit.id()) : kit.category().id().equals(tab)) list.add(kit);
        }
        return list;
    }

    /** Shows the menu; a kit of this tab with a pending progress reveal starts the progress animation. */
    private void show(Player player, String tab) {
        stopAnimation(player);
        UUID uuid = player.getUniqueId();
        Map<String, Reveal> reveals = new LinkedHashMap<>();
        if (plugin.settings().animQueueProgress && plugin.matches().match(uuid) == null) {
            Set<String> favorites = Objects.requireNonNullElse(plugin.queue().prefs().favorites(uuid), Set.of());
            for (Kit kit : kits(tab, favorites)) {
                Reveal r = plugin.progress().pendingReveal(uuid, kit.id());
                if (r == null) continue;
                reveals.put(kit.id(), r);
                plugin.progress().consume(uuid, kit.id()); // plays once, even when cut short
            }
        }
        render(player, tab, reveals.isEmpty() ? null : new MenuFrame(reveals, 0, 0));
        if (!reveals.isEmpty()) animate(player, tab, reveals);
    }

    /** Builds and shows the menu; {@code frame} (animation only) replaces the standing of its kits. */
    private void render(Player player, String tab, @Nullable MenuFrame frame) {
        GuiConfig gui = plugin.gui();
        UUID uuid = player.getUniqueId();
        PlayerProfile profile = plugin.profiles().get(player);
        Set<String> favorites = Objects.requireNonNullElse(plugin.queue().prefs().favorites(uuid), Set.of());
        List<Kit> kits = kits(tab, favorites);
        long now = System.currentTimeMillis();
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(header(uuid), gui.queueWidth));
        body.add(DialogBody.plainMessage(tabRow(player, tab), gui.queueWidth));
        body.add(DialogBody.plainMessage(toggleRow(uuid, profile, kits, tab), gui.queueWidth));
        if (kits.isEmpty()) {
            body.add(DialogBody.plainMessage(msg().get(tab.equals(FAVORITES) ? "dialog.queue.no-favorites" : "dialog.queue.no-kits"),
                gui.queueWidth));
        }
        for (Kit kit : kits) body.add(kitRow(uuid, profile, kit, favorites.contains(kit.id()), tab, now, frame));
        // while animating, Close tells the server (queue/close stops it); after Escape the first key or turn does
        ActionButton close = frame == null ? ActionButton.builder(msg().get("dialog.close")).width(120).build()
            : plugin.dialogs().button(msg().get("dialog.close"), null, 120, "queue/close", Map.of());
        player.showDialog(Dialog.create(f -> f.empty()
            .base(DialogBase.builder(msg().get("dialog.queue.menu-title"))
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(body)
                .build())
            .type(DialogType.notice(close))));
    }

    // ------------------------------------------------------------------ rows

    private Component header(UUID uuid) {
        int selected = 0;
        for (QueueEntry e : plugin.queue().entries(uuid)) if (e.mode() != QueueMode.PARTY) selected++;
        String hover = plugin.settings().queueAllowMultiple ? "dialog.queue.header-hover" : "dialog.queue.header-hover-single";
        return msg().get("dialog.queue.header").hoverEvent(HoverEvent.showText(msg().get(hover, Messages.num("selected", selected))));
    }

    private Component tabRow(Player player, String selected) {
        List<Component> parts = new ArrayList<>();
        for (String tab : tabs()) {
            if (!tab.equals(FAVORITES) && !tab.equals(selected) && kits(tab, Set.of()).isEmpty()) continue;
            Component label = msg().get(tab.equals(selected) ? "dialog.queue.tab-selected" : "dialog.queue.tab",
                Messages.comp("icon", tabIcon(player, tab)), Messages.comp("name", msg().get("dialog.queue.tabs." + tab)));
            parts.add(label.hoverEvent(HoverEvent.showText(msg().get("dialog.queue.tab-hover." + tab)))
                .clickEvent(action("tab", "tab", tab)));
        }
        return Component.join(JoinConfiguration.separator(msg().get("dialog.queue.tab-separator")), parts);
    }

    private Component tabIcon(Player player, String tab) {
        String spec = plugin.gui().queueTabIcons.getOrDefault(tab, "");
        if (spec.equalsIgnoreCase("head")) return Icons.head(player.getUniqueId(), player.getName());
        if (spec.isBlank()) return Component.empty();
        return Icons.parse(spec);
    }

    private Component toggleRow(UUID uuid, @Nullable PlayerProfile profile, List<Kit> kits, String tab) {
        List<Component> parts = new ArrayList<>();
        if (plugin.settings().queueAllowMultiple && !kits.isEmpty()) {
            parts.add(toggle(allQueued(uuid, kits), "dialog.queue.queue-all", "dialog.queue.queue-all-hover", action("all", "tab", tab)));
        }
        boolean keep = profile != null && profile.setting(Setting.KEEP_QUEUING);
        parts.add(toggle(keep, "dialog.queue.keep-queuing", "dialog.queue.keep-queuing-hover", action("keep", "tab", tab)));
        return Component.join(JoinConfiguration.separator(msg().get("dialog.queue.toggle-separator")), parts);
    }

    private Component toggle(boolean on, String label, String hover, ClickEvent<?> click) {
        return msg().get(on ? "dialog.queue.toggle-on" : "dialog.queue.toggle-off", Messages.comp("label", msg().get(label)))
            .hoverEvent(HoverEvent.showText(msg().get(hover))).clickEvent(click);
    }

    private boolean allQueued(UUID uuid, List<Kit> kits) {
        for (Kit kit : kits) if (!plugin.queue().isQueued(uuid, kit.id())) return false;
        return !kits.isEmpty();
    }

    private DialogBody kitRow(UUID uuid, @Nullable PlayerProfile profile, Kit kit, boolean favorite, String tab, long now,
                              @Nullable MenuFrame frame) {
        QueueEntry entry = plugin.queue().entry(uuid, kit.id());
        int queued = plugin.queue().size(kit.id());
        int playing = playing(kit.id());
        Component star = msg().get(favorite ? "dialog.queue.star-on" : "dialog.queue.star-off")
            .hoverEvent(HoverEvent.showText(msg().get(favorite ? "dialog.queue.unstar-hover" : "dialog.queue.star-hover")))
            .clickEvent(action("favorite", "kit", kit.id(), "tab", tab));
        Component first = msg().get(entry != null ? "dialog.queue.kit-queued" : "dialog.queue.kit",
            Messages.comp("kit", kit.displayName()), Messages.num("players", queued + playing),
            Messages.text("wait", clock(entry == null ? 0 : entry.waitSeconds(now))), Messages.comp("star", star));
        Component description = Component.text()
            .append(first)
            .appendNewline()
            .append(standing(profile, kit, frame))
            .hoverEvent(HoverEvent.showText(msg().get("dialog.queue.kit-hover", Messages.text("description", kit.description()),
                Messages.num("first_to", kit.firstTo()), Messages.num("queued", queued), Messages.num("playing", playing))))
            .clickEvent(action("toggle", "kit", kit.id(), "tab", tab))
            .build();
        return DialogBody.item(icon(kit, entry != null, queued, playing))
            .description(DialogBody.plainMessage(description, plugin.gui().queueKitWidth))
            .showDecorations(false)
            .showTooltip(true)
            .build();
    }

    /** Tier + rating once placement is done, otherwise a progress bar towards it (animated in a {@code frame}). */
    private Component standing(@Nullable PlayerProfile profile, Kit kit, @Nullable MenuFrame frame) {
        Reveal reveal = frame == null ? null : frame.reveals().get(kit.id());
        if (reveal != null) return animatedStanding(reveal, frame);
        KitStats stats = profile == null ? null : profile.stats(kit.id());
        Tier tier = plugin.tiers().kitTier(kit.id(), stats);
        int placement = plugin.tiers().placementMatches();
        int games = stats == null ? 0 : stats.games;
        int remaining = Math.max(0, placement - games);
        if (tier != null || remaining == 0) {
            return msg().get("dialog.queue.kit-rank", Messages.comp("tier", plugin.tiers().format(tier)),
                Messages.num("rating", stats == null ? (int) plugin.settings().ratingDefault : (int) Math.round(stats.rating)));
        }
        GuiConfig gui = plugin.gui();
        int segments = gui.queueProgressSegments;
        int done = doneSegments(games, placement);
        List<Component> parts = new ArrayList<>(segments);
        for (int i = 0; i < segments; i++) {
            boolean filled = i < done;
            parts.add(segment(filled ? gui.queueProgressDone : gui.queueProgressTodo,
                msg().get(filled ? "dialog.queue.progress-done" : "dialog.queue.progress-todo")));
        }
        return Component.join(JoinConfiguration.noSeparators(), parts).hoverEvent(progressHover(games, placement));
    }

    /** Filled segments of the static placement bar after {@code games} of {@code placement} matches. */
    private int doneSegments(int games, int placement) {
        int segments = plugin.gui().queueProgressSegments;
        return placement <= 0 ? segments : Math.clamp(Math.round((float) segments * games / placement), 0, segments);
    }

    private HoverEvent<Component> progressHover(int games, int placement) {
        return HoverEvent.showText(msg().get("dialog.queue.progress-hover", Messages.num("remaining", Math.max(0, placement - games)),
            Messages.num("games", games), Messages.num("placement", placement)));
    }

    // ------------------------------------------------------------------ progress animation

    /**
     * A kit's standing in an animation frame, from the reveal alone (so previews work too): the placement bar filling
     * with "+N%", or the tier and Elo counting with "+N Elo"; a first tier (placed) fills the bar, then shows the tier.
     */
    private Component animatedStanding(Reveal r, MenuFrame f) {
        boolean placement = r.inPlacement() || r.placedNow();
        if (placement && !(r.placedNow() && f.count() >= 1)) {
            int gained = (int) Math.round((r.newProgress() - r.oldProgress()) * 100);
            Component change = msg().get("dialog.queue.change-progress", Messages.num("percent", Math.round(gained * f.count())));
            return msg().get("dialog.queue.standing-change", Messages.comp("standing", animatedBar(r, f)), Messages.comp("change", change))
                .hoverEvent(progressHover(r.newGames(), r.placementMatches()));
        }
        boolean switched = f.count() >= 1;
        Tier tier = switched ? r.newTier() : r.oldTier();
        Component tierText = plugin.tiers().format(tier);
        if (switched && (r.tierUp() || r.placedNow()) && f.settle() < 1) {
            // the new tier is swept by a shimmer while the frame settles
            tierText = TextFx.shimmer(plugin.tiers().label(tier), plugin.progressReveal().tierColor(tier), plugin.gui().revealShimmer,
                Ease.easeInOutSine(f.settle()), 1.6);
        }
        long rating = r.placedNow() ? r.newElo() : Math.round(Ease.lerp(r.oldElo(), r.newElo(), f.count()));
        Component standing = msg().get("dialog.queue.kit-rank", Messages.comp("tier", tierText), Messages.num("rating", rating));
        int delta = r.eloDelta();
        long shown = Math.round(Math.abs(delta) * f.count());
        Component change = r.placedNow() ? msg().get("dialog.queue.change-placed")
            : msg().get(delta > 0 ? "dialog.queue.change-elo-up" : delta < 0 ? "dialog.queue.change-elo-down" : "dialog.queue.change-elo-same",
                Messages.num("delta", shown));
        return msg().get("dialog.queue.standing-change", Messages.comp("standing", standing), Messages.comp("change", change));
    }

    /**
     * The placement bar between the reveal's old and new segment counts (the same rounding as the static bar, so the
     * last frame matches it), drawn with the toolkit bar's segments: old segments as usual, the new ones and the one
     * being filled as tinted highlight sprites in the progress-reveal bar colours, settling to the normal look.
     */
    private Component animatedBar(Reveal r, MenuFrame f) {
        GuiConfig gui = plugin.gui();
        ProgressBar look = gui.revealBar;
        ProgressBar bar = new ProgressBar(gui.queueProgressSegments, look.filled(), look.empty(), look.filledColor(),
            look.emptyColor(), look.headColor());
        int from = doneSegments(r.oldGames(), r.placementMatches());
        int to = doneSegments(r.newGames(), r.placementMatches());
        double progress = Ease.lerp(from, to, f.count()) / bar.count();
        List<Component> parts = new ArrayList<>(bar.count());
        List<ProgressBar.Segment> segments = bar.segments(progress);
        for (int i = 0; i < segments.size(); i++) {
            ProgressBar.Segment s = segments.get(i);
            parts.add(switch (s.kind()) {
                case FULL -> i < from || f.settle() >= 1 ? segment(gui.queueProgressDone, msg().get("dialog.queue.progress-done"))
                    : highlight(TextFx.lerp(bar.headColor(), bar.filledColor(), Ease.easeInOutSine(f.settle())));
                case HEAD -> highlight(TextFx.lerp(bar.emptyColor(), bar.headColor(), 0.35 + 0.65 * s.fill()));
                case EMPTY -> segment(gui.queueProgressTodo, msg().get("dialog.queue.progress-todo"));
            });
        }
        return Component.join(JoinConfiguration.noSeparators(), parts);
    }

    /** A segment in {@code color}: the gui.yml highlight sprite tinted (with head textures), else the plain square. */
    private Component highlight(TextColor color) {
        GuiConfig gui = plugin.gui();
        String spec = gui.queueProgressHighlight.trim();
        int colon = spec.indexOf(':');
        if (!gui.queueProgressDone.matches("[0-9a-f]{16,80}") || colon <= 0 || colon == spec.length() - 1) {
            return TextFx.recolor(msg().get("dialog.queue.progress-done"), color);
        }
        return Icons.sprite(spec.substring(0, colon), spec.substring(colon + 1), "■", color).shadowColor(ShadowColor.none());
    }

    /** Re-shows the menu frame by frame on the {@link Channel#DIALOG} channel. */
    private void animate(Player player, String tab, Map<String, Reveal> reveals) {
        UUID uuid = player.getUniqueId();
        GuiConfig gui = plugin.gui();
        Reveal lead = reveals.values().iterator().next(); // the one the sounds follow
        boolean placement = lead.inPlacement() || lead.placedNow();
        int delta = lead.eloDelta();
        int steps = placement ? gui.queueProgressSegments : Math.max(1, Math.min(Math.abs(delta), ELO_STEPS));
        int from = doneSegments(lead.oldGames(), lead.placementMatches());
        int to = doneSegments(lead.newGames(), lead.placementMatches());
        plugin.anim().start(player, Channel.DIALOG, new Animation() {
            int lastStep = placement ? from : 0;

            @Override
            public boolean frame(Player p, int tick) {
                // a reload (new gui.yml), a match or an interrupt ends it; the player keeps the last frame
                if (plugin.gui() != gui || plugin.matches().match(uuid) != null || !animating.containsKey(uuid)) return false;
                if (tick < ANIM_DELAY) return true;
                int t = tick - ANIM_DELAY;
                if (t == 0) return true; // (the menu already shows this frame)
                double e = Ease.easeOutCubic(Ease.progress(t, ANIM_COUNT));
                double settle = t < ANIM_COUNT ? 0 : Ease.progress(t - ANIM_COUNT, ANIM_SETTLE);
                if (plugin.settings().animQueueSounds) sounds(p, t, e);
                render(p, tab, new MenuFrame(reveals, e, settle));
                return t < ANIM_COUNT + ANIM_SETTLE;
            }

            /** A rising tick per new segment (or Elo step), a flourish once the count is done. */
            private void sounds(Player p, int t, double e) {
                int step = placement ? (int) Math.floor(Ease.lerp(from, to, e) + 1e-6) : (int) Math.floor(e * steps + 1e-9);
                if (step > lastStep && t <= ANIM_COUNT) Sfx.play(plugin, p, Sfx.tick(step, steps, placement || delta >= 0));
                lastStep = Math.max(lastStep, step);
                if (t != ANIM_COUNT) return;
                if (lead.placedNow() || lead.tierUp()) Sfx.play(plugin, p, Sfx.flourish());
                else if (lead.tierDown()) Sfx.play(plugin, p, Sfx.demotion());
                else if (!placement && delta < 0) Sfx.play(plugin, p, Sfx.settle(false));
                else Sfx.play(plugin, p, SMALL_FLOURISH);
            }

            @Override
            public void end(Player p, End reason) {
                animating.remove(uuid);
            }
        });
        if (plugin.anim().busy(player, Channel.DIALOG)) animating.put(uuid, Bukkit.getCurrentTick());
    }

    /** Stops this player's progress animation (the menu stays as it is). */
    private void stopAnimation(Player player) {
        if (animating.containsKey(player.getUniqueId())) plugin.anim().cancel(player, Channel.DIALOG);
    }

    /**
     * Signs that the player has closed the menu or is doing something else: each stops the animation, so later
     * frames don't open a closed menu again. LOWEST, so an action that opens the menu again (the Play item, /queue)
     * finds the old animation already gone.
     */
    /** Any movement key, jump, sneak or sprint held (all released = a screen just opened). */
    static boolean anyPressed(Input in) {
        return in.isForward() || in.isBackward() || in.isLeft() || in.isRight() || in.isJump() || in.isSneak()
            || in.isSprint();
    }

    private final class Interrupts implements Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onMove(PlayerMoveEvent event) {
            Integer since = animating.get(event.getPlayer().getUniqueId());
            if (since == null) return;
            Player p = event.getPlayer();
            // the camera can't turn while a dialog is open; flying or falling players may still drift, so only level
            // steps of a player who isn't flying count
            boolean walked = event.hasChangedPosition() && !p.isFlying() && event.getFrom().getY() == event.getTo().getY()
                && Bukkit.getCurrentTick() - since > MOVE_GRACE;
            if (event.hasChangedOrientation() || walked) {
                stopAnimation(p);
            }
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onInteract(PlayerInteractEvent event) {
            stopAnimation(event.getPlayer());
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onHeld(PlayerItemHeldEvent event) {
            stopAnimation(event.getPlayer());
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onDrop(PlayerDropItemEvent event) {
            stopAnimation(event.getPlayer());
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onSwap(PlayerSwapHandItemsEvent event) {
            stopAnimation(event.getPlayer());
        }

        /**
         * A movement key, jump, sneak or sprint pressed: they do nothing while a dialog is open, so it is closed. All
         * keys released is what the client sends when a screen opens (the menu itself), so that doesn't count.
         */
        @EventHandler(priority = EventPriority.LOWEST)
        public void onInput(PlayerInputEvent event) {
            if (anyPressed(event.getInput())) stopAnimation(event.getPlayer());
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onCommand(PlayerCommandPreprocessEvent event) {
            stopAnimation(event.getPlayer());
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onInventory(InventoryOpenEvent event) {
            if (event.getPlayer() instanceof Player p) stopAnimation(p);
        }
    }

    // ------------------------------------------------------------------ tester previews

    private enum Sample { PLACEMENT, PLACED, ELO_UP, ELO_DOWN, TIER_UP }

    /**
     * {@code /animtest play queue-...}: opens the menu on the first queueable kit's tab and animates a made-up change in
     * it (nothing is recorded or consumed; the next open shows the real standing again).
     */
    private void preview(Player player, Sample sample) {
        Kit kit = null;
        for (Kit k : plugin.kits().enabled()) {
            if (plugin.queue().modeFor(k) != null) {
                kit = k;
                break;
            }
        }
        if (kit == null || plugin.matches().match(player.getUniqueId()) != null) return;
        String tab = kit.category().id();
        plugin.queue().prefs().tab(player.getUniqueId(), tab);
        if (!plugin.settings().animQueueProgress) {
            open(player, tab);
            return;
        }
        int pm = plugin.tiers().placementMatches();
        double ht3 = plugin.tiers().ladder().threshold(kit.id(), Tier.HT3);
        Reveal r = switch (sample) {
            case PLACEMENT -> sample(kit.id(), Math.max(0, pm - 3), Math.max(0, pm - 3) + 1, 1000, 1026);
            case PLACED -> sample(kit.id(), Math.max(0, pm - 1), Math.max(1, pm), 1000, ht3 + 20);
            case ELO_UP -> sample(kit.id(), pm + 4, pm + 5, ht3 + 12, ht3 + 30);
            case ELO_DOWN -> sample(kit.id(), pm + 4, pm + 5, ht3 + 30, ht3 + 18);
            case TIER_UP -> sample(kit.id(), pm + 4, pm + 5, ht3 - 8, ht3 + 12);
        };
        stopAnimation(player);
        Map<String, Reveal> reveals = new LinkedHashMap<>();
        reveals.put(kit.id(), r);
        render(player, tab, new MenuFrame(reveals, 0, 0));
        animate(player, tab, reveals);
    }

    private Reveal sample(String kit, int oldGames, int newGames, double oldRating, double newRating) {
        KitStats before = new KitStats(oldRating, 0, 0);
        before.games = oldGames;
        KitStats after = new KitStats(newRating, 0, 0);
        after.games = newGames;
        return Reveal.of(kit, before, after, plugin.tiers().placementMatches(), plugin.tiers().kitTier(kit, before),
            plugin.tiers().kitTier(kit, after), true);
    }

    /** One bar segment: a solid-colour head (textures.minecraft.net id), or the plain fallback text. */
    private static Component segment(String texture, Component fallback) {
        if (!texture.matches("[0-9a-f]{16,80}")) return fallback;
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + texture + "\"}}}";
        String value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return Component.object()
            .contents(ObjectContents.playerHead().profileProperty(PlayerHeadObjectContents.property("textures", value)).hat(false).build())
            .fallback(fallback)
            .color(NamedTextColor.WHITE)
            .shadowColor(ShadowColor.none())
            .build();
    }

    /** The kit item on the left: custom name, counts as lore, no attribute/enchant tooltips; glinting while queued. */
    private ItemStack icon(Kit kit, boolean queued, int searching, int playing) {
        ItemStack item = ItemStack.of(kit.icon());
        item.setData(DataComponentTypes.CUSTOM_NAME, msg().get(queued ? "dialog.queue.item-name-queued" : "dialog.queue.item-name",
            Messages.comp("kit", kit.displayName())));
        item.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(msg().get("dialog.queue.item-lore",
            Messages.num("queued", searching), Messages.num("playing", playing)))));
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().addHiddenComponents(
            DataComponentTypes.ATTRIBUTE_MODIFIERS, DataComponentTypes.ENCHANTMENTS, DataComponentTypes.STORED_ENCHANTMENTS,
            DataComponentTypes.POTION_CONTENTS).build());
        if (queued) item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        Color tint = tint(kit.spriteSpec());
        if (tint != null && (kit.icon() == Material.POTION || kit.icon() == Material.SPLASH_POTION
            || kit.icon() == Material.LINGERING_POTION || kit.icon() == Material.TIPPED_ARROW)) {
            item.setData(DataComponentTypes.POTION_CONTENTS, PotionContents.potionContents().customColor(tint).build());
        }
        return item;
    }

    /** The "#rrggbb" tint of a sprite spec (e.g. the Pot kit's red), or null. */
    private static @Nullable Color tint(String spriteSpec) {
        int hash = spriteSpec.indexOf('#');
        if (hash < 0 || spriteSpec.length() < hash + 7) return null;
        try {
            return Color.fromRGB(Integer.parseInt(spriteSpec.substring(hash + 1, hash + 7), 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Players fighting in this kit right now. */
    private int playing(String kit) {
        int n = 0;
        for (Match m : plugin.matches().active()) {
            if (m.isOver() || !m.kit().id().equals(kit)) continue;
            for (Participant p : m.participants()) if (!p.left()) n++;
        }
        return n;
    }

    private static String clock(double seconds) {
        int s = (int) seconds;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static ClickEvent<?> action(String action, String... kv) {
        Map<String, String> payload = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) payload.put(kv[i], kv[i + 1]);
        return ClickEvent.custom(Key.key(DialogService.NS, "queue/" + action),
            BinaryTagHolder.binaryTagHolder(DialogService.snbt(payload)));
    }

    // ------------------------------------------------------------------ clicks

    /** Every {@code duelcore:queue/*} click. {@code data} is untrusted client input. */
    public void click(Player player, String action, Map<String, String> data, @Nullable DialogResponseView view) {
        stopAnimation(player);
        String tab = data.get("tab");
        switch (action) {
            case "queue/open", "queue/tab" -> open(player, tab == null ? null : validTab(tab));
            case "queue/toggle" -> {
                Kit kit = kit(data);
                if (kit == null) return;
                if (plugin.queue().isQueued(player.getUniqueId(), kit.id())) plugin.queue().leave(player, kit);
                else join(player, kit);
                reopen(player, tab);
            }
            case "queue/all" -> {
                queueAll(player, validTab(tab));
                reopen(player, tab);
            }
            case "queue/keep" -> {
                PlayerProfile profile = plugin.profiles().get(player);
                if (profile == null) return;
                profile.setting(Setting.KEEP_QUEUING, !profile.setting(Setting.KEEP_QUEUING));
                plugin.profiles().saveSettings(profile);
                reopen(player, tab);
            }
            case "queue/favorite" -> {
                Kit kit = kit(data);
                if (kit == null || plugin.queue().prefs().favorites(player.getUniqueId()) == null) return;
                plugin.queue().prefs().toggle(player, kit);
                reopen(player, tab);
            }
            // direct join: the results "Play again" button and test bots ({kit, mode?})
            case "queue/join" -> {
                Kit kit = kit(data);
                if (kit == null) return;
                QueueMode mode = QueueMode.parse(data.get("mode"));
                if (mode == null || mode == QueueMode.PARTY) mode = plugin.queue().modeFor(kit);
                if (mode == null) {
                    plugin.messages().send(player, "queue.result.mode_disabled", Messages.comp("kit", kit.displayName()));
                    return;
                }
                plugin.commands().joinQueue(player, kit, mode);
            }
            case "queue/leave" -> plugin.queue().leave(player, true);
            case "queue/close" -> {
                // the Close button of an animating menu: the animation was stopped above
            }
            default -> {
                // unknown queue action: ignore
            }
        }
    }

    private @Nullable Kit kit(Map<String, String> data) {
        Kit kit = plugin.kits().get(data.getOrDefault("kit", ""));
        return kit == null || !kit.enabled() ? null : kit;
    }

    /** Joins a kit's queue from the menu; a refusal is explained in chat. Returns true when queued. */
    private boolean join(Player player, Kit kit) {
        QueueMode mode = plugin.queue().modeFor(kit);
        QueueService.JoinResult r = mode == null ? QueueService.JoinResult.MODE_DISABLED : plugin.queue().join(player, kit, mode);
        switch (r) {
            case OK, SWITCHED, ALREADY -> {
                return true;
            }
            default -> {
                plugin.messages().send(player, "queue.result." + r.name().toLowerCase(Locale.ROOT),
                    Messages.comp("kit", kit.displayName()));
                return false;
            }
        }
    }

    /** Queue All: joins every kit of the tab, or leaves them all when every one is already queued. */
    private void queueAll(Player player, String tab) {
        if (!plugin.settings().queueAllowMultiple) return;
        Set<String> favorites = Objects.requireNonNullElse(plugin.queue().prefs().favorites(player.getUniqueId()), Set.of());
        List<Kit> kits = kits(tab, favorites);
        if (allQueued(player.getUniqueId(), kits)) {
            for (Kit kit : kits) plugin.queue().leave(player, kit);
            return;
        }
        for (Kit kit : kits) {
            if (!plugin.queue().isQueued(player.getUniqueId(), kit.id()) && !join(player, kit)) return;
        }
    }

    /** Shows the menu again with the new state, unless the click started a match or the player left. */
    private void reopen(Player player, @Nullable String tab) {
        if (!player.isOnline() || plugin.matches().match(player.getUniqueId()) != null) return;
        open(player, tab == null ? null : validTab(tab));
    }
}
