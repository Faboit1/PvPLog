package top.cheesesmp.duelcore.ui;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.GuiConfig;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.geo.Flags;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.match.Participant;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;
import top.cheesesmp.duelcore.rating.Tier;

/**
 * Tier tags and country flags in chat, the tab list and above heads, plus the tab header and footer.
 *
 * <p>A tag is the icon of a kit followed by the player's tier in it: in the hub their best kit (best tier, then
 * highest rating), during a match the match's kit with the tier they had when it started. Nametags use one scoreboard
 * team per shown kit + tier (+ flag); team names start with the tier's rank, so the tab list is sorted best tier first.
 *
 * <p>Flags ({@link Flags}, {@code <flag>} in the gui.yml formats) go between the tag and the name. The tab list name
 * is the same for every viewer, so there only the player's own {@link Setting#SHOW_MY_FLAG} counts. Nametags come
 * from each viewer's own scoreboard: a player showing a flag is in a team of their tier, kit and country, whose prefix
 * has the flag on the boards of viewers with {@link Setting#SHOW_FLAGS} on and not on the others. Chat is rendered
 * per viewer as well.
 *
 * <p>Spectators of a match look like vanilla spectator-mode entries: a grey italic name, sorted last (their team
 * name sorts after every tier team). Their chat tag stays their normal one.
 *
 * <p>{@code <logo>} in the tab header is gui.yml {@code tab.logo} in a colour wave that moves on with every header
 * refresh ({@code animations.tab-logo}); the header is re-sent every 2 seconds anyway, so it costs no packets.
 */
public final class TagService implements Listener, Runnable {

    private static final String PREFIX = "dct_";

    /**
     * What a player's tag shows: a kit (null = none ranked yet), their tier in it (null = unranked) and the country
     * whose flag goes before their name above heads (null = none); spectators of a match are listed apart.
     */
    private record Shown(@Nullable Kit kit, @Nullable Tier tier, boolean spectator, @Nullable String flag) {

        String team() {
            if (spectator) return PREFIX + "zz_spectators"; // after every "dct_NN" tier team
            int rank = tier != null ? tier.ordinal() : kit != null ? Tier.values().length : Tier.values().length + 1;
            // kit ids are [a-z0-9_], so ".<country>" can't clash with another kit's team
            return PREFIX + String.format(Locale.ROOT, "%02d", rank) + (kit == null ? "" : "_" + kit.id())
                + (flag == null ? "" : "." + flag.toLowerCase(Locale.ROOT));
        }
    }

    private final DuelCorePlugin plugin;
    /** Rendered tag per player; read by the async chat renderer. */
    private final Map<UUID, Component> tags = new ConcurrentHashMap<>();
    /** The flag before a player's name in chat (formatted), for players who show one; read by the chat renderer. */
    private final Map<UUID, Component> chatFlags = new ConcurrentHashMap<>();
    private final Map<UUID, Shown> shown = new ConcurrentHashMap<>();
    /** Where the tab logo's colour wave is (0..1). */
    private double logoPhase;

    public TagService(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** The kit + tier (and nametag flag) a player's tag shows right now. */
    private Shown shownFor(Player player) {
        PlayerProfile profile = plugin.profiles().get(player);
        String flag = plugin.flags().shows(Flags.Place.NAMETAG) ? plugin.flags().visibleCountry(profile) : null;
        if (!plugin.flags().hasFlag(flag)) flag = null;
        Match match = plugin.matches().match(player.getUniqueId());
        if (match != null && !match.isOver()) {
            Participant p = match.participant(player.getUniqueId());
            return new Shown(match.kit(), p == null ? null : p.tierBefore(), false, flag);
        }
        boolean spectator = plugin.spectate().spectating(player.getUniqueId()) != null;
        if (spectator) flag = null;
        if (profile == null) return new Shown(null, null, spectator, null);
        Kit best = null;
        Tier bestTier = null;
        double bestRating = 0;
        for (Map.Entry<String, KitStats> e : profile.allStats().entrySet()) {
            Kit kit = plugin.kits().get(e.getKey());
            Tier tier = plugin.tiers().kitTier(e.getKey(), e.getValue());
            if (kit == null || !kit.enabled() || tier == null) continue;
            if (bestTier == null || tier.isBetterThan(bestTier)
                || (tier == bestTier && e.getValue().rating > bestRating)) {
                best = kit;
                bestTier = tier;
                bestRating = e.getValue().rating;
            }
        }
        return new Shown(best, bestTier, spectator, flag);
    }

    /** After the name in the tab list: the status icon (in a match / queueing, nothing in the lobby) and the admin star. */
    private Component suffix(Player player) {
        GuiConfig gui = plugin.gui();
        Component out = Component.empty();
        TabListing listing = plugin.tabListing(); // (null while the plugin is still enabling)
        String status = listing == null ? "" : switch (listing.status(player)) {
            case MATCH -> gui.tabStatusMatch;
            case QUEUE -> gui.tabStatusQueue;
            case LOBBY -> "";
        };
        if (!status.isBlank()) out = out.append(Component.space()).append(Icons.parse(status));
        if (player.isOp() && !gui.tabAdmin.isBlank()) out = out.append(plugin.messages().parse(gui.tabAdmin));
        return out;
    }

    /** Icon + tier, or empty for "nothing ranked" when hide-unranked is on. */
    private Component tagFor(Shown s) {
        if (s.kit() == null) {
            return plugin.gui().hideUnrankedTag ? Component.empty() : plugin.tiers().format(null);
        }
        return plugin.messages().parse(plugin.gui().tagIconFormat,
            Messages.comp("icon", s.kit().sprite()), Messages.comp("tier", plugin.tiers().format(s.tier())));
    }

    /** Creates the teams of everyone online on {@code viewer}'s (new) scoreboard. */
    public void setupTeams(Player viewer, Scoreboard sb) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Shown s = shown.get(p.getUniqueId());
            if (s == null) continue;
            Team team = team(viewer.getUniqueId(), sb, s);
            if (team != null) team.addEntry(p.getName());
        }
    }

    /** The team of {@code s} on the board of {@code viewer}, created with the prefix that viewer sees. */
    private @Nullable Team team(UUID viewer, Scoreboard sb, Shown s) {
        String name = s.team();
        Team team = sb.getTeam(name);
        if (team == null) {
            try {
                team = sb.registerNewTeam(name);
            } catch (IllegalArgumentException e) {
                return sb.getTeam(name);
            }
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
            if (s.spectator()) {
                team.color(NamedTextColor.GRAY);
                return team;
            }
            team.prefix(nametagPrefix(s, viewer));
        }
        return team;
    }

    /**
     * What stands before a name above heads on {@code viewer}'s board: gui.yml {@code tags.nametag-prefix} with the
     * tag ({@code display.nametag-tag}) and the flag when the viewer sees flags; empty when neither is shown.
     */
    private Component nametagPrefix(Shown s, UUID viewer) {
        Component tag = plugin.settings().nametagTag ? tagFor(s) : Component.empty();
        Component flag = s.flag() != null && Flags.wantsFlags(plugin.profiles().get(viewer))
            ? plugin.flags().formatted(s.flag()) : Component.empty();
        if (tag.equals(Component.empty()) && flag.equals(Component.empty())) return Component.empty();
        String format = plugin.gui().nametagPrefix;
        return plugin.messages().parse(tag.equals(Component.empty()) ? omit(format, "tier") : format,
            Messages.comp("tier", tag), Messages.comp("flag", flag));
    }

    /** {@code format} without the tag {@code <name>} and the space after it (for a part that is empty). */
    private static String omit(String format, String name) {
        return format.replace("<" + name + "> ", "").replace("<" + name + ">", "");
    }

    /** Rebuilds every tag team after a reload (formats may have changed). */
    public void refreshTeams() {
        for (Scoreboard sb : plugin.sidebar().boards().values()) {
            for (Team t : sb.getTeams()) if (t.getName().startsWith(PREFIX)) t.unregister();
        }
        shown.clear();
        for (Player p : Bukkit.getOnlinePlayers()) update(p);
    }

    /** Redraws the nametags on {@code viewer}'s own board after they switched {@link Setting#SHOW_FLAGS}. */
    public void refreshFlags(Player viewer) {
        Scoreboard sb = plugin.sidebar().boards().get(viewer.getUniqueId());
        if (sb == null) return;
        Set<String> done = new HashSet<>();
        for (Shown s : shown.values()) {
            if (s.flag() == null || !done.add(s.team())) continue;
            Team team = sb.getTeam(s.team());
            if (team != null) team.prefix(nametagPrefix(s, viewer.getUniqueId()));
        }
    }

    /**
     * Recomputes a player's tag and flag everywhere (join, match start and end, tier change, their country or
     * {@link Setting#SHOW_MY_FLAG} changed).
     */
    public void update(Player player) {
        Shown s = shownFor(player);
        Component tag = tagFor(s);
        tags.put(player.getUniqueId(), tag);
        String country = plugin.flags().visibleCountry(plugin.profiles().get(player));
        Component chatFlag = plugin.flags().shows(Flags.Place.CHAT) ? plugin.flags().formatted(country) : Component.empty();
        if (chatFlag.equals(Component.empty())) chatFlags.remove(player.getUniqueId());
        else chatFlags.put(player.getUniqueId(), chatFlag);
        Shown before = shown.put(player.getUniqueId(), s);
        Component suffix = suffix(player);
        boolean plain = suffix.equals(Component.empty());
        // the tab list looks the same to everyone: only the player's own Show my flag applies
        Component tabFlag = plugin.flags().shows(Flags.Place.TAB) ? plugin.flags().formatted(country) : Component.empty();
        boolean tabTag = plugin.settings().tabTag && !tag.equals(Component.empty());
        if (s.spectator()) {
            player.playerListName(plugin.messages().parse(plugin.gui().tabSpectatorFormat, Messages.comp("tier", tag),
                Messages.comp("flag", Component.empty()), Messages.text("name", player.getName())).append(suffix));
        } else if (tabTag || !tabFlag.equals(Component.empty())) {
            String format = plugin.gui().tabFormat;
            player.playerListName(plugin.messages().parse(tabTag ? format : omit(format, "tier"),
                Messages.comp("tier", tabTag ? tag : Component.empty()), Messages.comp("flag", tabFlag),
                Messages.text("name", player.getName())).append(suffix));
        } else if (!plain) {
            player.playerListName(Component.text(player.getName()).append(suffix));
        } else {
            player.playerListName(null);
        }
        plugin.sidebar().board(player);
        String name = player.getName();
        for (Map.Entry<UUID, Scoreboard> board : plugin.sidebar().boards().entrySet()) {
            Scoreboard sb = board.getValue();
            Team current = sb.getEntryTeam(name);
            if (current != null && current.getName().equals(s.team())) continue;
            if (current != null && current.getName().startsWith(PREFIX)) {
                current.removeEntry(name);
                if (current.getEntries().isEmpty()) current.unregister();
            }
            Team team = team(board.getKey(), sb, s);
            if (team != null) team.addEntry(name);
        }
        if (before == null || before.spectator() != s.spectator()) header(player);
    }

    /** The rendered tag of an online player (icon + tier of their best kit in the hub), empty when none. */
    public Component tag(UUID player) {
        return tags.getOrDefault(player, Component.empty());
    }

    /** Tab header and footer for everyone; runs every 2 seconds. */
    @Override
    public void run() {
        if (plugin.settings().animTabLogo) logoPhase = (logoPhase + plugin.gui().tabLogoStep) % 1.0;
        for (Player p : Bukkit.getOnlinePlayers()) header(p);
    }

    private Component logo() {
        GuiConfig gui = plugin.gui();
        return WaveText.wave(gui.tabLogoText, gui.tabLogoFrom, gui.tabLogoTo, logoPhase,
            gui.tabLogoBold ? new TextDecoration[] {TextDecoration.BOLD} : new TextDecoration[0]);
    }

    private void header(Player player) {
        GuiConfig gui = plugin.gui();
        if (gui.tabHeader.isEmpty() && gui.tabFooter.isEmpty()) return;
        // the match this player is fighting in or watching, for <watching>
        Match match = plugin.matches().match(player.getUniqueId());
        if (match == null) match = plugin.spectate().spectating(player.getUniqueId());
        TagResolver tags = TagResolver.resolver(
            Messages.num("online", Bukkit.getOnlinePlayers().size()),
            Messages.num("live", plugin.matches().count()),
            Messages.num("fighting", plugin.matches().playersInMatches()),
            Messages.num("queued", plugin.queue().totalQueued()),
            Messages.num("spectators", plugin.spectate().count()),
            Messages.num("watching", match == null ? 0 : match.spectators().size()),
            Messages.num("ping", player.getPing()),
            Messages.comp("logo", logo()),
            Messages.text("tps", String.format(Locale.ROOT, "%.1f", Math.min(20.0, Bukkit.getTPS()[0]))));
        java.util.List<String> footer = match != null && !gui.tabFooterMatch.isEmpty() ? gui.tabFooterMatch : gui.tabFooter;
        player.sendPlayerListHeaderAndFooter(lines(gui.tabHeader, tags), lines(footer, tags));
    }

    private Component lines(java.util.List<String> raw, TagResolver tags) {
        return Component.join(JoinConfiguration.newlines(),
            raw.stream().map(line -> plugin.messages().parse(line, tags)).toList());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        String name = event.getPlayer().getName();
        tags.remove(event.getPlayer().getUniqueId());
        chatFlags.remove(event.getPlayer().getUniqueId());
        shown.remove(event.getPlayer().getUniqueId());
        for (Scoreboard sb : plugin.sidebar().boards().values()) {
            Team team = sb.getEntryTeam(name);
            if (team != null && team.getName().startsWith(PREFIX)) {
                team.removeEntry(name);
                if (team.getEntries().isEmpty()) team.unregister();
            }
        }
    }

    /**
     * Chat lines with the tag and the flag, rendered per viewer: their {@link Setting#CHAT_TAGS} and
     * {@link Setting#SHOW_FLAGS} decide what they see (the console gets no flag, it can't draw heads). Without chat
     * tags ({@code display.chat-tag}) and without a flag the line stays vanilla.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true) // (party chat, HIGH, replaces the renderer)
    public void onChat(AsyncChatEvent event) {
        Component tag = plugin.settings().chatTag ? tags.getOrDefault(event.getPlayer().getUniqueId(), Component.empty())
            : Component.empty();
        Component flag = chatFlags.getOrDefault(event.getPlayer().getUniqueId(), Component.empty());
        if (!plugin.settings().chatTag && flag.equals(Component.empty())) return;
        String format = plugin.gui().chatFormat;
        Messages messages = plugin.messages();
        event.renderer((source, displayName, message, viewer) -> {
            boolean showTags = true;
            boolean showFlags = false;
            if (viewer instanceof Player v) {
                PlayerProfile vp = plugin.profiles().get(v.getUniqueId());
                showTags = vp == null || vp.setting(Setting.CHAT_TAGS);
                showFlags = Flags.wantsFlags(vp);
            }
            Component t = showTags ? tag : Component.empty();
            String f = t.equals(Component.empty()) ? omit(format, "tier") : format;
            return messages.parse(f, Messages.comp("tier", t), Messages.comp("flag", showFlags ? flag : Component.empty()),
                Messages.comp("name", displayName), Messages.comp("message", message));
        });
    }
}
