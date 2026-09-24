package top.cheesesmp.duelcore.ui;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.GuiConfig;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.match.Participant;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;
import top.cheesesmp.duelcore.queue.QueueEntry;
import top.cheesesmp.duelcore.queue.QueueService;

/**
 * One scoreboard per player: the sidebar (blank numbers, one custom-named score per line) and the tier teams used
 * for nametags. Lines are only re-sent when their text changed.
 */
public final class SidebarService implements Listener, Runnable {

    private static final String OBJECTIVE = "dc_side";

    private final DuelCorePlugin plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, List<Component>> lastLines = new HashMap<>();

    public SidebarService(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** The player's own scoreboard, created on first use. */
    public Scoreboard board(Player player) {
        return boards.computeIfAbsent(player.getUniqueId(), id -> {
            Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
            player.setScoreboard(sb);
            plugin.tags().setupTeams(sb);
            return sb;
        });
    }

    public Map<UUID, Scoreboard> boards() {
        return boards;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        boards.remove(event.getPlayer().getUniqueId());
        lastLines.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public void run() {
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
    }

    public void refresh(Player player) {
        Scoreboard sb = board(player);
        if (player.getScoreboard() != sb) player.setScoreboard(sb);
        GuiConfig gui = plugin.gui();
        PlayerProfile profile = plugin.profiles().get(player);
        Objective obj = sb.getObjective(OBJECTIVE);
        boolean show = gui.sidebarEnabled && (profile == null || profile.setting(Setting.SIDEBAR));
        if (!show) {
            if (obj != null) obj.unregister();
            lastLines.remove(player.getUniqueId());
            return;
        }
        if (obj == null) {
            obj = sb.registerNewObjective(OBJECTIVE, Criteria.DUMMY, plugin.messages().parse(gui.sidebarTitle), RenderType.INTEGER);
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            obj.numberFormat(NumberFormat.blank());
            lastLines.remove(player.getUniqueId());
        }
        List<Component> lines = render(player, profile);
        List<Component> previous = lastLines.get(player.getUniqueId());
        if (previous != null && previous.size() != lines.size()) {
            for (String entry : new ArrayList<>(sb.getEntries())) {
                if (entry.startsWith("dc")) sb.resetScores(entry);
            }
            previous = null;
        }
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            if (previous != null && Objects.equals(previous.get(i), line)) continue;
            var score = obj.getScore("dc" + i);
            score.setScore(lines.size() - i);
            score.customName(line);
        }
        lastLines.put(player.getUniqueId(), lines);
    }

    private List<Component> render(Player player, @Nullable PlayerProfile profile) {
        GuiConfig gui = plugin.gui();
        Messages msg = plugin.messages();
        List<TagResolver> tags = new ArrayList<>();
        tags.add(Messages.text("player", player.getName()));
        tags.add(Messages.comp("tier", plugin.tiers().format(profile == null ? null : profile.overall())));
        tags.add(Messages.text("elo", top.cheesesmp.duelcore.rating.TierService.eloText(profile)));
        tags.add(Messages.num("queued", plugin.queue().totalQueued()));
        tags.add(Messages.num("live", plugin.matches().count()));
        tags.add(Messages.num("online", Bukkit.getOnlinePlayers().size()));
        tags.add(Messages.text("region", profile == null || profile.region() == null ? "—" : profile.region()));
        tags.add(Messages.num("ping", player.getPing()));
        List<String> template;
        Match match = plugin.matches().match(player.getUniqueId());
        Match spectating = plugin.spectate().spectating(player.getUniqueId());
        QueueEntry queued = plugin.queue().firstEntry(player.getUniqueId());
        if (match != null) {
            template = gui.sidebarMatch;
            Participant self = match.participant(player.getUniqueId());
            int team = self == null ? 0 : self.team();
            Participant opp = self == null ? null : match.opponentOf(self);
            Player oppPlayer = opp == null ? null : Bukkit.getPlayer(opp.uuid());
            addKit(tags, match.kit(), match.ranked() ? "ranked" : "unranked");
            tags.add(Messages.text("opponent", match.teamName(1 - team)));
            tags.add(Messages.num("you_score", match.score(team)));
            tags.add(Messages.num("opp_score", match.score(1 - team)));
            tags.add(Messages.num("round", Math.max(1, match.round())));
            tags.add(Messages.num("first_to", match.firstTo()));
            tags.add(Messages.text("time", time(match)));
            tags.add(Messages.num("opp_ping", oppPlayer == null ? 0 : oppPlayer.getPing()));
        } else if (spectating != null) {
            template = gui.sidebarSpectate;
            addKit(tags, spectating.kit(), spectating.ranked() ? "ranked" : "unranked");
            tags.add(Messages.text("red_name", spectating.teamName(0)));
            tags.add(Messages.text("blue_name", spectating.teamName(1)));
            tags.add(Messages.num("red_score", spectating.score(0)));
            tags.add(Messages.num("blue_score", spectating.score(1)));
            tags.add(Messages.num("round", Math.max(1, spectating.round())));
            tags.add(Messages.text("time", time(spectating)));
        } else if (queued != null) {
            template = gui.sidebarQueue;
            Kit kit = plugin.kits().get(queued.kit());
            if (kit != null) addKit(tags, kit, queued.mode().id());
            long now = System.currentTimeMillis();
            tags.add(Messages.text("wait", QueueService.formatWait(queued.waitSeconds(now))));
            double range = plugin.queue().matchmaker().windowFor(queued, now);
            tags.add(Messages.text("range", Double.isInfinite(range) ? "∞" : String.valueOf((int) range)));
        } else {
            template = gui.sidebarHub;
        }
        TagResolver[] resolvers = tags.toArray(TagResolver[]::new);
        List<Component> out = new ArrayList<>(template.size());
        for (String line : template) out.add(msg.parse(line, resolvers));
        return out;
    }

    private void addKit(List<TagResolver> tags, Kit kit, String mode) {
        tags.add(Messages.comp("kit", kit.displayName()));
        tags.add(Messages.comp("kit_icon", kit.sprite()));
        tags.add(Messages.text("mode", plugin.messages().raw("mode." + mode)));
    }

    private static String time(Match m) {
        int seconds = m.roundTicks() / 20;
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
