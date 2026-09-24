package top.cheesesmp.duelcore.ui;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.match.Participant;
import top.cheesesmp.duelcore.queue.QueueMode;

/**
 * Post-match presentation: an instant title + chat summary, then a results dialog once the player is back in
 * the hub (a dialog opened in the arena would be closed by the world change).
 */
public final class ResultsService implements org.bukkit.event.Listener {

    private record Pending(Component title, List<Component> lines, Kit kit, @Nullable QueueMode requeue, long at) {
    }

    private final DuelCorePlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    public ResultsService(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void show(Match m) {
        Match.EndReason reason = m.endReason();
        boolean cancelled = reason == Match.EndReason.CANCELLED || reason == Match.EndReason.NO_ARENA;
        for (Participant p : m.participants()) {
            Player player = Bukkit.getPlayer(p.uuid());
            if (player == null || p.left()) continue;
            if (cancelled) {
                plugin.messages().send(player, reason == Match.EndReason.NO_ARENA ? "match.no-arena" : "match.cancelled");
                continue;
            }
            boolean draw = m.winnerTeam() < 0;
            boolean won = !draw && p.team() == m.winnerTeam();
            String outcome = draw ? "draw" : won ? "victory" : "defeat";
            Component title = plugin.messages().get("results.title-" + outcome);
            player.showTitle(Title.title(title, plugin.messages().get("results.subtitle",
                    Messages.num("you", m.score(p.team())), Messages.num("opp", m.score(1 - p.team())),
                    Messages.text("opponent", m.teamName(1 - p.team()))),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2200), Duration.ofMillis(400))));
            List<Component> lines = lines(m, p);
            for (Component line : chatLines(m, p)) player.sendMessage(line);
            QueueMode requeue = m.origin() == Match.Origin.QUEUE ? (m.ranked() ? QueueMode.RANKED : QueueMode.UNRANKED) : null;
            pending.put(p.uuid(), new Pending(title, lines, m.kit(), requeue, System.currentTimeMillis()));
        }
        if (!cancelled) {
            for (UUID s : m.spectators()) {
                Player sp = Bukkit.getPlayer(s);
                if (sp == null) continue;
                plugin.messages().send(sp, "results.spectator", Messages.text("winner",
                        m.winnerTeam() < 0 ? plugin.messages().raw("results.nobody") : m.teamName(m.winnerTeam())),
                    Messages.num("red_score", m.score(0)), Messages.num("blue_score", m.score(1)),
                    Messages.comp("kit", m.kit().displayName()));
            }
        }
    }

    /** Opens the results dialog if one is waiting (called after the hub teleport). */
    public void showPending(Player player) {
        Pending p = pending.remove(player.getUniqueId());
        if (p == null || System.currentTimeMillis() - p.at() > 60_000) return;
        plugin.dialogs().results(player, p.lines(), p.title(), p.kit(), p.requeue());
    }

    public void forget(UUID uuid) {
        pending.remove(uuid);
    }

    @org.bukkit.event.EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    public int pendingCount() {
        return pending.size();
    }

    private List<Component> lines(Match m, Participant self) {
        Participant opp = m.opponentOf(self);
        List<Component> lines = new ArrayList<>();
        lines.add(plugin.messages().get("results.header", Messages.comp("kit_icon", m.kit().sprite()),
            Messages.comp("kit", m.kit().displayName()),
            Messages.text("mode", plugin.messages().raw("mode." + (m.ranked() ? "ranked" : "unranked")))));
        lines.add(plugin.messages().get("results.score", Messages.text("you", self.name()),
            Messages.text("opponent", m.teamName(1 - self.team())),
            Messages.num("you_score", m.score(self.team())), Messages.num("opp_score", m.score(1 - self.team()))));
        if (m.endReason() == Match.EndReason.FORFEIT_QUIT || m.endReason() == Match.EndReason.FORFEIT_COMMAND) {
            lines.add(plugin.messages().get("results.forfeit"));
        }
        lines.add(Component.empty());
        lines.add(plugin.messages().get("results.stat-hits", Messages.num("you", self.hits()),
            Messages.num("opp", opp == null ? 0 : opp.hits())));
        lines.add(plugin.messages().get("results.stat-damage", Messages.text("you", hearts(self.damageDealt())),
            Messages.text("opp", hearts(opp == null ? 0 : opp.damageDealt()))));
        lines.add(plugin.messages().get("results.stat-combo", Messages.num("you", self.bestCombo()),
            Messages.num("opp", opp == null ? 0 : opp.bestCombo())));
        lines.add(plugin.messages().get("results.stat-rounds", Messages.comp("rounds", rounds(m, self.team()))));
        if (m.ranked() && !Double.isNaN(self.ratingAfter())) {
            lines.add(Component.empty());
            int delta = (int) Math.round(self.ratingDelta());
            lines.add(plugin.messages().get(delta >= 0 ? "results.rating-up" : "results.rating-down",
                Messages.num("before", (int) Math.round(self.ratingBefore())),
                Messages.num("after", (int) Math.round(self.ratingAfter())),
                Messages.text("delta", (delta >= 0 ? "+" : "") + delta)));
            lines.add(plugin.messages().get("results.tier", Messages.comp("before", plugin.tiers().format(self.tierBefore())),
                Messages.comp("after", plugin.tiers().format(self.tierAfter()))));
        }
        return lines;
    }

    private List<Component> chatLines(Match m, Participant self) {
        List<Component> out = new ArrayList<>();
        out.add(plugin.messages().get("results.chat", Messages.comp("kit_icon", m.kit().sprite()),
            Messages.comp("outcome", plugin.messages().get("results.word-" + (m.winnerTeam() < 0 ? "draw"
                : m.winnerTeam() == self.team() ? "victory" : "defeat"))),
            Messages.num("you_score", m.score(self.team())), Messages.num("opp_score", m.score(1 - self.team())),
            Messages.text("opponent", m.teamName(1 - self.team()))));
        if (m.ranked() && !Double.isNaN(self.ratingAfter())) {
            int delta = (int) Math.round(self.ratingDelta());
            out.add(plugin.messages().get("results.chat-rating", Messages.num("after", (int) Math.round(self.ratingAfter())),
                Messages.text("delta", (delta >= 0 ? "+" : "") + delta),
                Messages.comp("tier", plugin.tiers().format(self.tierAfter()))));
        }
        return out;
    }

    private static String hearts(double damage) {
        return String.format(java.util.Locale.ROOT, "%.1f", damage / 2.0);
    }

    /** Round history from one side, e.g. "W W L W" (each letter styled by messages.yml). */
    private Component rounds(Match m, int team) {
        List<Component> parts = new ArrayList<>();
        for (int w : m.roundWinners()) {
            parts.add(plugin.messages().get(w < 0 ? "results.round-draw" : w == team ? "results.round-win" : "results.round-loss"));
        }
        return Component.join(net.kyori.adventure.text.JoinConfiguration.spaces(), parts);
    }
}
