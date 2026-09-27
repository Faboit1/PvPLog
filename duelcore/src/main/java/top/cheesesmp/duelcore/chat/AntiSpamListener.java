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

/**
 * Runs chat, party chat and private messages through {@link AntiSpam}, and rate-limits commands. Registered before
 * {@link ChatFilterListener} at the same priority (LOWEST), so it runs first: blocked spam is cancelled before anyone
 * else sees it, and the slur filter, party chat (HIGH), chat tags and shortcodes (HIGHEST) get the cleaned text.
 * Cancelled events are ignored. Chat arrives on async threads; {@link AntiSpam} is thread-safe.
 */
public final class AntiSpamListener implements Listener {

    public static final String BYPASS = "duelcore.antispam.bypass";
    public static final String NOTIFY = "duelcore.antispam.notify";

    /** Private messages whose first argument is the recipient. */
    private static final Set<String> TO_PLAYER = Set.of("msg", "tell", "w", "whisper", "m", "pm", "dm", "message", "t",
        "emsg", "etell", "ew", "ewhisper", "epm");
    private static final Set<String> REPLY = Set.of("r", "reply", "er", "ereply");
    private static final Set<String> PARTY = Set.of("pc", "teammsg", "tm");

    private final DuelCorePlugin plugin;

    public AntiSpamListener(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) return;
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        // "@message" is party chat for party members: check the words, keep the "@"
        String prefix = "";
        String channel = "chat";
        if (text.startsWith("@") && plugin.parties() != null && plugin.parties().route(player.getUniqueId()) != null) {
            String rest = text.substring(1).strip();
            if (rest.isEmpty()) return; // party chat drops it
            prefix = "@";
            channel = "party";
            text = rest;
        }
        AntiSpam.Result r = plugin.antiSpam().chat(player.getUniqueId(), text, channel);
        if (!r.allowed()) {
            event.setCancelled(true);
            feedback(player, r, prefix + text);
            return;
        }
        if (!r.text().equals(text)) event.message(Component.text(prefix + r.text()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) return;
        AntiSpam spam = plugin.antiSpam();
        AntiSpam.Result flood = spam.command(player.getUniqueId());
        if (!flood.allowed()) {
            event.setCancelled(true);
            feedback(player, flood, event.getMessage());
            return;
        }
        String line = event.getMessage();
        int space = line.indexOf(' ');
        if (space < 0) return;
        String label = line.substring(1, space).toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (!ChatFilterListener.MESSAGE_COMMANDS.contains(label)) return;
        int start = space + 1;
        while (start < line.length() && line.charAt(start) == ' ') start++;
        String channel;
        if (TO_PLAYER.contains(label)) {
            int end = line.indexOf(' ', start);
            if (end < 0) return; // no message yet: the command itself answers
            channel = "pm:" + line.substring(start, end).toLowerCase(Locale.ROOT);
            start = end + 1;
            while (start < line.length() && line.charAt(start) == ' ') start++;
        } else if (REPLY.contains(label)) {
            channel = "reply";
        } else if (PARTY.contains(label)) {
            channel = "party";
        } else if (label.equals("mail") || label.equals("email")) {
            channel = "mail";
        } else {
            channel = "chat"; // /me, /say: seen by everyone like chat
        }
        String text = line.substring(start);
        if (text.isBlank()) return;
        AntiSpam.Result r = spam.chat(player.getUniqueId(), text, channel);
        if (!r.allowed()) {
            event.setCancelled(true);
            feedback(player, r, line);
            return;
        }
        if (!r.text().equals(text)) event.setMessage(line.substring(0, start) + r.text());
    }

    /** Tells the player why (throttled by {@link AntiSpam}), warns before a mute, tells staff about ads and mutes. */
    private void feedback(Player player, AntiSpam.Result r, String text) {
        Messages m = plugin.messages();
        AntiSpam.Reason reason = r.reason();
        if (reason == null) return;
        if (r.mutedMillis() > 0) {
            m.send(player, "chat.anti-spam.muted-now", Messages.text("time", AntiSpam.formatDuration(r.mutedMillis())));
        } else if (r.tell()) {
            if (reason == AntiSpam.Reason.MUTED) {
                m.send(player, "chat.anti-spam.muted", Messages.text("time", AntiSpam.formatDuration(r.muteLeftMillis())));
            } else {
                m.send(player, "chat.anti-spam." + reason.key);
                if (r.warn()) m.actionBar(player, "chat.anti-spam.warning");
            }
        }
        boolean ad = reason == AntiSpam.Reason.ADVERTISING && r.tell();
        if (!ad && r.mutedMillis() <= 0) return;
        String who = player.getName();
        String detail = r.detail() == null ? "" : r.detail();
        String time = AntiSpam.formatDuration(r.mutedMillis());
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (!staff.hasPermission(NOTIFY)) continue;
                if (ad) {
                    m.send(staff, "chat.anti-spam.staff-advertising", Messages.text("player", who),
                        Messages.text("message", text), Messages.text("term", detail));
                }
                if (r.mutedMillis() > 0) {
                    m.send(staff, "chat.anti-spam.staff-muted", Messages.text("player", who), Messages.text("time", time),
                        Messages.text("reason", reason.key));
                }
            }
            if (ad) plugin.getLogger().info("[anti-spam] advertising by " + who + ": " + text + " (" + detail + ")");
            if (r.mutedMillis() > 0) plugin.getLogger().info("[anti-spam] muted " + who + " for " + time + " (" + reason.key + ")");
        });
    }
}
