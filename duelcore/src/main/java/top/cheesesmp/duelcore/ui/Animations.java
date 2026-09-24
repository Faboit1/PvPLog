package top.cheesesmp.duelcore.ui;

import java.util.List;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.MainConfig;

/**
 * Small match effects. Everything is sent to an explicit viewer list (the match's fighters and spectators), so
 * parallel matches in the shared arena world never see or hear each other's effects. Each one can be turned off
 * in config.yml {@code animations}.
 */
public final class Animations {

    private static final Particle.DustOptions RED = new Particle.DustOptions(Color.fromRGB(0xE0, 0x4A, 0x4A), 1.4f);
    private static final Particle.DustOptions GOLD = new Particle.DustOptions(Color.fromRGB(0xF2, 0xC1, 0x4E), 1.2f);
    private static final Particle.DustOptions WHITE = new Particle.DustOptions(Color.fromRGB(0xF4, 0xF6, 0xF8), 0.9f);

    private final DuelCorePlugin plugin;

    public Animations(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    private MainConfig cfg() {
        return plugin.settings();
    }

    /** Where a fighter died: a red burst, a soul drifting up and a muffled thunder crack (only for this match). */
    public void death(Location at, List<Player> viewers) {
        if (!cfg().animDeath) return;
        Location body = at.clone().add(0, 1, 0);
        for (Player v : viewers) {
            if (v.getWorld() != at.getWorld()) continue;
            v.spawnParticle(Particle.DUST, body, 40, 0.35, 0.6, 0.35, 0, RED);
            v.spawnParticle(Particle.CLOUD, body, 16, 0.25, 0.4, 0.25, 0.03);
            v.spawnParticle(Particle.SOUL, body, 6, 0.2, 0.3, 0.2, 0.04);
            v.playSound(at, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.45f, 1.6f);
        }
    }

    /** Round won: a quick golden spiral around the winner. */
    public void roundWin(Player winner, List<Player> viewers) {
        if (!cfg().animRoundWin) return;
        Location base = winner.getLocation();
        for (Player v : viewers) {
            if (v.getWorld() != base.getWorld()) continue;
            for (int i = 0; i < 24; i++) {
                double a = i * Math.PI / 6;
                double y = i * 0.08;
                v.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a) * 0.8, y, Math.sin(a) * 0.8), 1, 0, 0, 0, 0, GOLD);
            }
        }
    }

    /** Match won: three firework-style bursts above the winner over about a second and a half. */
    public void matchWin(Player winner, List<Player> viewers) {
        if (!cfg().animMatchWin) return;
        for (int burst = 0; burst < 3; burst++) {
            int b = burst;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!winner.isOnline()) return;
                Location c = winner.getLocation().add((b - 1) * 1.2, 3.2 + b * 0.4, (b % 2 == 0 ? 1 : -1) * 0.8);
                for (Player v : viewers) {
                    if (!v.isOnline() || v.getWorld() != c.getWorld()) continue;
                    v.spawnParticle(Particle.FIREWORK, c, 45, 0, 0, 0, 0.16);
                    v.spawnParticle(Particle.DUST, c, 25, 0.7, 0.7, 0.7, 0, GOLD);
                    v.playSound(c, b == 2 ? Sound.ENTITY_FIREWORK_ROCKET_TWINKLE : Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.7f, 1f);
                }
            }, 4L + burst * 12L);
        }
    }

    /** Fight starts: a thin white ring at each fighter's feet. */
    public void fightStart(Player fighter, List<Player> viewers) {
        if (!cfg().animFightStart) return;
        Location base = fighter.getLocation().add(0, 0.1, 0);
        for (Player v : viewers) {
            if (v.getWorld() != base.getWorld()) continue;
            for (int i = 0; i < 20; i++) {
                double a = i * Math.PI / 10;
                v.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a) * 1.1, 0, Math.sin(a) * 1.1), 1, 0, 0, 0, 0, WHITE);
            }
        }
    }
}
