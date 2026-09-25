package top.cheesesmp.duelcore.friends;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.db.dao.FollowDao.Person;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.match.SpectateService;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.ui.Icons;
import top.cheesesmp.duelcore.ui.dialog.DialogService;

/**
 * The friends dialogs (list, person, add) and their {@code duelcore:friend/…} clicks. Every click payload is
 * client input: uuids, pages and filters are parsed and checked again against the cache.
 */
public final class FriendDialogs {

    /** People per page of the friends list. */
    static final int PAGE_SIZE = 18;
    static final int COLUMNS = 3;
    static final int BUTTON_WIDTH = 110;
    /** Most online players listed in "Add Friends". */
    static final int ADD_LIMIT = 30;

    public enum Filter {
        ALL, FRIENDS, FOLLOWING, FOLLOWERS;

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }

        static Filter parse(@Nullable String raw) {
            for (Filter f : values()) if (f.id().equals(raw)) return f;
            return ALL;
        }
    }

    enum Relation { MUTUAL, FOLLOWING, FOLLOWER }

    enum Status { ONLINE, IN_MATCH, OFFLINE }

    record Entry(Person person, String name, Relation relation, Status status) {
    }

    /** Online first (fighting counts as online), then by name. */
    private static final Comparator<Entry> ORDER = Comparator.comparing((Entry e) -> e.status() == Status.OFFLINE)
        .thenComparing(e -> e.name().toLowerCase(Locale.ROOT));

    private final DuelCorePlugin plugin;
    private final FriendService service;

    FriendDialogs(DuelCorePlugin plugin, FriendService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private Messages msg() {
        return plugin.messages();
    }

    private DialogService ui() {
        return plugin.dialogs();
    }

    // ------------------------------------------------------------------ list

    public void open(Player viewer, int page, Filter filter) {
        FriendService.Graph g = service.graph(viewer.getUniqueId());
        if (g == null) {
            msg().send(viewer, "friends.loading");
            service.ensureLoaded(viewer);
            return;
        }
        List<Entry> entries = entries(g, filter);
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int p = Math.clamp(page, 0, pages - 1);
        String f = filter.id();
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ui().button(msg().get("friends.dialog.prev"), msg().get("friends.dialog.prev-tooltip"), BUTTON_WIDTH,
            "friend/open", DialogService.payload("page", String.valueOf(Math.max(0, p - 1)), "filter", f)));
        buttons.add(ui().button(msg().get("friends.dialog.add"), msg().get("friends.dialog.add-tooltip"), BUTTON_WIDTH,
            "friend/add", Map.of()));
        buttons.add(ui().button(msg().get("friends.dialog.next"), msg().get("friends.dialog.next-tooltip"), BUTTON_WIDTH,
            "friend/open", DialogService.payload("page", String.valueOf(Math.min(pages - 1, p + 1)), "filter", f)));
        buttons.add(ui().button(msg().get("friends.dialog.refresh"), msg().get("friends.dialog.refresh-tooltip"), BUTTON_WIDTH,
            "friend/refresh", DialogService.payload("page", String.valueOf(p), "filter", f)));
        buttons.add(ui().button(msg().get("friends.dialog.filter", Messages.text("filter", filterName(filter))),
            msg().get("friends.dialog.filter-tooltip"), BUTTON_WIDTH,
            "friend/open", DialogService.payload("page", "0", "filter", filter.next().id())));
        for (Entry e : entries.subList(p * PAGE_SIZE, Math.min(entries.size(), (p + 1) * PAGE_SIZE))) {
            boolean addBack = e.relation() == Relation.FOLLOWER;
            Component label = msg().get("friends.dialog.person-" + statusKey(e.status()),
                Messages.comp("head", Icons.head(e.person().uuid(), e.name())), Messages.text("player", e.name()),
                Messages.comp("suffix", addBack ? msg().get("friends.dialog.add-back") : Component.empty()));
            Component tooltip = msg().get("friends.dialog.tooltip-" + e.relation().name().toLowerCase(Locale.ROOT),
                Messages.comp("status", status(e.person().uuid(), e.status())));
            String target = e.person().uuid().toString();
            buttons.add(addBack
                ? ui().button(label, tooltip, BUTTON_WIDTH, "friend/follow",
                    DialogService.payload("target", target, "back", "list", "page", String.valueOf(p), "filter", f))
                : ui().button(label, tooltip, BUTTON_WIDTH, "friend/person",
                    DialogService.payload("target", target, "page", String.valueOf(p), "filter", f)));
        }
        List<Component> body = new ArrayList<>();
        body.add(msg().get("friends.dialog.body", Messages.num("page", p + 1), Messages.num("pages", pages),
            Messages.text("filter", filterName(filter)), Messages.num("shown", entries.size())));
        if (entries.isEmpty()) body.add(msg().get("friends.dialog.empty-" + f));
        viewer.showDialog(DialogService.dialog(msg().get("friends.dialog.title"), List.of(ui().text(ui().lines(body))),
            List.of(), DialogType.multiAction(buttons).columns(COLUMNS).exitAction(ui().close()).build()));
    }

    List<Entry> entries(FriendService.Graph g, Filter filter) {
        List<Entry> out = new ArrayList<>();
        for (Person p : g.following.values()) {
            boolean mutual = g.followers.containsKey(p.uuid());
            if (filter == Filter.FOLLOWERS && !mutual || filter == Filter.FRIENDS && !mutual) continue;
            out.add(entry(p, mutual ? Relation.MUTUAL : Relation.FOLLOWING));
        }
        if (filter == Filter.ALL || filter == Filter.FOLLOWERS) {
            for (Person p : g.followers.values()) {
                if (!g.following.containsKey(p.uuid())) out.add(entry(p, Relation.FOLLOWER));
            }
        }
        out.sort(ORDER);
        return out;
    }

    private Entry entry(Person p, Relation relation) {
        Player online = Bukkit.getPlayer(p.uuid());
        Status status = online == null ? Status.OFFLINE
            : plugin.matches().match(p.uuid()) != null ? Status.IN_MATCH : Status.ONLINE;
        return new Entry(p, online != null ? online.getName() : p.name(), relation, status);
    }

    private static String statusKey(Status s) {
        return s.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private Component status(UUID uuid, Status s) {
        Match m = s == Status.IN_MATCH ? plugin.matches().match(uuid) : null;
        return msg().get("friends.dialog.status-" + statusKey(s),
            Messages.comp("kit", m == null ? Component.empty() : m.kit().displayName()));
    }

    private String filterName(Filter f) {
        return msg().raw("friends.dialog.filter-" + f.id());
    }

    // ------------------------------------------------------------------ person

    /** Actions for one person of the list: profile, follow/unfollow, duel, spectate. */
    public void person(Player viewer, UUID target, int page, Filter filter) {
        FriendService.Graph g = service.graph(viewer.getUniqueId());
        if (g == null) {
            msg().send(viewer, "friends.loading");
            return;
        }
        Person following = g.following.get(target);
        Person follower = g.followers.get(target);
        Person known = following != null ? following : follower;
        if (known == null || target.equals(viewer.getUniqueId())) {
            open(viewer, page, filter);
            return;
        }
        Relation relation = following == null ? Relation.FOLLOWER
            : follower != null ? Relation.MUTUAL : Relation.FOLLOWING;
        Entry e = entry(known, relation);
        String name = e.name();
        List<Component> body = new ArrayList<>();
        body.add(msg().get("friends.person.header", Messages.comp("head", Icons.head(target, name)),
            Messages.text("player", name), Messages.comp("status", status(target, e.status()))));
        body.add(msg().get("friends.person." + relation.name().toLowerCase(Locale.ROOT), Messages.text("player", name)));
        if (following != null) body.add(msg().get("friends.person.since", Messages.text("ago", ago(following.since()))));

        String t = target.toString();
        String pg = String.valueOf(page);
        String f = filter.id();
        int w = 150;
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ui().button(msg().get("friends.person.profile"), null, w, "profile/view", DialogService.payload("name", name)));
        if (following != null) {
            buttons.add(ui().button(msg().get("friends.person.unfollow"), null, w, "friend/unfollow",
                DialogService.payload("target", t, "back", "person", "page", pg, "filter", f)));
        } else {
            buttons.add(ui().button(msg().get("friends.person.follow-back"), null, w, "friend/follow",
                DialogService.payload("target", t, "back", "person", "page", pg, "filter", f)));
        }
        Player online = Bukkit.getPlayer(target);
        if (online != null && e.status() == Status.ONLINE && viewer.hasPermission("duelcore.duel")
            && plugin.matches().match(viewer.getUniqueId()) == null) {
            buttons.add(ui().button(msg().get("friends.person.duel"), null, w, "friend/duel", DialogService.payload("target", t)));
        }
        if (online != null && e.status() == Status.IN_MATCH && viewer.hasPermission("duelcore.spectate")) {
            buttons.add(ui().button(msg().get("friends.person.spectate"), null, w, "friend/spectate",
                DialogService.payload("target", t)));
        }
        buttons.add(ui().button(msg().get("friends.person.back"), null, w, "friend/open",
            DialogService.payload("page", pg, "filter", f)));
        viewer.showDialog(DialogService.dialog(msg().get("friends.person.title", Messages.text("player", name)),
            List.of(ui().text(ui().lines(body))), List.of(),
            DialogType.multiAction(buttons).columns(2).exitAction(ui().close()).build()));
    }

    private static String ago(long at) {
        long s = Math.max(0, (System.currentTimeMillis() - at) / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        return (s / 86400) + "d";
    }

    // ------------------------------------------------------------------ add

    /** Online players the viewer doesn't follow yet, filtered by {@code query} (name substring). */
    public void add(Player viewer, String query) {
        FriendService.Graph g = service.graph(viewer.getUniqueId());
        if (g == null) {
            msg().send(viewer, "friends.loading");
            return;
        }
        String q = query.strip();
        if (q.length() > 16) q = q.substring(0, 16);
        String lower = q.toLowerCase(Locale.ROOT);
        List<Player> candidates = new ArrayList<>();
        boolean exact = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals(viewer) || g.following.containsKey(p.getUniqueId())) continue;
            candidates.add(p);
            if (p.getName().equalsIgnoreCase(q)) exact = true;
        }
        List<Player> shown = new ArrayList<>();
        for (Player p : candidates) if (lower.isEmpty() || p.getName().toLowerCase(Locale.ROOT).contains(lower)) shown.add(p);
        shown.sort(Comparator.comparing(p -> p.getName().toLowerCase(Locale.ROOT)));
        if (shown.size() > ADD_LIMIT) shown = shown.subList(0, ADD_LIMIT);

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ui().button(msg().get("friends.add.search"), null, BUTTON_WIDTH, "friend/search", Map.of()));
        buttons.add(ui().button(msg().get("friends.add.back"), null, BUTTON_WIDTH, "friend/open", Map.of()));
        boolean followsName = false;
        for (Person p : g.following.values()) followsName |= FriendService.name(p).equalsIgnoreCase(q);
        if (!exact && !followsName && FriendService.NAME.matcher(q).matches() && !q.equalsIgnoreCase(viewer.getName())) {
            buttons.add(ui().button(msg().get("friends.add.by-name", Messages.text("player", q)),
                msg().get("friends.add.by-name-tooltip", Messages.text("player", q)), BUTTON_WIDTH,
                "friend/follow-name", DialogService.payload("name", q)));
        }
        for (Player p : shown) {
            buttons.add(ui().button(msg().get("friends.add.player", Messages.comp("head", Icons.head(p.getUniqueId(), p.getName())),
                    Messages.text("player", p.getName())),
                msg().get("friends.add.player-tooltip", Messages.text("player", p.getName())), BUTTON_WIDTH,
                "friend/follow", DialogService.payload("target", p.getUniqueId().toString(), "back", "add", "q", q)));
        }
        Component body = candidates.isEmpty() ? msg().get("friends.add.none")
            : shown.isEmpty() ? msg().get("friends.add.no-results", Messages.text("query", q))
            : msg().get("friends.add.body", Messages.num("shown", shown.size()), Messages.num("total", candidates.size()));
        List<DialogInput> inputs = List.of(DialogInput.text("search", msg().get("friends.add.search-label"))
            .width(COLUMNS * BUTTON_WIDTH + 2 * (COLUMNS - 1)).initial(q).maxLength(16).build());
        viewer.showDialog(DialogService.dialog(msg().get("friends.add.title"), List.of(ui().text(body)), inputs,
            DialogType.multiAction(buttons).columns(COLUMNS).exitAction(ui().close()).build()));
    }

    // ------------------------------------------------------------------ profile dialog

    /** The Follow / Unfollow button on someone else's profile, or null (own profile, no permission, not loaded). */
    public @Nullable ActionButton profileButton(Player viewer, PlayerProfile target) {
        if (target.uuid().equals(viewer.getUniqueId()) || !viewer.hasPermission(FriendService.PERMISSION)) return null;
        FriendService.Graph g = service.graph(viewer.getUniqueId());
        if (g == null) return null;
        boolean following = g.following.containsKey(target.uuid());
        String key = following ? "friends.profile.unfollow"
            : g.followers.containsKey(target.uuid()) ? "friends.profile.follow-back" : "friends.profile.follow";
        return ui().button(msg().get(key), null, 150, following ? "friend/unfollow" : "friend/follow",
            DialogService.payload("target", target.uuid().toString(), "back", "profile", "name", target.name()));
    }

    // ------------------------------------------------------------------ clicks

    /** Handles {@code duelcore:friend/…}. {@code data} is untrusted client input. */
    void click(Player player, String action, Map<String, String> data, @Nullable DialogResponseView view) {
        if (!player.hasPermission(FriendService.PERMISSION)) {
            msg().send(player, "command.no-permission");
            return;
        }
        int page = Math.max(0, parseInt(data.get("page")));
        Filter filter = Filter.parse(data.get("filter"));
        UUID target = uuid(data.get("target"));
        switch (action) {
            case "friend/open" -> open(player, page, filter);
            case "friend/refresh" -> service.reload(player, () -> open(player, page, filter));
            case "friend/add" -> add(player, "");
            case "friend/search" -> add(player, view == null ? "" : Objects.requireNonNullElse(view.getText("search"), ""));
            case "friend/person" -> {
                if (target != null) person(player, target, page, filter);
            }
            case "friend/follow" -> {
                if (target != null) service.follow(player, target, back(player, data, target, page, filter));
            }
            case "friend/unfollow" -> {
                if (target != null) service.unfollow(player, target, back(player, data, target, page, filter));
            }
            case "friend/follow-name" -> {
                String name = data.getOrDefault("name", "");
                if (FriendService.NAME.matcher(name).matches()) service.followByName(player, name, () -> add(player, ""));
            }
            case "friend/duel" -> {
                Player other = target == null ? null : Bukkit.getPlayer(target);
                if (other == null || other.equals(player) || !player.hasPermission("duelcore.duel")
                    || plugin.matches().match(other.getUniqueId()) != null
                    || plugin.matches().match(player.getUniqueId()) != null) {
                    msg().send(player, "friends.not-free", Messages.text("player", other == null ? "?" : other.getName()));
                    return;
                }
                plugin.dialogs().duelPicker(player, other);
            }
            case "friend/spectate" -> {
                Player other = target == null ? null : Bukkit.getPlayer(target);
                if (other == null || !player.hasPermission("duelcore.spectate")) {
                    msg().send(player, "duel.result.offline");
                    return;
                }
                SpectateService.Result r = plugin.spectate().spectate(player, other);
                if (r != SpectateService.Result.OK) {
                    msg().send(player, "spectate.result." + r.name().toLowerCase(Locale.ROOT),
                        Messages.text("player", other.getName()));
                } else {
                    player.closeDialog();
                }
            }
            default -> {
            }
        }
    }

    /** What to show after a follow or unfollow, from the click's "back" field. */
    private @Nullable Runnable back(Player player, Map<String, String> data, UUID target, int page, Filter filter) {
        return switch (data.getOrDefault("back", "")) {
            case "list" -> () -> open(player, page, filter);
            case "person" -> () -> person(player, target, page, filter);
            case "add" -> () -> add(player, data.getOrDefault("q", ""));
            case "profile" -> {
                String name = data.getOrDefault("name", "");
                yield FriendService.NAME.matcher(name).matches()
                    ? () -> plugin.commands().openProfile(player, name, false) : null;
            }
            default -> null;
        };
    }

    private static int parseInt(@Nullable String raw) {
        if (raw == null) return 0;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static @Nullable UUID uuid(@Nullable String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
