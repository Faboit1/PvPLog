package top.cheesesmp.duelcore.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.ui.anim.TextFx;

/** Typed view of gui.yml. */
public final class GuiConfig {

    /** {@code actionBar}: MiniMessage hint shown in the action bar while the item is held (empty = none). */
    public record HotbarItem(int slot, Material material, String name, List<String> lore, String actionBar) {
    }

    public final Map<String, HotbarItem> hotbar = new HashMap<>();
    public final boolean sidebarEnabled;
    public final String sidebarTitle;
    /** Sidebar title sweep: band colour, half width in characters, sweep and pause length in ticks. */
    public final net.kyori.adventure.text.format.TextColor sidebarShimmerColor;
    public final double sidebarShimmerWidth;
    public final int sidebarShimmerTicks;
    public final int sidebarShimmerPause;
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
    /** {@code <logo>} in the tab header: text, the two colours of its wave, bold, wave shift per tab refresh. */
    public final String tabLogoText;
    public final net.kyori.adventure.text.format.TextColor tabLogoFrom;
    public final net.kyori.adventure.text.format.TextColor tabLogoTo;
    public final boolean tabLogoBold;
    public final double tabLogoStep;
    public final int kitColumns;
    public final int kitButtonWidth;
    public final int wideWidth;
    public final int leaderboardLines;
    public final int spectateLimit;
    public final boolean motdEnabled;
    public final boolean motdCenter;
    public final java.util.List<String> motdLines;
    public final java.util.List<String> motdHover;
    /** Random pick per ping, shown where a MOTD line has {@code <tagline>}. */
    public final java.util.List<String> motdTaglines;
    public final int historyLines;
    /** Queue menu: row width, kit description width, tab icons, placement bar (segments + head textures). */
    public final int queueWidth;
    public final int queueKitWidth;
    public final Map<String, String> queueTabIcons = new HashMap<>();
    public final int queueProgressSegments;
    public final String queueProgressDone;
    public final String queueProgressTodo;
    /** Post-match progress animation: the action bar's bar, the blink colour, the colour it fades to, the shimmer. */
    public final top.cheesesmp.duelcore.ui.anim.ProgressBar revealBar;
    public final net.kyori.adventure.text.format.TextColor revealFlash;
    public final net.kyori.adventure.text.format.TextColor revealFade;
    public final net.kyori.adventure.text.format.TextColor revealShimmer;
    /** Queue menu animation: the "atlas:path" sprite drawn (tinted) for newly filled and filling segments. */
    public final String queueProgressHighlight;
    /**
     * The animated searching bars: spinner frames, the colours the spinner and word cycle through (one cycle in
     * {@code searchingCycleTicks}), the boss bar colours stepped through as the search widens and its overlay.
     */
    public final List<String> searchingSpinner;
    public final List<net.kyori.adventure.text.format.TextColor> searchingColors;
    public final int searchingCycleTicks;
    /** In-match animation colours and confetti (match-fx). */
    public final top.cheesesmp.duelcore.ui.MatchFxStyle matchFx;

    public GuiConfig(YamlConfiguration y) {
        ConfigurationSection hb = y.getConfigurationSection("hotbar");
        if (hb != null) {
            for (String key : hb.getKeys(false)) {
                ConfigurationSection s = hb.getConfigurationSection(key);
                if (s == null || !s.getBoolean("enabled", true)) continue; // enabled: false hides the item
                Material m = Material.matchMaterial(s.getString("item", "stone"));
                hotbar.put(key, new HotbarItem(Math.clamp(s.getInt("slot", 0), 0, 8), m == null ? Material.STONE : m,
                    s.getString("name", key), s.getStringList("lore"), s.getString("action-bar", "")));
            }
        }
        sidebarEnabled = y.getBoolean("sidebar.enabled", true);
        sidebarTitle = y.getString("sidebar.title", "<accent>Duels</accent>");
        sidebarShimmerColor = TextFx.color(y.getString("sidebar.title-shimmer.highlight"), net.kyori.adventure.text.format.TextColor.color(0xFFF4C8));
        sidebarShimmerWidth = Math.clamp(y.getDouble("sidebar.title-shimmer.width", 1.6), 0.5, 10);
        sidebarShimmerTicks = Math.clamp(y.getInt("sidebar.title-shimmer.sweep-ticks", 32), 8, 200);
        sidebarShimmerPause = Math.clamp(y.getInt("sidebar.title-shimmer.pause-ticks", 160), 20, 12000);
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
        tabLogoText = y.getString("tab.logo.text", "Cheese PvP");
        List<String> logoColors = y.getStringList("tab.logo.colors");
        tabLogoFrom = TextFx.color(logoColors.isEmpty() ? null : logoColors.getFirst(), net.kyori.adventure.text.format.TextColor.color(0xF2C14E));
        tabLogoTo = TextFx.color(logoColors.size() < 2 ? null : logoColors.get(1), net.kyori.adventure.text.format.TextColor.color(0xFFF1B8));
        tabLogoBold = y.getBoolean("tab.logo.bold", true);
        tabLogoStep = Math.clamp(y.getDouble("tab.logo.step", 0.12), 0, 1);
        kitColumns = Math.clamp(y.getInt("dialogs.kit-columns", 3), 1, 6);
        kitButtonWidth = Math.clamp(y.getInt("dialogs.kit-button-width", 110), 40, 400);
        wideWidth = Math.clamp(y.getInt("dialogs.wide-width", 310), 100, 1024);
        leaderboardLines = Math.clamp(y.getInt("dialogs.leaderboard-lines", 10), 3, 50);
        spectateLimit = Math.clamp(y.getInt("dialogs.spectate-limit", 30), 1, 100);
        motdEnabled = y.getBoolean("motd.enabled", true);
        motdCenter = y.getBoolean("motd.center", true);
        motdLines = y.getStringList("motd.lines");
        motdHover = y.getStringList("motd.hover");
        motdTaglines = y.getStringList("motd.taglines");
        historyLines = Math.clamp(y.getInt("dialogs.history-lines", 5), 0, 20);
        queueWidth = Math.clamp(y.getInt("queue-menu.width", 310), 100, 1024);
        queueKitWidth = Math.clamp(y.getInt("queue-menu.kit-width", 250), 50, 1024);
        ConfigurationSection icons = y.getConfigurationSection("queue-menu.tab-icons");
        if (icons != null) for (String key : icons.getKeys(false)) queueTabIcons.put(key, icons.getString(key, ""));
        queueProgressSegments = Math.clamp(y.getInt("queue-menu.progress.segments", 10), 1, 30);
        queueProgressDone = y.getString("queue-menu.progress.done", "");
        queueProgressTodo = y.getString("queue-menu.progress.todo", "");
        var std = top.cheesesmp.duelcore.ui.anim.ProgressBar.standard();
        revealBar = new top.cheesesmp.duelcore.ui.anim.ProgressBar(y.getInt("progress-reveal.bar.segments", std.count()),
            y.getString("progress-reveal.bar.filled", std.filled()), y.getString("progress-reveal.bar.empty", std.empty()),
            TextFx.color(y.getString("progress-reveal.bar.filled-color"), std.filledColor()),
            TextFx.color(y.getString("progress-reveal.bar.empty-color"), std.emptyColor()),
            TextFx.color(y.getString("progress-reveal.bar.head-color"), std.headColor()));
        revealFlash = TextFx.color(y.getString("progress-reveal.flash-color"), TextFx.WHITE);
        revealFade = TextFx.color(y.getString("progress-reveal.fade-color"), net.kyori.adventure.text.format.TextColor.color(0x6B7078));
        revealShimmer = TextFx.color(y.getString("progress-reveal.shimmer-color"), TextFx.WHITE);
        queueProgressHighlight = y.getString("queue-menu.progress.highlight", "blocks:block/white_concrete");
        List<String> spinner = y.getStringList("searching.spinner").stream().filter(f -> !f.isEmpty()).toList();
        searchingSpinner = spinner.isEmpty() ? List.of("◐", "◓", "◑", "◒") : spinner;
        List<net.kyori.adventure.text.format.TextColor> colors = new java.util.ArrayList<>();
        for (String hex : y.getStringList("searching.colors")) {
            net.kyori.adventure.text.format.TextColor c = net.kyori.adventure.text.format.TextColor.fromHexString(hex.trim());
            if (c != null) colors.add(c);
        }
        searchingColors = colors.isEmpty() ? List.of(net.kyori.adventure.text.format.TextColor.color(0xF2C14E)) : List.copyOf(colors);
        searchingCycleTicks = (int) Math.round(Math.clamp(y.getDouble("searching.cycle-seconds", 6), 1, 60) * 20);
        matchFx = top.cheesesmp.duelcore.ui.MatchFxStyle.parse(y);
    }

    public @Nullable HotbarItem item(String key) {
        return hotbar.get(key);
    }
}
