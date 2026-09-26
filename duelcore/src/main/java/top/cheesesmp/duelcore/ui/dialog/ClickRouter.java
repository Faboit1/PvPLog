package top.cheesesmp.duelcore.ui.dialog;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.match.SpectateService;
import top.cheesesmp.duelcore.ui.MenuSound;

/**
 * Handles every {@code duelcore:*} custom click from dialogs and chat. Payloads come from the client and are
 * treated as untrusted: every id is looked up and checked again. Every handled click plays a menu sound
 * ({@link top.cheesesmp.duelcore.ui.MenuSounds}).
 */
public final class ClickRouter implements Listener {

    private static final Pattern PAIR = Pattern.compile(
        "([A-Za-z0-9_]+)\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|'((?:[^'\\\\]|\\\\.)*)')");

    /** Handles the clicks of one feature ({@code duelcore:<prefix>/...}). The data map is untrusted client input. */
    @FunctionalInterface
    public interface Handler {
        void handle(Player player, String action, Map<String, String> data, @Nullable DialogResponseView view);
    }

    private final DuelCorePlugin plugin;
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private final Map<String, Handler> handlers = new HashMap<>();

    public ClickRouter(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Routes every {@code duelcore:<prefix>/<rest>} click to {@code handler} (e.g. prefix "friend" for
     * "friend/follow"). Features register their own prefix so they don't have to touch the switch below.
     */
    public void register(String prefix, Handler handler) {
        handlers.put(prefix, handler);
    }

    @EventHandler
    public void onClick(PlayerCustomClickEvent event) {
        if (!event.getIdentifier().namespace().equals(DialogService.NS)) return;
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;
        Player player = connection.getPlayer();
        String action = event.getIdentifier().value();
        // exit actions (what Escape sends) are harmless to repeat and must never be dropped
        if (!unguarded(action)) {
            long now = System.currentTimeMillis();
            Long last = lastClick.get(player.getUniqueId());
            if (last != null && now - last < 150) return; // double-click / spam guard
            lastClick.put(player.getUniqueId(), now);
            if (lastClick.size() > 512) lastClick.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
        }
        DialogResponseView view = event.getDialogResponseView();
        Map<String, String> data = parse(event.getTag());
        // dialogs stay on screen after a click (after-action NONE) until the next one replaces them; a click that
        // shows none (started a match, sent a request, failed with a chat message, …) closes its dialog here
        OpenDialogs open = plugin.openDialogs();
        long serial = open.serial(player);
        try {
            handle(player, action, data, view);
            // every press sounds: the handler's own kind (a toggle's state, a refusal) if it played one, else this
            plugin.menuSounds().play(player, MenuSound.forClick(action));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Click '" + action + "' from " + player.getName() + " failed: " + e);
            plugin.menuSounds().play(player, MenuSound.DENY);
        } finally {
            open.afterClick(player, serial);
        }
    }

    /** The close clicks: every Close / exit button, so also Escape (queue/close and party/close from before). */
    static boolean isClose(String action) {
        return action.equals(OpenDialogs.CLOSE) || action.equals("queue/close") || action.equals("party/close");
    }

    /**
     * Clicks the spam guard never drops: the close clicks, {@code party/menu}, the exit action (Back) of the party
     * dialogs, and {@code settings/back}, the settings country dialog's. Escape runs a dialog's exit action and closes the screen (after-action CLOSE), so a dropped one would
     * leave the server tracking (and refreshing) a dialog the player closed. They only close or navigate.
     */
    static boolean unguarded(String action) {
        return isClose(action) || action.equals("party/menu") || action.equals("settings/back");
    }

    private void handle(Player player, String action, Map<String, String> data, @Nullable DialogResponseView view) {
        if (isClose(action)) {
            plugin.openDialogs().close(player);
            return;
        }
        // queue/* clicks are handled by QueueDialog (registered prefix "queue"), settings/* by SettingsDialog
        switch (action) {
            case "profile/view" -> plugin.commands().openProfile(player, data.getOrDefault("name", player.getName()), false);
            case "leaderboard/view" -> {
                String region = data.getOrDefault("region", "");
                if (!region.isEmpty() && !plugin.settings().regions.contains(region)) region = "";
                plugin.dialogs().leaderboard(player, data.getOrDefault("cat", "overall"), region.isEmpty() ? null : region);
            }
            case "spectate/find" -> plugin.dialogs().spectateSearch(player);
            case "spectate/search" -> {
                String query = view == null ? "" : java.util.Objects.requireNonNullElse(view.getText("search"), "");
                plugin.dialogs().spectate(player, query.length() > 32 ? query.substring(0, 32) : query);
            }
            case "spectate/match" -> {
                int id;
                try {
                    id = Integer.parseInt(data.getOrDefault("id", "-1"));
                } catch (NumberFormatException e) {
                    return;
                }
                Match match = plugin.matches().byId(id);
                if (match == null || match.isOver()) {
                    plugin.messages().send(player, "spectate.ended");
                    deny(player);
                    return;
                }
                plugin.openDialogs().close(player);
                SpectateService.Result r = plugin.spectate().spectate(player, match, null);
                if (r != SpectateService.Result.OK) {
                    plugin.messages().send(player, "spectate.result." + r.name().toLowerCase(Locale.ROOT));
                    deny(player);
                }
            }
            case "duel/pick" -> {
                Player target = uuidPlayer(data.get("target"));
                if (target == null) {
                    plugin.messages().send(player, "duel.result.offline");
                    deny(player);
                    return;
                }
                plugin.dialogs().duelPicker(player, target);
            }
            case "duel/send" -> {
                Player target = uuidPlayer(data.get("target"));
                Kit kit = plugin.kits().get(data.getOrDefault("kit", ""));
                if (target == null || kit == null) {
                    plugin.messages().send(player, "duel.result.offline");
                    deny(player);
                    return;
                }
                plugin.commands().sendDuel(player, target, kit);
            }
            case "duel/accept" -> {
                UUID from = uuid(data.get("from"));
                if (from != null) plugin.commands().acceptDuel(player, from);
            }
            case "duel/deny" -> {
                UUID from = uuid(data.get("from"));
                if (from != null) plugin.duels().deny(player, from);
            }
            default -> {
                int slash = action.indexOf('/');
                Handler handler = slash < 0 ? null : handlers.get(action.substring(0, slash));
                if (handler != null) handler.handle(player, action, data, view);
            }
        }
    }

    private void deny(Player player) {
        plugin.menuSounds().play(player, MenuSound.DENY);
    }

    /** Reads a flat SNBT compound of string values, e.g. {kit:"sword",mode:"ranked"}. */
    static Map<String, String> parse(@Nullable BinaryTagHolder tag) {
        Map<String, String> map = new HashMap<>();
        if (tag == null) return map;
        Matcher m = PAIR.matcher(tag.string());
        while (m.find()) {
            String value = m.group(2) != null ? m.group(2) : m.group(3);
            map.put(m.group(1), value.replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\"));
        }
        return map;
    }

    private static @Nullable UUID uuid(@Nullable String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static @Nullable Player uuidPlayer(@Nullable String raw) {
        UUID id = uuid(raw);
        return id == null ? null : Bukkit.getPlayer(id);
    }
}
