package top.cheesesmp.duelcore.ui;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Locale;
import java.util.Map;
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
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.match.Participant;
import top.cheesesmp.duelcore.profile.KitStats;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;
import top.cheesesmp.duelcore.rating.Tier;

/**
 * Tier tags in chat, the tab list and above heads, plus the tab header and footer.
 *
 * <p>A tag is the icon of a kit followed by the player's tier in it: in the hub their best kit (best tier, then
 * highest rating), during a match the match's kit with the tier they had when it started. Nametags use one scoreboard
 * team per shown kit + tier; team names start with the tier's rank, so the tab list is sorted best tier first.
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
     * What a player's tag shows: a kit (null = none ranked yet) and their tier in it (null = unranked); spectators
     * of a match are listed apart.
     */
    private record Shown(@Nullable Kit kit, @Nullable Tier tier, boolean spectator) {

        String team() {
            if (spectator) return PREFIX + "zz_spectators"; // after every "dct_NN" tier team
            int rank = tier != null ? tier.ordinal() : kit != null ? Tier.values().length : Tier.values().length + 1;
            return PREFIX + String.format(Locale.ROOT, "%02d", rank) + (kit == null ? "" : "_" + kit.id());
        }
    }

    private final DuelCorePlugin plugin;
    /** Rendered tag per player; read by the async chat renderer. */
    private final Map<UUID, Component> tags = new ConcurrentHashMap<>();
    private final Map<UUID, Shown> shown = new ConcurrentHashMap<>();
    /** Where the tab logo's colour wave is (0..1). */
    private double logoPhase;

    public TagService(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** The kit + tier a player's tag shows right now. */
    private Shown shownFor(Player player) {
        Match match = plugin.matches().match(player.getUniqueId());
        if (match != null && !match.isOver()) {
            Participant p = match.participant(player.getUniqueId());
            return new Shown(match.kit(), p == null ? null : p.tierBefore(), false);
        }
        boolean spectator = plugin.spectate().spectating(player.getUniqueId()) != null;
        PlayerProfile profile = plugin.profiles().get(player);
        if (profile == null) return new Shown(null, null, spectator);
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
        return new Shown(best, bestTier, spectator);
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

    /** Creates the teams of everyone online on a (new) scoreboard. */
    public void setupTeams(Scoreboard sb) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Shown s = shown.get(p.getUniqueId());
            if (s == null) continue;
            Team team = team(sb, s);
            if (team != null) team.addEntry(p.getName());
        }
    }

    private @Nullable Team team(Scoreboard sb, Shown s) {
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
            Component tag = tagFor(s);
            team.prefix(plugin.settings().nametagTag && !tag.equals(Component.empty())
                ? plugin.messages().parse(plugin.gui().nametagPrefix, Messages.comp("tier", tag)) : Component.empty());
        }
        return team;
    }

    /** Rebuilds every tag team after a reload (formats may have changed). */
    public void refreshTeams() {
        for (Scoreboard sb : plugin.sidebar().boards().values()) {
            for (Team t : sb.getTeams()) if (t.getName().startsWith(PREFIX)) t.unregister();
        }
        shown.clear();
        for (Player p : Bukkit.getOnlinePlayers()) update(p);
    }

    /** Recomputes a player's tag everywhere (join, match start and end, tier change). */
    public void update(Player player) {
        Shown s = shownFor(player);
        Component tag = tagFor(s);
        tags.put(player.getUniqueId(), tag);
        Shown before = shown.put(player.getUniqueId(), s);
        Component suffix = suffix(player);
        boolean plain = suffix.equals(Component.empty());
        if (s.spectator()) {
            player.playerListName(plugin.messages().parse(plugin.gui().tabSpectatorFormat, Messages.comp("tier", tag),
                Messages.text("name", player.getName())).append(suffix));
        } else if (plugin.settings().tabTag && !tag.equals(Component.empty())) {
            player.playerListName(plugin.messages().parse(plugin.gui().tabFormat, Messages.comp("tier", tag),
                Messages.text("name", player.getName())).append(suffix));
        } else if (!plain) {
            player.playerListName(Component.text(player.getName()).append(suffix));
        } else {
            player.playerListName(null);
        }
        plugin.sidebar().board(player);
        String name = player.getName();
        for (Scoreboard sb : plugin.sidebar().boards().values()) {
            Team current = sb.getEntryTeam(name);
            if (current != null && current.getName().equals(s.team())) continue;
            if (current != null && current.getName().startsWith(PREFIX)) {
                current.removeEntry(name);
                if (current.getEntries().isEmpty()) current.unregister();
            }
            Team team = team(sb, s);
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
        shown.remove(event.getPlayer().getUniqueId());
        for (Scoreboard sb : plugin.sidebar().boards().values()) {
            Team team = sb.getEntryTeam(name);
            if (team != null && team.getName().startsWith(PREFIX)) {
                team.removeEntry(name);
                if (team.getEntries().isEmpty()) team.unregister();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.settings().chatTag) return;
        Component tag = tags.getOrDefault(event.getPlayer().getUniqueId(), Component.empty());
        String format = plugin.gui().chatFormat;
        Messages messages = plugin.messages();
        event.renderer((source, displayName, message, viewer) -> {
            boolean showTags = true;
            if (viewer instanceof Player v) {
                PlayerProfile vp = plugin.profiles().get(v.getUniqueId());
                showTags = vp == null || vp.setting(Setting.CHAT_TAGS);
            }
            Component t = showTags ? tag : Component.empty();
            String f = t.equals(Component.empty()) ? format.replace("<tier> ", "").replace("<tier>", "") : format;
            return messages.parse(f, Messages.comp("tier", t), Messages.comp("name", displayName),
                Messages.comp("message", message));
        });
    }
}
