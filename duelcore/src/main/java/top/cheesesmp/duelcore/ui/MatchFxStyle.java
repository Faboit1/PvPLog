package top.cheesesmp.duelcore.ui;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import top.cheesesmp.duelcore.ui.anim.TextFx;

/**
 * Colours of the in-match animations ({@link MatchFx}), gui.yml {@code match-fx}.
 *
 * @param countdownColors countdown number colour for 1, 2, 3+ seconds left
 * @param pop             the colour a popping number (countdown, combo, players left, score) shows first
 * @param fightColors     gradient across "FIGHT!"
 * @param shine           the band sweeping over "FIGHT!", "Victory" and the winner's name
 * @param heartbeatBright low-health action bar on a beat
 * @param heartbeatDim    low-health action bar between beats
 * @param fade            what the combo / kill bar and the defeat title fade towards
 * @param confetti        blocks whose colour the confetti over the match winner takes
 */
public record MatchFxStyle(List<TextColor> countdownColors, TextColor pop, List<TextColor> fightColors, TextColor shine,
                           TextColor heartbeatBright, TextColor heartbeatDim, TextColor fade, List<Material> confetti) {

    public static MatchFxStyle parse(YamlConfiguration y) {
        List<TextColor> countdown = colors(y.getStringList("match-fx.countdown-colors"),
            List.of(TextColor.color(0xFF5A5A), TextColor.color(0xFFD24A), TextColor.color(0x5BD46A)));
        List<TextColor> fight = colors(y.getStringList("match-fx.fight-colors"),
            List.of(TextColor.color(0xFFD24A), TextColor.color(0xFF5A5A)));
        List<TextColor> heart = colors(y.getStringList("match-fx.heartbeat-colors"),
            List.of(TextColor.color(0xFF3B3B), TextColor.color(0x6A1F1F)));
        List<Material> confetti = new ArrayList<>();
        for (String name : y.getStringList("match-fx.confetti")) {
            Material m = Material.matchMaterial(name);
            if (m != null && m.isBlock()) confetti.add(m);
        }
        if (confetti.isEmpty()) {
            confetti.addAll(List.of(Material.RED_CONCRETE, Material.YELLOW_CONCRETE, Material.LIME_CONCRETE,
                Material.LIGHT_BLUE_CONCRETE, Material.MAGENTA_CONCRETE));
        }
        return new MatchFxStyle(countdown, TextFx.color(y.getString("match-fx.pop-color"), TextFx.WHITE), fight,
            TextFx.color(y.getString("match-fx.shine-color"), TextFx.WHITE), heart.getFirst(), heart.get(heart.size() > 1 ? 1 : 0),
            TextFx.color(y.getString("match-fx.fade-color"), TextColor.color(0x6B7078)), List.copyOf(confetti));
    }

    private static List<TextColor> colors(List<String> hex, List<TextColor> fallback) {
        List<TextColor> out = new ArrayList<>();
        for (String h : hex) {
            TextColor c = TextColor.fromHexString(h.trim());
            if (c != null) out.add(c);
        }
        return out.isEmpty() ? fallback : List.copyOf(out);
    }
}
