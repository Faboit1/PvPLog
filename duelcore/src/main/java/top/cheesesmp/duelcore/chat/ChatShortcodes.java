package top.cheesesmp.duelcore.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.function.Function;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.geo.Countries;
import top.cheesesmp.duelcore.geo.Flags;
import top.cheesesmp.duelcore.ui.Icons;

/**
 * Shortcodes in chat: {@code :SE:} becomes Sweden's flag (hover: the country and its shortcode), {@code :Name:} an
 * online player's head and name (hover: who it is). Only codes that resolve are replaced (anything else stays as
 * typed), at most gui.yml {@code chat-shortcodes.max} per message, and only in what the player typed: the message is
 * plain text by then (the client sends no formatting), so nothing else can smuggle components in.
 *
 * <p>Runs last ({@link EventPriority#HIGHEST}), after the chat filter and after party chat took its prefix off.
 */
public final class ChatShortcodes implements Listener {

    /** {@code :XX:} (a country) or {@code :Name:} (a player; Minecraft names are 3 to 16 of these). */
    static final Pattern CODE = Pattern.compile(":([A-Za-z0-9_]{2,16}):");

    private final DuelCorePlugin plugin;

    public ChatShortcodes(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.gui().chatShortcodesEnabled) return;
        event.message(apply(event.message(), plugin.gui().chatShortcodesMax, this::resolve));
    }

    /**
     * {@code message} with its first {@code max} resolvable shortcodes replaced by what {@code resolve} gives
     * (null = leave that one as typed).
     */
    static Component apply(Component message, int max, Function<String, @Nullable Component> resolve) {
        if (max <= 0) return message;
        int[] replaced = {0};
        return message.replaceText(TextReplacementConfig.builder()
            .match(CODE)
            .replacement((match, original) -> {
                if (replaced[0] >= max) return original;
                Component c = resolve.apply(match.group(1));
                if (c == null) return original;
                replaced[0]++;
                return (ComponentLike) c;
            })
            .build());
    }

    /** A flag for a listed country with a flag, a head and name for an online player, else null. */
    private @Nullable Component resolve(String token) {
        Flags flags = plugin.flags();
        if (token.length() == 2) {
            String cc = Countries.code(token);
            return cc != null && flags.hasFlag(cc) ? flags.flag(cc) : null;
        }
        Player p = Bukkit.getPlayerExact(token);
        return p == null ? null : player(p);
    }

    /** The player's head and name; the hover shows them again with their tier tag and (if they show it) country. */
    private Component player(Player p) {
        Messages msg = plugin.messages();
        Flags flags = plugin.flags();
        Component head = Icons.head(p.getUniqueId(), p.getName());
        Component tier = plugin.tags().tag(p.getUniqueId());
        String country = flags.visibleCountry(plugin.profiles().get(p.getUniqueId()));
        Component tierLine = tier.equals(Component.empty()) ? Component.empty()
            : msg.get("chat.shortcode.player-tier", Messages.comp("tier", tier));
        Component countryLine = country == null ? Component.empty()
            : msg.get("chat.shortcode.player-country", Messages.comp("flag", flags.flag(country)),
                Messages.text("country", flags.name(country)), Messages.text("code", country));
        Component hover = msg.get("chat.shortcode.player-hover", Messages.comp("head", head),
            Messages.text("name", p.getName()), Messages.comp("tier_line", tierLine), Messages.comp("country_line", countryLine));
        return msg.get("chat.shortcode.player", Messages.comp("head", head), Messages.text("name", p.getName()))
            .hoverEvent(HoverEvent.showText(hover));
    }
}
