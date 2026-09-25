package top.cheesesmp.duelcore.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

/** Typed view of gui.yml. */
public final class GuiConfig {

    /** {@code actionBar}: MiniMessage hint shown in the action bar while the item is held (empty = none). */
    public record HotbarItem(int slot, Material material, String name, List<String> lore, String actionBar) {
    }

    public final Map<String, HotbarItem> hotbar = new HashMap<>();
    public final boolean sidebarEnabled;
    public final String sidebarTitle;
    public final List<String> sidebarHub;
    public final List<String> sidebarQueue;
    public final List<String> sidebarMatch;
    public final List<String> sidebarSpectate;
    /** Free-for-all versions of the match and spectate sidebars (party FFA). */
    public final List<String> sidebarMatchFfa;
    public final List<String> sidebarSpectateFfa;
    /** Party menu: members per page, most parties / players listed, text and button widths. */
    public final int partyMembersPerPage;
    public final int partyListLimit;
    public final int partyWidth;
    public final int partyButtonWidth;
    public final String chatFormat;
    public final String tabFormat;
    public final String tabSpectatorFormat;
    public final String nametagPrefix;
    public final boolean hideUnrankedTag;
    public final String tagIconFormat;
    public final java.util.List<String> tabHeader;
    public final java.util.List<String> tabFooter;
    public final java.util.List<String> tabFooterMatch;
    public final int kitColumns;
    public final int kitButtonWidth;
    public final int wideWidth;
    public final int leaderboardLines;
    public final int spectateLimit;
    public final boolean motdEnabled;
    public final boolean motdCenter;
    public final java.util.List<String> motdLines;
    public final java.util.List<String> motdHover;
    public final int historyLines;
    /** Queue menu: row width, kit description width, tab icons, placement bar (segments + head textures). */
    public final int queueWidth;
    public final int queueKitWidth;
    public final Map<String, String> queueTabIcons = new HashMap<>();
    public final int queueProgressSegments;
    public final String queueProgressDone;
    public final String queueProgressTodo;

    public GuiConfig(YamlConfiguration y) {
        ConfigurationSection hb = y.getConfigurationSection("hotbar");
        if (hb != null) {
            for (String key : hb.getKeys(false)) {
                ConfigurationSection s = hb.getConfigurationSection(key);
                if (s == null) continue;
                Material m = Material.matchMaterial(s.getString("item", "stone"));
                hotbar.put(key, new HotbarItem(Math.clamp(s.getInt("slot", 0), 0, 8), m == null ? Material.STONE : m,
                    s.getString("name", key), s.getStringList("lore"), s.getString("action-bar", "")));
            }
        }
        sidebarEnabled = y.getBoolean("sidebar.enabled", true);
        sidebarTitle = y.getString("sidebar.title", "<accent>Duels</accent>");
        sidebarHub = y.getStringList("sidebar.hub");
        sidebarQueue = y.getStringList("sidebar.queue");
        sidebarMatch = y.getStringList("sidebar.match");
        sidebarSpectate = y.getStringList("sidebar.spectate");
        sidebarMatchFfa = y.getStringList("sidebar.match-ffa");
        sidebarSpectateFfa = y.getStringList("sidebar.spectate-ffa");
        partyMembersPerPage = Math.clamp(y.getInt("party-menu.members-per-page", 8), 1, 30);
        partyListLimit = Math.clamp(y.getInt("party-menu.list-limit", 24), 1, 100);
        partyWidth = Math.clamp(y.getInt("party-menu.width", 300), 100, 1024);
        partyButtonWidth = Math.clamp(y.getInt("party-menu.button-width", 100), 40, 400);
        chatFormat =y.getString("tags.chat", "<tier> <text><name></text><muted>:</muted> <message>");
        tabFormat = y.getString("tags.tab", "<tier> <text><name></text>");
        tabSpectatorFormat = y.getString("tags.tab-spectator", "<gray><i><name></i></gray>");
        nametagPrefix = y.getString("tags.nametag-prefix", "<tier> ");
        hideUnrankedTag = y.getBoolean("tags.hide-unranked", false);
        tagIconFormat = y.getString("tags.icon-tier", "<icon><tier>");
        tabHeader = y.getStringList("tab.header");
        tabFooter = y.getStringList("tab.footer");
        tabFooterMatch = y.getStringList("tab.footer-match");
        kitColumns = Math.clamp(y.getInt("dialogs.kit-columns", 3), 1, 6);
        kitButtonWidth = Math.clamp(y.getInt("dialogs.kit-button-width", 110), 40, 400);
        wideWidth = Math.clamp(y.getInt("dialogs.wide-width", 310), 100, 1024);
        leaderboardLines = Math.clamp(y.getInt("dialogs.leaderboard-lines", 10), 3, 50);
        spectateLimit = Math.clamp(y.getInt("dialogs.spectate-limit", 30), 1, 100);
        motdEnabled = y.getBoolean("motd.enabled", true);
        motdCenter = y.getBoolean("motd.center", true);
        motdLines = y.getStringList("motd.lines");
        motdHover = y.getStringList("motd.hover");
        historyLines = Math.clamp(y.getInt("dialogs.history-lines", 5), 0, 20);
        queueWidth = Math.clamp(y.getInt("queue-menu.width", 310), 100, 1024);
        queueKitWidth = Math.clamp(y.getInt("queue-menu.kit-width", 250), 50, 1024);
        ConfigurationSection icons = y.getConfigurationSection("queue-menu.tab-icons");
        if (icons != null) for (String key : icons.getKeys(false)) queueTabIcons.put(key, icons.getString(key, ""));
        queueProgressSegments = Math.clamp(y.getInt("queue-menu.progress.segments", 10), 1, 30);
        queueProgressDone = y.getString("queue-menu.progress.done", "");
        queueProgressTodo = y.getString("queue-menu.progress.todo", "");
    }

    public @Nullable HotbarItem item(String key) {
        return hotbar.get(key);
    }
}
