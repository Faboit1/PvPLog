package top.cheesesmp.duelcore.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.Messages;

/** Runs chat and private messages through {@link ChatFilter}: blocks slurs, masks swearing, tells staff. */
public final class ChatFilterListener implements Listener {

    /** Commands whose arguments are someone's words (private messages, /me, …). */
    private static final Set<String> MESSAGE_COMMANDS = Set.of("msg", "tell", "w", "whisper", "r", "reply", "me",
        "say", "m", "pm", "dm", "message", "teammsg", "tm", "mail", "pc");

    private final DuelCorePlugin plugin;

    public ChatFilterListener(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        ChatFilter filter = plugin.chatFilter();
        if (!filter.enabled() || player.hasPermission("duelcore.chatfilter.bypass")) return;
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        ChatFilter.Result r = filter.check(text);
        switch (r.verdict()) {
            case BLOCKED -> {
                event.setCancelled(true);
                blocked(player, text, r.matched());
            }
            case CENSORED -> event.message(Component.text(r.text()));
            case CLEAN -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        ChatFilter filter = plugin.chatFilter();
        if (!filter.enabled() || player.hasPermission("duelcore.chatfilter.bypass")) return;
        String line = event.getMessage();
        int space = line.indexOf(' ');
        if (space < 0) return;
        String label = line.substring(1, space).toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (!MESSAGE_COMMANDS.contains(label)) return;
        String args = line.substring(space + 1);
        ChatFilter.Result r = filter.check(args);
        switch (r.verdict()) {
            case BLOCKED -> {
                event.setCancelled(true);
                blocked(player, line, r.matched());
            }
            case CENSORED -> event.setMessage(line.substring(0, space + 1) + r.text());
            case CLEAN -> {
            }
        }
    }

    private void blocked(Player player, String text, String term) {
        String who = player.getName();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) plugin.messages().send(player, "chat.blocked");
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("duelcore.chatfilter.notify")) {
                    plugin.messages().send(staff, "chat.blocked-staff", Messages.text("player", who),
                        Messages.text("message", text), Messages.text("term", term));
                }
            }
            plugin.getLogger().info("[chat-filter] blocked " + who + ": " + text + " (" + term + ")");
            for (String cmd : plugin.chatFilter().commands()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("<player>", who));
            }
        });
    }
}
