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
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.GuiConfig;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.match.Participant;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;
import top.cheesesmp.duelcore.queue.QueueEntry;
import top.cheesesmp.duelcore.queue.QueueMode;
import top.cheesesmp.duelcore.queue.QueuePrefs;
import top.cheesesmp.duelcore.queue.QueueService;
import top.cheesesmp.duelcore.rating.Tier;
import top.cheesesmp.duelcore.ui.Icons;

/**
 * The queue menu: a notice dialog with a header, category tabs (Favorites / Weapons / Vanilla / Skills), the
 * "Queue All" and "Keep Queuing" toggles and one item row per kit. Clicking a kit's text joins or leaves its queue
 * and re-opens the menu with the new state. Every click is a text click event {@code duelcore:queue/<action>}
 * (routed here through the "queue" prefix of {@link ClickRouter}); payloads are re-validated.
 */
public final class QueueDialog {

    public static final String FAVORITES = "favorites";

    private final DuelCorePlugin plugin;

    QueueDialog(DuelCorePlugin plugin) {
        this.plugin = plugin;
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

    private void show(Player player, String tab) {
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
        for (Kit kit : kits) body.add(kitRow(uuid, profile, kit, favorites.contains(kit.id()), tab, now));
        ActionButton close = ActionButton.builder(msg().get("dialog.close")).width(120).build();
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

    private DialogBody kitRow(UUID uuid, @Nullable PlayerProfile profile, Kit kit, boolean favorite, String tab, long now) {
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
            .append(standing(profile, kit))
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

    /** Tier + rating once placement is done, otherwise a progress bar towards it. */
    private Component standing(@Nullable PlayerProfile profile, Kit kit) {
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
        int done = Math.clamp(Math.round((float) segments * games / placement), 0, segments);
        List<Component> parts = new ArrayList<>(segments);
        for (int i = 0; i < segments; i++) {
            boolean filled = i < done;
            parts.add(segment(filled ? gui.queueProgressDone : gui.queueProgressTodo,
                msg().get(filled ? "dialog.queue.progress-done" : "dialog.queue.progress-todo")));
        }
        return Component.join(JoinConfiguration.noSeparators(), parts)
            .hoverEvent(HoverEvent.showText(msg().get("dialog.queue.progress-hover", Messages.num("remaining", remaining),
                Messages.num("games", games), Messages.num("placement", placement))));
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
