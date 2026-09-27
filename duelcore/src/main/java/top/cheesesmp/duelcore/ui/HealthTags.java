package top.cheesesmp.duelcore.ui;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.GuiConfig;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.match.Participant;

/**
 * Health under the names of your opponents while you fight (and of every fighter while you watch a match): a
 * below-name line on each viewer's own scoreboard ({@link SidebarService#board}), checked every other tick and only
 * re-sent when it changed. Teammates and the hub show nothing; the line goes away when the match ends.
 */
public final class HealthTags implements Runnable {

    static final String OBJECTIVE = "dc_hp";

    private static final TextColor LOW = TextColor.color(0xFF5555);
    private static final TextColor MID = TextColor.color(0xFFCC33);
    private static final TextColor HIGH = TextColor.color(0x55FF55);

    private final DuelCorePlugin plugin;
    /** Per viewer: the line shown under each name right now (only changes are sent). */
    private final Map<UUID, Map<String, Component>> shown = new HashMap<>();

    public HealthTags(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        boolean enabled = plugin.gui().matchHealthEnabled;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard sb = plugin.sidebar().boards().get(viewer.getUniqueId()); // (created by the sidebar)
            if (sb == null) continue;
            apply(viewer.getUniqueId(), sb, enabled ? lines(viewer) : Map.of());
        }
        shown.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    /** Name → line for everyone whose health the viewer sees now: their opponents, or every fighter when watching. */
    private Map<String, Component> lines(Player viewer) {
        Match m = plugin.matches().match(viewer.getUniqueId());
        Participant self = m == null ? null : m.participant(viewer.getUniqueId());
        if (m == null || m.isOver()) {
            m = plugin.spectate().spectating(viewer.getUniqueId());
            self = null;
        }
        if (m == null || m.isOver()) return Map.of();
        Map<String, Component> out = new HashMap<>();
        for (Participant p : m.participants()) {
            if (!p.alive() || p.left() || (self != null && p.team() == self.team())) continue;
            Player target = Bukkit.getPlayer(p.uuid());
            if (target != null) out.put(target.getName(), line(target));
        }
        return out;
    }

    private Component line(Player target) {
        GuiConfig gui = plugin.gui();
        AttributeInstance max = target.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = max == null ? 20 : max.getValue();
        double hp = Math.max(0, target.getHealth());
        double absorption = target.getAbsorptionAmount();
        Component extra = absorption <= 0 ? Component.empty()
            : plugin.messages().parse(gui.matchHealthAbsorption, Messages.text("hearts", hearts(absorption)));
        return plugin.messages().parse(gui.matchHealthFormat,
            Messages.text("hearts", hearts(hp)),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.styling("hp_color", color(hp / maxHp)),
            Messages.comp("absorption", extra));
    }

    /** Health points as hearts with one decimal ("7.5", "10"). */
    static String hearts(double hp) {
        double h = Math.round(hp / 2 * 10) / 10.0;
        return h == Math.rint(h) ? String.valueOf((long) h) : String.format(Locale.ROOT, "%.1f", h);
    }

    /** Red at no health, yellow at half, green at full. */
    static TextColor color(double fraction) {
        double f = Math.clamp(fraction, 0, 1);
        return f < 0.5 ? TextColor.lerp((float) (f * 2), LOW, MID) : TextColor.lerp((float) ((f - 0.5) * 2), MID, HIGH);
    }

    private void apply(UUID viewer, Scoreboard sb, Map<String, Component> lines) {
        Objective obj = sb.getObjective(OBJECTIVE);
        if (lines.isEmpty()) {
            if (obj != null) obj.unregister();
            shown.remove(viewer);
            return;
        }
        Map<String, Component> before = shown.get(viewer);
        if (obj == null) {
            obj = sb.registerNewObjective(OBJECTIVE, Criteria.DUMMY, Component.empty(), RenderType.INTEGER);
            obj.setDisplaySlot(DisplaySlot.BELOW_NAME);
            before = null;
        }
        Map<String, Component> now = new HashMap<>(lines);
        if (before != null) {
            for (String name : before.keySet()) {
                if (!now.containsKey(name)) resetScore(obj.getScore(name));
            }
        }
        for (Map.Entry<String, Component> e : now.entrySet()) {
            Score score = obj.getScore(e.getKey());
            // (a score reset elsewhere, e.g. a sidebar line cleanup of the same entry, is sent again)
            if (before != null && e.getValue().equals(before.get(e.getKey())) && score.isScoreSet()) continue;
            score.setScore(1);
            score.numberFormat(NumberFormat.fixed(e.getValue()));
        }
        shown.put(viewer, now);
    }

    private static void resetScore(@Nullable Score score) {
        if (score == null) return;
        try {
            score.resetScore();
        } catch (IllegalStateException ignored) {
            // the objective went away meanwhile
        }
    }
}
