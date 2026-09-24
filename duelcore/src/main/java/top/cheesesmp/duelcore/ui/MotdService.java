package top.cheesesmp.duelcore.ui;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.GuiConfig;
import top.cheesesmp.duelcore.config.Messages;

/**
 * Server list MOTD from gui.yml {@code motd}: two MiniMessage lines with live numbers, optionally centered
 * (by the pixel widths of the default font), plus the hover text shown over the player count.
 */
public final class MotdService implements Listener {

    /** Width of the MOTD text area in the multiplayer screen, in GUI pixels. */
    private static final int AREA = 270;

    private final DuelCorePlugin plugin;

    public MotdService(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(PaperServerListPingEvent event) {
        GuiConfig gui = plugin.gui();
        if (!gui.motdEnabled) return;
        TagResolver tags = TagResolver.resolver(
            Messages.num("online", Bukkit.getOnlinePlayers().size()),
            Messages.num("max", event.getMaxPlayers()),
            Messages.num("live", plugin.matches().count()),
            Messages.num("fighting", plugin.matches().playersInMatches()),
            Messages.num("queued", plugin.queue().totalQueued()),
            Messages.num("kits", plugin.kits().enabled().size()));
        List<Component> lines = new ArrayList<>();
        for (String raw : gui.motdLines.subList(0, Math.min(2, gui.motdLines.size()))) {
            Component line = plugin.messages().parse(raw, tags);
            lines.add(gui.motdCenter ? center(line) : line);
        }
        event.motd(Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines));
        if (!gui.motdHover.isEmpty()) {
            event.getListedPlayers().clear();
            for (String raw : gui.motdHover) {
                String legacy = LegacyComponentSerializer.legacySection().serialize(plugin.messages().parse(raw, tags));
                event.getListedPlayers().add(new PaperServerListPingEvent.ListedPlayerInfo(legacy, UUID.randomUUID()));
            }
        }
    }

    private static Component center(Component line) {
        String plain = PlainTextComponentSerializer.plainText().serialize(line);
        int width = width(plain);
        int pad = Math.max(0, (AREA - width) / 2 / 4); // a space is 4 px
        return pad == 0 ? line : Component.text(" ".repeat(pad)).append(line);
    }

    /** Approximate pixel width in the default font (glyph + 1 px spacing, no bold). */
    static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            w += switch (c) {
                case 'i', '!', '.', ',', ':', ';', '|', '\'', '·' -> 2;
                case 'l', '`' -> 3;
                case ' ', 't', 'I', '[', ']', '(', ')', '{', '}', '"', '*' -> 4;
                case 'f', 'k', '<', '>' -> 5;
                case '@', '~' -> 7;
                default -> 6;
            };
        }
        return w;
    }
}
