package top.cheesesmp.duelcore.ui;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
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
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;
import top.cheesesmp.duelcore.rating.Tier;

/** Tier tags in chat, the tab list and above heads. */
public final class TagService implements Listener {

    private static final String UNRANKED_TEAM = "dct_u";

    private final DuelCorePlugin plugin;
    /** Rendered tag per player; read by the async chat renderer. */
    private final Map<UUID, Component> tags = new ConcurrentHashMap<>();

    public TagService(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    private static String teamName(@Nullable Tier tier) {
        return tier == null ? UNRANKED_TEAM : "dct_" + tier.name().toLowerCase(java.util.Locale.ROOT);
    }

    private Component tagFor(@Nullable Tier tier) {
        if (tier == null && plugin.gui().hideUnrankedTag) return Component.empty();
        return plugin.tiers().format(tier);
    }

    /** Creates the tier teams on a (new) scoreboard and fills them with everyone online. */
    public void setupTeams(Scoreboard sb) {
        boolean nametags = plugin.settings().nametagTag;
        for (Tier t : Tier.values()) team(sb, t, nametags);
        team(sb, null, nametags);
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerProfile profile = plugin.profiles().get(p);
            Team team = sb.getTeam(teamName(profile == null ? null : profile.overall()));
            if (team != null) team.addEntry(p.getName());
        }
    }

    private Team team(Scoreboard sb, @Nullable Tier tier, boolean withPrefix) {
        String name = teamName(tier);
        Team team = sb.getTeam(name);
        if (team == null) {
            team = sb.registerNewTeam(name);
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        }
        Component prefix = tagFor(tier);
        team.prefix(withPrefix && !prefix.equals(Component.empty())
            ? plugin.messages().parse(plugin.gui().nametagPrefix, Messages.comp("tier", prefix)) : Component.empty());
        return team;
    }

    /** Re-applies prefixes after a reload. */
    public void refreshTeams() {
        for (Scoreboard sb : plugin.sidebar().boards().values()) {
            boolean nametags = plugin.settings().nametagTag;
            for (Tier t : Tier.values()) team(sb, t, nametags);
            team(sb, null, nametags);
        }
        for (Player p : Bukkit.getOnlinePlayers()) update(p);
    }

    /** Recomputes a player's tag everywhere (after a match, tier change or join). */
    public void update(Player player) {
        PlayerProfile profile = plugin.profiles().get(player);
        Tier overall = profile == null ? null : profile.overall();
        Component tag = tagFor(overall);
        tags.put(player.getUniqueId(), tag);
        if (plugin.settings().tabTag) {
            player.playerListName(tag.equals(Component.empty())
                ? null
                : plugin.messages().parse(plugin.gui().tabFormat, Messages.comp("tier", tag),
                    Messages.text("name", player.getName())));
        }
        String teamName = teamName(overall);
        plugin.sidebar().board(player);
        for (Scoreboard sb : plugin.sidebar().boards().values()) {
            Team current = sb.getEntryTeam(player.getName());
            if (current != null && current.getName().equals(teamName)) continue;
            if (current != null && current.getName().startsWith("dct_")) current.removeEntry(player.getName());
            Team team = sb.getTeam(teamName);
            if (team != null) team.addEntry(player.getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        String name = event.getPlayer().getName();
        tags.remove(event.getPlayer().getUniqueId());
        for (Scoreboard sb : plugin.sidebar().boards().values()) {
            Team team = sb.getEntryTeam(name);
            if (team != null && team.getName().startsWith("dct_")) team.removeEntry(name);
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
