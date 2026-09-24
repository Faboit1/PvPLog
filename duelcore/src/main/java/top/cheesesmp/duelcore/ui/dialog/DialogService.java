package top.cheesesmp.duelcore.ui.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.GuiConfig;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.db.dao.LeaderboardDao;
import top.cheesesmp.duelcore.db.dao.MatchDao;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.leaderboard.LeaderboardService;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.match.Participant;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;
import top.cheesesmp.duelcore.queue.QueueMode;
import top.cheesesmp.duelcore.rating.Tier;
import top.cheesesmp.duelcore.rating.TierService;
import top.cheesesmp.duelcore.ui.Icons;

/**
 * All menus as 1.21.6+ dialogs. Buttons use fixed custom-click keys ({@code duelcore:<action>}) with a small SNBT
 * payload, handled by {@link ClickRouter}: nothing is stored per click, and every payload is re-validated.
 */
public final class DialogService {

    public static final String NS = "duelcore";

    private final DuelCorePlugin plugin;

    public DialogService(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    // ------------------------------------------------------------------ building blocks

    static String snbt(Map<String, String> payload) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : payload.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(e.getKey()).append(":\"")
                .append(e.getValue().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    private static Map<String, String> payload(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) map.put(kv[i], kv[i + 1]);
        return map;
    }

    private ActionButton button(Component label, @Nullable Component tooltip, int width, @Nullable String action,
                                Map<String, String> payload) {
        ActionButton.Builder b = ActionButton.builder(label).width(Math.clamp(width, 1, 1024));
        if (tooltip != null) b.tooltip(tooltip);
        if (action != null) {
            b.action(DialogAction.customClick(Key.key(NS, action),
                payload.isEmpty() ? null : BinaryTagHolder.binaryTagHolder(snbt(payload))));
        }
        return b.build();
    }

    private ActionButton close() {
        return button(msg().get("dialog.close"), null, 120, null, Map.of());
    }

    private static Dialog dialog(Component title, List<DialogBody> body, List<DialogInput> inputs, DialogType type) {
        return Dialog.create(f -> f.empty()
            .base(DialogBase.builder(title)
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(body)
                .inputs(inputs)
                .build())
            .type(type));
    }

    private DialogBody text(Component content) {
        return DialogBody.plainMessage(content, plugin.gui().wideWidth);
    }

    private Component lines(List<Component> lines) {
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    private String modeLabel(QueueMode mode) {
        return msg().raw("mode." + mode.id());
    }

    // ------------------------------------------------------------------ queue

    public void queue(Player player, QueueMode mode, boolean extra) {
        GuiConfig gui = plugin.gui();
        PlayerProfile profile = plugin.profiles().get(player);
        Kit.Category category = extra ? Kit.Category.EXTRA : Kit.Category.MAIN;
        List<ActionButton> buttons = new ArrayList<>();
        for (Kit kit : plugin.kits().enabled(category)) {
            if (mode == QueueMode.RANKED && !kit.ranked()) continue;
            KitStats stats = profile == null ? null : profile.stats(kit.id());
            Tier tier = plugin.tiers().kitTier(kit.id(), stats);
            Component label = msg().get("dialog.queue.kit-button", Messages.comp("kit_icon", kit.sprite()),
                Messages.comp("kit", kit.displayName()), Messages.num("queued", plugin.queue().size(kit.id(), mode)));
            Component tooltip = msg().get("dialog.queue.kit-tooltip",
                Messages.comp("kit", kit.displayName()),
                Messages.text("description", kit.description()),
                Messages.num("queued", plugin.queue().size(kit.id(), mode)),
                Messages.num("live", plugin.matches().count(kit.id())),
                Messages.comp("tier", plugin.tiers().format(tier)),
                Messages.num("rating", stats == null ? (int) plugin.settings().ratingDefault : (int) Math.round(stats.rating)),
                Messages.num("first_to", kit.firstTo()));
            buttons.add(button(label, tooltip, gui.kitButtonWidth, "queue/join", payload("kit", kit.id(), "mode", mode.id())));
        }
        int width = gui.kitButtonWidth;
        buttons.add(button(msg().get(extra ? "dialog.queue.main-kits" : "dialog.queue.extra-kits"), null, width,
            "queue/page", payload("mode", mode.id(), "extra", String.valueOf(!extra))));
        QueueMode other = mode == QueueMode.RANKED ? QueueMode.UNRANKED : QueueMode.RANKED;
        buttons.add(button(msg().get("dialog.queue.switch-mode", Messages.text("mode", modeLabel(other))), null, width,
            "queue/page", payload("mode", other.id(), "extra", String.valueOf(extra))));
        if (plugin.queue().isQueued(player.getUniqueId())) {
            buttons.add(button(msg().get("dialog.queue.leave"), null, width, "queue/leave", Map.of()));
        }
        Component body = msg().get("dialog.queue.body", Messages.text("mode", modeLabel(mode)),
            Messages.num("queued", plugin.queue().totalQueued()), Messages.num("live", plugin.matches().count()),
            Messages.text("category", msg().raw(extra ? "dialog.queue.category-extra" : "dialog.queue.category-main")));
        Dialog d = dialog(msg().get("dialog.queue.title", Messages.text("mode", modeLabel(mode))), List.of(text(body)), List.of(),
            DialogType.multiAction(buttons).columns(gui.kitColumns).exitAction(close()).build());
        player.showDialog(d);
    }

    // ------------------------------------------------------------------ profile

    public void profile(Player viewer, PlayerProfile target, boolean legacy) {
        var history = target.recent();
        if (history == null) {
            plugin.profiles().history(target, Math.max(1, plugin.gui().historyLines)).thenAccept(list ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (plugin.profiles().get(target.uuid()) == target) target.recent(list);
                    if (viewer.isOnline()) showProfile(viewer, target, list, legacy);
                }));
            return;
        }
        showProfile(viewer, target, history, legacy);
    }

    private void showProfile(Player viewer, PlayerProfile target, List<MatchDao.HistoryEntry> history, boolean legacy) {
        List<Component> body = new ArrayList<>();
        int wins = target.totalWins();
        int losses = target.totalLosses();
        int games = wins + losses;
        body.add(msg().get("dialog.profile.header",
            Messages.comp("head", Icons.head(target.uuid(), target.name())),
            Messages.text("player", target.name()),
            Messages.comp("tier", plugin.tiers().format(target.overall())),
            Messages.text("elo", TierService.eloText(target)),
            Messages.text("region", target.region() == null ? "—" : target.region()),
            Messages.text("country", target.country() == null ? "—" : target.country())));
        body.add(msg().get("dialog.profile.record", Messages.num("wins", wins), Messages.num("losses", losses),
            Messages.text("winrate", games == 0 ? "0" : String.valueOf(Math.round(100.0 * wins / games)))));
        if (legacy) body.add(msg().get("dialog.profile.legacy"));
        body.add(Component.empty());
        boolean any = false;
        for (Kit kit : plugin.kits().enabled()) {
            KitStats s = target.stats(kit.id());
            if (s == null || s.games == 0 && s.tierOverride == null) continue;
            any = true;
            Tier tier = plugin.tiers().kitTier(kit.id(), s);
            String key = tier == null ? "dialog.profile.kit-line-placement" : "dialog.profile.kit-line";
            body.add(msg().get(key, Messages.comp("kit_icon", kit.sprite()), Messages.comp("kit", kit.displayName()),
                Messages.comp("tier", plugin.tiers().format(tier)), Messages.num("rating", (int) Math.round(s.rating)),
                Messages.num("wins", s.wins), Messages.num("losses", s.losses),
                Messages.num("streak", s.streak), Messages.num("best_streak", s.bestStreak),
                Messages.num("games", s.games), Messages.num("placement", plugin.tiers().placementMatches())));
        }
        if (!any) body.add(msg().get("dialog.profile.no-games"));
        if (!history.isEmpty() && plugin.gui().historyLines > 0) {
            body.add(Component.empty());
            body.add(msg().get("dialog.profile.history-title"));
            for (MatchDao.HistoryEntry h : history) {
                Kit kit = plugin.kits().get(h.kitKey());
                int delta = Math.round(h.ratingDelta());
                body.add(msg().get(h.won() ? "dialog.profile.history-win" : "dialog.profile.history-loss",
                    Messages.comp("kit_icon", kit == null ? Component.empty() : kit.sprite()),
                    Messages.text("opponent", h.opponentName()),
                    Messages.num("you", h.roundsWon()), Messages.num("opp", h.roundsLost()),
                    Messages.text("delta", !h.ranked() ? "" : (delta >= 0 ? "+" + delta : String.valueOf(delta))),
                    Messages.text("ago", ago(h.startedAt()))));
            }
        }
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(msg().get("dialog.profile.leaderboards"), null, 150, "leaderboard/view", payload("cat", "overall")));
        buttons.add(button(msg().get("dialog.profile.recent-refresh"), null, 150, "profile/view", payload("name", target.name())));
        Dialog d = dialog(msg().get("dialog.profile.title", Messages.text("player", target.name())),
            List.of(text(lines(body))), List.of(), DialogType.multiAction(buttons).columns(2).exitAction(close()).build());
        viewer.showDialog(d);
    }

    private static String ago(long at) {
        long s = Math.max(0, (System.currentTimeMillis() - at) / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        return (s / 86400) + "d";
    }

    // ------------------------------------------------------------------ leaderboards

    public void leaderboard(Player viewer, String category, @Nullable String region) {
        String cat = category.toLowerCase(Locale.ROOT);
        if (!cat.equals(LeaderboardService.OVERALL) && plugin.kits().get(cat) == null) cat = LeaderboardService.OVERALL;
        String finalCat = cat;
        plugin.leaderboards().get(cat, region, null).thenAccept(rows ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (viewer.isOnline()) showLeaderboard(viewer, finalCat, region, rows);
            }));
    }

    private void showLeaderboard(Player viewer, String category, @Nullable String region, List<LeaderboardDao.Row> rows) {
        boolean overall = category.equals(LeaderboardService.OVERALL);
        Kit kit = overall ? null : plugin.kits().get(category);
        Component catName = overall ? msg().get("dialog.leaderboard.overall") : kit.displayName();
        List<Component> body = new ArrayList<>();
        int shown = Math.min(rows.size(), plugin.gui().leaderboardLines);
        if (shown == 0) body.add(msg().get("dialog.leaderboard.empty"));
        for (int i = 0; i < shown; i++) {
            LeaderboardDao.Row r = rows.get(i);
            Tier tier = overall ? r.tier() : (r.tier() != null ? r.tier() : plugin.tiers().ladder().kitTier(category, r.value()));
            body.add(msg().get(overall ? "dialog.leaderboard.line-overall" : "dialog.leaderboard.line-kit",
                Messages.num("rank", r.rank()),
                Messages.comp("head", Icons.head(r.uuid(), r.name())),
                Messages.text("player", r.name()),
                Messages.comp("tier", plugin.tiers().format(tier)),
                Messages.num("value", (int) Math.round(r.value())),
                Messages.num("elo", (int) Math.round(r.value())),
                Messages.num("wins", r.wins()), Messages.num("losses", r.losses()),
                Messages.text("region", r.region() == null ? "" : r.region())));
        }
        PlayerProfile own = plugin.profiles().get(viewer);
        for (LeaderboardDao.Row r : rows) {
            if (own != null && r.uuid().equals(own.uuid()) && r.rank() > shown) {
                body.add(Component.empty());
                body.add(msg().get("dialog.leaderboard.you", Messages.num("rank", r.rank()),
                    Messages.num("value", (int) Math.round(r.value())),
                    Messages.num("elo", (int) Math.round(r.value()))));
            }
        }
        List<ActionButton> buttons = new ArrayList<>();
        int small = 34;
        String reg = region == null ? "" : region;
        buttons.add(button(msg().get(overall ? "dialog.leaderboard.overall-button-selected" : "dialog.leaderboard.overall-button"),
            msg().get("dialog.leaderboard.overall"), small, "leaderboard/view", payload("cat", "overall", "region", reg)));
        for (Kit k : plugin.kits().enabled()) {
            boolean selected = k.id().equals(category);
            buttons.add(button(msg().get(selected ? "dialog.leaderboard.kit-button-selected" : "dialog.leaderboard.kit-button",
                    Messages.comp("kit_icon", k.sprite()), Messages.comp("kit", k.displayName())),
                k.displayName(), small, "leaderboard/view", payload("cat", k.id(), "region", reg)));
        }
        buttons.add(button(msg().get(region == null ? "dialog.leaderboard.all-regions-selected" : "dialog.leaderboard.all-regions"),
            msg().get("dialog.leaderboard.global"), small, "leaderboard/view", payload("cat", category, "region", "")));
        for (String r : plugin.settings().regions) {
            buttons.add(button(msg().get(r.equals(region) ? "dialog.leaderboard.region-selected" : "dialog.leaderboard.region",
                Messages.text("region", r)), null, small, "leaderboard/view", payload("cat", category, "region", r)));
        }
        Dialog d = dialog(msg().get("dialog.leaderboard.title", Messages.comp("category", catName),
                Messages.text("region", region == null ? msg().raw("dialog.leaderboard.global") : region)),
            List.of(text(lines(body))), List.of(), DialogType.multiAction(buttons).columns(8).exitAction(close()).build());
        viewer.showDialog(d);
    }

    // ------------------------------------------------------------------ settings

    public void settings(Player player) {
        PlayerProfile p = plugin.profiles().get(player);
        if (p == null) return;
        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(bool("duel_requests", "dialog.settings.duel-requests", p.setting(Setting.DUEL_REQUESTS)));
        inputs.add(bool("sidebar", "dialog.settings.sidebar", p.setting(Setting.SIDEBAR)));
        inputs.add(bool("sounds", "dialog.settings.sounds", p.setting(Setting.SOUNDS)));
        inputs.add(bool("chat_tags", "dialog.settings.chat-tags", p.setting(Setting.CHAT_TAGS)));
        inputs.add(bool("hide_hub", "dialog.settings.hide-hub", p.setting(Setting.HIDE_HUB_PLAYERS)));
        inputs.add(bool("spectators", "dialog.settings.spectators", p.setting(Setting.ALLOW_SPECTATORS)));
        List<SingleOptionDialogInput.OptionEntry> regions = new ArrayList<>();
        regions.add(SingleOptionDialogInput.OptionEntry.create("none", msg().get("dialog.settings.region-none"), p.region() == null));
        for (String r : plugin.settings().regions) {
            regions.add(SingleOptionDialogInput.OptionEntry.create(r, Component.text(r), r.equals(p.region())));
        }
        inputs.add(DialogInput.singleOption("region", msg().get("dialog.settings.region"), regions).width(250).build());
        inputs.add(DialogInput.text("country", msg().get("dialog.settings.country")).width(250)
            .initial(p.country() == null ? "" : p.country()).maxLength(2).build());
        inputs.add(DialogInput.numberRange("max_ping", msg().get("dialog.settings.max-ping"), 0f, 500f)
            .step(25f).initial((float) Math.clamp(Math.round(p.maxPing() / 25.0) * 25, 0, 500)).width(250)
            .labelFormat("%s: %s").build());
        ActionButton save = button(msg().get("dialog.settings.save"), null, 150, "settings/save", Map.of());
        ActionButton cancel = button(msg().get("dialog.settings.cancel"), null, 150, null, Map.of());
        Dialog d = dialog(msg().get("dialog.settings.title"), List.of(text(msg().get("dialog.settings.body"))), inputs,
            DialogType.confirmation(save, cancel));
        player.showDialog(d);
    }

    private DialogInput bool(String key, String label, boolean value) {
        return DialogInput.bool(key, msg().get(label)).initial(value).build();
    }

    // ------------------------------------------------------------------ spectate

    /** Spectate sort orders, in the order they are offered. The first one is the default. */
    public static final List<String> SPECTATE_SORTS = List.of("elo", "newest", "watchers");

    public void spectate(Player player) {
        spectate(player, "", SPECTATE_SORTS.getFirst());
    }

    /**
     * Live matches, filtered by {@code query} (a player name or kit, case-insensitive substring) and sorted by
     * {@code sort}: "elo" (average rating of the fighters, highest first), "newest" or "watchers".
     */
    public void spectate(Player player, String query, String sort) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        if (!SPECTATE_SORTS.contains(sort)) sort = SPECTATE_SORTS.getFirst();
        List<Match> live = new ArrayList<>();
        for (Match m : plugin.matches().active()) {
            if (m.isOver() || m.arena() == null) continue;
            if (!spectatable(m) && !player.hasPermission("duelcore.spectate.bypass")) continue;
            live.add(m);
        }
        List<Match> shown = new ArrayList<>();
        for (Match m : live) if (q.isEmpty() || matchesQuery(m, q)) shown.add(m);
        Comparator<Match> order = switch (sort) {
            case "newest" -> Comparator.comparingLong(Match::createdAt).reversed();
            case "watchers" -> Comparator.<Match>comparingInt(m -> m.spectators().size()).reversed()
                .thenComparing(Comparator.comparingDouble(DialogService::matchElo).reversed());
            default -> Comparator.comparingDouble(DialogService::matchElo).reversed();
        };
        shown.sort(order);
        Component title = msg().get("dialog.spectate.title", Messages.num("live", live.size()));
        if (live.isEmpty()) {
            player.showDialog(dialog(title, List.of(text(msg().get("dialog.spectate.none"))), List.of(),
                DialogType.notice(close())));
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(msg().get("dialog.spectate.search"), null, plugin.gui().wideWidth, "spectate/search", Map.of()));
        int limit = Math.max(1, plugin.gui().spectateLimit);
        for (Match m : shown.subList(0, Math.min(shown.size(), limit))) {
            Component label = msg().get("dialog.spectate.match", Messages.comp("kit_icon", m.kit().sprite()),
                Messages.text("red", m.teamName(0)), Messages.text("blue", m.teamName(1)),
                Messages.num("red_score", m.score(0)), Messages.num("blue_score", m.score(1)),
                Messages.num("elo", (int) Math.round(matchElo(m))));
            Component tooltip = msg().get("dialog.spectate.tooltip", Messages.comp("kit", m.kit().displayName()),
                Messages.text("mode", msg().raw("mode." + (m.ranked() ? "ranked" : "unranked"))),
                Messages.num("round", Math.max(1, m.round())), Messages.num("first_to", m.firstTo()),
                Messages.num("spectators", m.spectators().size()), Messages.num("elo", (int) Math.round(matchElo(m))));
            buttons.add(button(label, tooltip, plugin.gui().wideWidth, "spectate/match", payload("id", String.valueOf(m.id()))));
        }
        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.text("search", msg().get("dialog.spectate.search-label")).width(plugin.gui().wideWidth)
            .initial(query.strip()).maxLength(32).build());
        List<SingleOptionDialogInput.OptionEntry> sorts = new ArrayList<>();
        for (String s : SPECTATE_SORTS) {
            sorts.add(SingleOptionDialogInput.OptionEntry.create(s, msg().get("dialog.spectate.sort-" + s), s.equals(sort)));
        }
        inputs.add(DialogInput.singleOption("sort", msg().get("dialog.spectate.sort"), sorts).width(plugin.gui().wideWidth).build());
        Component body = shown.isEmpty()
            ? msg().get("dialog.spectate.no-results", Messages.text("query", query.strip()))
            : msg().get("dialog.spectate.body", Messages.num("shown", Math.min(shown.size(), limit)), Messages.num("live", live.size()));
        player.showDialog(dialog(title, List.of(text(body)), inputs,
            DialogType.multiAction(buttons).columns(1).exitAction(close()).build()));
    }

    private static boolean matchesQuery(Match m, String q) {
        if (m.kit().id().contains(q)) return true;
        String kitName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(m.kit().displayName()).toLowerCase(Locale.ROOT);
        if (kitName.contains(q)) return true;
        for (Participant p : m.participants()) {
            if (p.name().toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    /** Average rating of the fighters when the match started. */
    static double matchElo(Match m) {
        double sum = 0;
        int n = 0;
        for (Participant p : m.participants()) {
            sum += p.ratingBefore();
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    private boolean spectatable(Match m) {
        for (Participant p : m.participants()) {
            PlayerProfile profile = plugin.profiles().get(p.uuid());
            if (profile != null && !profile.setting(Setting.ALLOW_SPECTATORS)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ duel

    /** Opponent picker for a bare /duel: online players who are free. */
    public void duelPlayers(Player player) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(player) || plugin.matches().match(other.getUniqueId()) != null) continue;
            PlayerProfile p = plugin.profiles().get(other);
            if (p != null && !p.setting(Setting.DUEL_REQUESTS)) continue;
            buttons.add(button(msg().get("dialog.duel.player-button", Messages.comp("head", Icons.head(other.getUniqueId(), other.getName())),
                    Messages.text("player", other.getName()), Messages.comp("tier", plugin.tiers().format(p == null ? null : p.overall()))),
                null, plugin.gui().kitButtonWidth + 40, "duel/pick", payload("target", other.getUniqueId().toString())));
            if (buttons.size() >= 60) break;
        }
        Component title = msg().get("dialog.duel.players-title");
        if (buttons.isEmpty()) {
            player.showDialog(dialog(title, List.of(text(msg().get("dialog.duel.no-players"))), List.of(), DialogType.notice(close())));
            return;
        }
        player.showDialog(dialog(title, List.of(text(msg().get("dialog.duel.players-body"))), List.of(),
            DialogType.multiAction(buttons).columns(2).exitAction(close()).build()));
    }

    public void duelPicker(Player player, Player target) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Kit kit : plugin.kits().enabled()) {
            buttons.add(button(msg().get("dialog.duel.kit-button", Messages.comp("kit_icon", kit.sprite()),
                    Messages.comp("kit", kit.displayName())), Component.text(kit.description()), plugin.gui().kitButtonWidth,
                "duel/send", payload("target", target.getUniqueId().toString(), "kit", kit.id())));
        }
        player.showDialog(dialog(msg().get("dialog.duel.title", Messages.text("player", target.getName())),
            List.of(text(msg().get("dialog.duel.body", Messages.text("player", target.getName())))), List.of(),
            DialogType.multiAction(buttons).columns(plugin.gui().kitColumns).exitAction(close()).build()));
    }

    // ------------------------------------------------------------------ results

    /** Results screen after a match (shown once the player is back in the hub). */
    public void results(Player player, List<Component> lines, Component title, @Nullable Kit kit, @Nullable QueueMode mode) {
        List<ActionButton> buttons = new ArrayList<>();
        if (kit != null && mode != null && mode != QueueMode.PARTY) {
            buttons.add(button(msg().get("dialog.results.again", Messages.comp("kit_icon", kit.sprite()),
                Messages.comp("kit", kit.displayName())), null, 150, "queue/join", payload("kit", kit.id(), "mode", mode.id())));
        }
        buttons.add(button(msg().get("dialog.results.profile"), null, 150, "profile/view", payload("name", player.getName())));
        player.showDialog(dialog(title, List.of(text(lines(lines))), List.of(),
            DialogType.multiAction(buttons).columns(2).exitAction(close()).build()));
    }

    /** Generic info notice. */
    public void notice(Player player, Component title, Component body) {
        player.showDialog(dialog(title, List.of(text(body)), List.of(), DialogType.notice(close())));
    }

    static TagResolver[] none() {
        return new TagResolver[0];
    }
}
