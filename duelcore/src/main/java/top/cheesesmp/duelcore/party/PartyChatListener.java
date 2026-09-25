package top.cheesesmp.duelcore.party;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Party chat: a message starting with "@", or any message of a member with Party Chat on, goes only to the online
 * members of their party (and the console), with the party prefix. It runs last (HIGHEST) and skips cancelled
 * events, so the chat filter (LOWEST) has already blocked or censored the text. Runs on the async chat thread and
 * only reads {@link PartyService.ChatRoute} snapshots.
 */
public final class PartyChatListener implements Listener {

    private static final String SHORTCUT = "@";

    private final PartyService parties;

    PartyChatListener(PartyService parties) {
        this.parties = parties;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        PartyService.ChatRoute route = parties.route(event.getPlayer().getUniqueId());
        if (route == null) return;
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        boolean shortcut = text.startsWith(SHORTCUT);
        if (!shortcut && !route.chat()) return;
        if (shortcut) {
            String rest = text.substring(SHORTCUT.length()).strip();
            if (rest.isEmpty()) {
                event.setCancelled(true);
                return;
            }
            event.message(Component.text(rest));
        }
        event.viewers().removeIf(viewer -> viewer instanceof Player p && !route.members().contains(p.getUniqueId()));
        event.renderer((source, displayName, message, viewer) -> parties.chatLine(displayName, message));
    }
}
