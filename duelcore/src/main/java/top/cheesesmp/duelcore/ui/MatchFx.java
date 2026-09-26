package top.cheesesmp.duelcore.ui;

import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.MainConfig;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.debug.TesterMode;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.profile.Setting;
import top.cheesesmp.duelcore.ui.anim.Animation;
import top.cheesesmp.duelcore.ui.anim.BlinkFade;
import top.cheesesmp.duelcore.ui.anim.Channel;
import top.cheesesmp.duelcore.ui.anim.Ease;
import top.cheesesmp.duelcore.ui.anim.Sfx;
import top.cheesesmp.duelcore.ui.anim.TextFx;

/**
 * In-match titles, action bars and sounds, one player at a time on the {@code plugin.anim()} channels (the particle
 * effects stay in {@link Animations}). MatchService decides who sees what (fighters and spectators); every method
 * here checks its own config.yml {@code animations} switch and returns false when it is off, so the caller can fall
 * back to the plain title. Titles are sent part by part ({@link TitlePart}): the times once with no fade-in, then only
 * the part that changes, so a re-sent frame doesn't fade in again. Players' own settings: {@link Setting#COMBO_BAR}
 * (combo and kill bars), {@link Setting#HEARTBEAT}, and {@link Setting#MATCH_SOUNDS} for every sound but the heartbeat's.
 * Texts: messages.yml {@code match.fx}; colours:
 * gui.yml {@code match-fx}.
 */
public final class MatchFx {

    /** Ticks the combo / kill bar stays before it fades, and the fade. */
    private static final int COMBO_HOLD = 30;
    private static final int KILL_HOLD = 40;
    private static final int BAR_FADE = 10;
    private static final int FIGHT_TICKS = 12;
    /** Round banner: the title types in over BANNER_TYPE ticks, the score changes at BANNER_SCORE. */
    private static final int BANNER_TYPE = 8;
    private static final int BANNER_SCORE = 12;
    private static final int VICTORY_DELAY = 6;
    private static final int SHIMMER_TICKS = 26;
    private static final int DEFEAT_FADE = 24;
    private static final int HEARTBEAT_TICKS = 12;
    private static final String HEARTBEAT = "minecraft:entity.warden.heartbeat";
    private static final String PUNCH = "minecraft:entity.player.attack.strong";

    /** What a viewer's round banner says. */
    public enum Banner { WON, LOST, DRAW, SPECTATOR }

    private final DuelCorePlugin plugin;

    MatchFx(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    private MainConfig cfg() {
        return plugin.settings();
    }

    /** A player's own setting (true without a loaded profile: these all default to on). */
    private boolean wants(Player player, Setting setting) {
        PlayerProfile profile = plugin.profiles().get(player);
        return profile == null || profile.setting(setting);
    }

    /** A match sound, unless the player turned match sounds off ({@link MatchSounds#matchSounds}). */
    private void sfx(Player player, Sfx.Note note) {
        if (MatchSounds.matchSounds(plugin, player)) Sfx.play(plugin, player, note);
    }

    private void sfx(Player player, List<Sfx.Note> notes) {
        if (MatchSounds.matchSounds(plugin, player)) Sfx.play(plugin, player, notes);
    }

    private MatchFxStyle style() {
        return plugin.gui().matchFx;
    }

    private Messages msg() {
        return plugin.messages();
    }

    // ------------------------------------------------------------------ countdown & fight

    /**
     * One countdown second: the number pops in (pop colour and bold, then its own colour by seconds left, then
     * normal weight) with a tick whose pitch rises each second. {@code pulse}: the subtitle (match point) flashes
     * and fades back each second, with a bell on the first one. {@code shown} is how many numbers the countdown has.
     */
    public boolean countdown(Player viewer, int secs, int shown, Component subtitle, boolean pulse) {
        if (!cfg().animCountdownPop) return false;
        MatchFxStyle s = style();
        TextColor color = MatchFxMath.countdownColor(s.countdownColors(), secs, TextFx.WHITE);
        int semis = MatchFxMath.countdownSemitones(secs, shown);
        sfx(viewer, new Sfx.Note(Sfx.HAT, Sfx.pitch(1.0f, semis), 0.6f, 0));
        sfx(viewer, new Sfx.Note(Sfx.PLING, Sfx.pitch(0.9f, semis), 0.3f, 0));
        if (pulse && secs == shown) sfx(viewer, new Sfx.Note(Sfx.BELL, 0.7f, 0.5f, 0));
        int frames = pulse ? 8 : 4;
        plugin.anim().start(viewer, Channel.TITLE, new Animation() {
            @Override
            public boolean frame(Player p, int tick) {
                if (tick == 0) p.sendTitlePart(TitlePart.TIMES, times(0, 900, 150));
                if (tick == 0 || pulse) {
                    p.sendTitlePart(TitlePart.SUBTITLE, pulse
                        ? TextFx.fade(subtitle, s.pop(), 1 - Ease.easeOutCubic(Ease.progress(tick, frames)), TextFx.WHITE) : subtitle);
                }
                if (tick <= 4) {
                    Component number = pop(secs, tick / 2, color);
                    p.sendTitlePart(TitlePart.TITLE, msg().get("match.countdown", Messages.comp("seconds", number)));
                }
                return tick < frames;
            }
        });
        return true;
    }

    /** "FIGHT!": a gradient across the letters with a bright band sweeping over it, and a punch. */
    public boolean fight(Player fighter) {
        if (!cfg().animFightSweep) return false;
        MatchFxStyle s = style();
        String text = plain(msg().get("match.fx.fight"));
        sfx(fighter, new Sfx.Note(PUNCH, 0.75f, 0.8f, 0));
        sfx(fighter, new Sfx.Note(Sfx.BASS, 0.6f, 0.7f, 0));
        plugin.anim().start(fighter, Channel.TITLE, new Animation() {
            @Override
            public boolean frame(Player p, int tick) {
                if (tick == 0) {
                    p.sendTitlePart(TitlePart.TIMES, times(0, 700, 300));
                    p.sendTitlePart(TitlePart.SUBTITLE, Component.empty());
                }
                double phase = Ease.easeOutCubic(Ease.progress(tick, FIGHT_TICKS));
                p.sendTitlePart(TitlePart.TITLE, MatchFxMath.sweep(text, s.fightColors(), s.shine(), phase, 1.8, TextDecoration.BOLD));
                return tick < FIGHT_TICKS;
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ rounds

    /**
     * Round over (the match goes on): "ROUND WON" / "ROUND LOST" (or the winner, for spectators) types in, then the
     * score below it moves from {@code before} to {@code after} ({you, opp}, or {team 1, team 2} for spectators) and
     * the number that changed pops.
     */
    public boolean roundBanner(Player viewer, Banner kind, int[] before, int[] after, int round, String winner) {
        if (!cfg().animRoundBanner) return false;
        String key = switch (kind) {
            case WON -> "match.fx.round-won";
            case LOST -> "match.fx.round-lost";
            case DRAW -> "match.fx.round-draw";
            case SPECTATOR -> "match.fx.round-spectator";
        };
        Component title = msg().get(key, Messages.num("round", round), Messages.text("winner", winner));
        sfx(viewer, switch (kind) {
            case WON -> Sfx.arpeggio(Sfx.CHIME, 3, 1.2f, 4, 2, 0.55f);
            case LOST -> Sfx.settle(false);
            case DRAW -> List.of(new Sfx.Note(Sfx.HAT, 1.0f, 0.5f, 0));
            case SPECTATOR -> List.of(new Sfx.Note(Sfx.CHIME, 1.4f, 0.45f, 0));
        });
        plugin.anim().start(viewer, Channel.TITLE, new Animation() {
            @Override
            public boolean frame(Player p, int tick) {
                if (tick == 0) {
                    p.sendTitlePart(TitlePart.TIMES, times(0, 1500, 400));
                    p.sendTitlePart(TitlePart.SUBTITLE, score(before, after, -1));
                } else if (tick >= BANNER_SCORE) {
                    p.sendTitlePart(TitlePart.SUBTITLE, score(before, after, (tick - BANNER_SCORE) / 2));
                }
                if (tick <= BANNER_TYPE) {
                    p.sendTitlePart(TitlePart.TITLE, TextFx.typewriter(title, Ease.progress(tick + 2, BANNER_TYPE + 2)));
                }
                return tick < BANNER_SCORE + 4;
            }
        });
        return true;
    }

    /** "2 — 1": {@code stage} −1 = the old score, 0..2 = the new one with the changed numbers popping. */
    private Component score(int[] before, int[] after, int stage) {
        Component[] n = new Component[2];
        for (int i = 0; i < 2; i++) {
            n[i] = stage < 0 ? Component.text(before[i]) : before[i] != after[i] ? pop(after[i], stage, null) : Component.text(after[i]);
        }
        return msg().get("match.fx.round-score", Messages.comp("you", n[0]), Messages.comp("opp", n[1]));
    }

    // ------------------------------------------------------------------ action bars

    /** The attacker's hit combo (from 2 hits on): the counter pops on every hit, holds, then fades out. */
    public boolean combo(Player attacker, int combo) {
        if (!cfg().animComboBar || combo < 2 || !wants(attacker, Setting.COMBO_BAR)) return false;
        plugin.anim().start(attacker, Channel.ACTION_BAR,
            popBar(stage -> msg().get("match.fx.combo", Messages.comp("count", pop(combo, stage, null))), COMBO_HOLD));
        return true;
    }

    /** "+1 kill · 3 hit combo" for the killer, while the round goes on. */
    public boolean kill(Player killer, int combo) {
        if (!cfg().animComboBar || !wants(killer, Setting.COMBO_BAR)) return false;
        Component comboText = combo >= 2
            ? msg().get("match.fx.kill-combo", Messages.comp("count", Component.text(combo))) : Component.empty();
        Component text = msg().get("match.fx.kill", Messages.comp("combo", comboText));
        TextColor flash = style().pop();
        plugin.anim().start(killer, Channel.ACTION_BAR, popBar(stage -> stage == 0 ? TextFx.recolor(text, flash) : text, KILL_HOLD));
        sfx(killer, new Sfx.Note(Sfx.ORB, 1.3f, 0.5f, 0));
        return true;
    }

    /** Pops in (stages 0, 1, 2 two ticks apart), holds until {@code hold}, fades out, clears. */
    private Animation popBar(IntFunction<Component> text, int hold) {
        BlinkFade fade = new BlinkFade(0, 1, BAR_FADE, style().pop(), style().fade());
        return new Animation() {
            boolean showing;

            @Override
            public boolean frame(Player p, int tick) {
                if (tick <= 4) {
                    p.sendActionBar(text.apply(tick / 2));
                    showing = true;
                    return true;
                }
                if (tick < hold) return true; // the client keeps showing it: no packets while holding
                Component c = fade.apply(text.apply(2), tick - hold);
                if (c == null) return false;
                p.sendActionBar(c);
                return true;
            }

            @Override
            public void end(Player p, End reason) {
                if (showing && reason != End.REPLACED) p.sendActionBar(Component.empty());
            }
        };
    }

    /**
     * One low-health heartbeat: a quiet heartbeat sound and, unless something else is on the action bar, a red
     * "lub-dub" pulse of the health there. The caller spaces the beats (faster when lower).
     */
    public boolean heartbeat(Player player, double hp) {
        if (!cfg().animHeartbeat || !wants(player, Setting.HEARTBEAT)) return false;
        Sfx.play(plugin, player, new Sfx.Note(HEARTBEAT, 1.0f, 0.6f, 0)); // (its own setting, not match sounds)
        if (plugin.anim().busy(player, Channel.ACTION_BAR)) return true; // a combo bar or a round result wins
        Component text = msg().get("match.fx.heartbeat", Messages.text("hp", String.format(Locale.ROOT, "%.1f", hp / 2)));
        MatchFxStyle s = style();
        plugin.anim().start(player, Channel.ACTION_BAR, new Animation() {
            boolean showing;

            @Override
            public boolean frame(Player p, int tick) {
                p.sendActionBar(TextFx.recolor(text, TextFx.lerp(s.heartbeatDim(), s.heartbeatBright(), MatchFxMath.pulse(tick))));
                showing = true;
                return tick < HEARTBEAT_TICKS;
            }

            @Override
            public void end(Player p, End reason) {
                // finished: the dim frame stays until the next beat (or fades on the client); cancelled: gone now
                if (showing && reason == End.CANCELLED) p.sendActionBar(Component.empty());
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ match end

    /**
     * Match won, after the results title went up: a victory jingle, then a shimmer sweeps over the title (unless
     * another animation already runs on the title). The confetti is {@link Animations#confetti}.
     */
    public boolean victory(Player winner) {
        if (!cfg().animVictoryTitle) return false;
        sfx(winner, MatchFxMath.victoryJingle());
        if (plugin.anim().busy(winner, Channel.TITLE)) return true;
        Component title = msg().get("results.title-victory");
        TextColor base = color(title);
        String text = plain(title);
        TextColor shine = style().shine();
        plugin.anim().start(winner, Channel.TITLE, new Animation() {
            @Override
            public boolean frame(Player p, int tick) {
                if (tick < VICTORY_DELAY) return true;
                int t = tick - VICTORY_DELAY;
                if (t == 0) p.sendTitlePart(TitlePart.TIMES, times(0, 2200, 400));
                boolean last = t >= SHIMMER_TICKS;
                p.sendTitlePart(TitlePart.TITLE, last ? title
                    : TextFx.shimmer(text, base, shine, Ease.easeInOutSine(Ease.progress(t, SHIMMER_TICKS)), 1.8));
                return !last;
            }
        });
        return true;
    }

    /** Match lost: softer. Three quiet falling notes while the results title slowly greys out a little. */
    public boolean defeat(Player loser) {
        if (!cfg().animDefeatTitle) return false;
        sfx(loser, MatchFxMath.defeatPhrase());
        if (plugin.anim().busy(loser, Channel.TITLE)) return true;
        Component title = msg().get("results.title-defeat");
        TextColor to = style().fade();
        plugin.anim().start(loser, Channel.TITLE, new Animation() {
            @Override
            public boolean frame(Player p, int tick) {
                if (tick == 0) p.sendTitlePart(TitlePart.TIMES, times(0, 2200, 600));
                double f = 0.6 * Ease.easeInOutSine(Ease.progress(tick, DEFEAT_FADE));
                p.sendTitlePart(TitlePart.TITLE, TextFx.fade(title, to, f, TextFx.WHITE));
                return tick < DEFEAT_FADE;
            }

            @Override
            public int period() {
                return 4;
            }
        });
        return true;
    }

    /** Spectators at the end: the winner types in and gets the shimmer, the final score below (or "Draw"). */
    public boolean spectatorResult(Player spectator, @Nullable String winner, int first, int second, boolean ffa) {
        if (!cfg().animVictoryTitle) return false;
        Component plainTitle = winner == null ? msg().get("match.fx.spectator-draw")
            : msg().get("match.fx.spectator-winner", Messages.text("winner", winner));
        Component subtitle = msg().get(winner == null ? "match.fx.spectator-draw-sub"
                : ffa ? "match.fx.spectator-winner-sub-ffa" : "match.fx.spectator-winner-sub",
            Messages.num("first", first), Messages.num("second", second));
        TextColor base = color(plainTitle);
        TextColor shine = style().shine();
        sfx(spectator, List.of(new Sfx.Note(Sfx.CHIME, 1.2f, 0.45f, 0), new Sfx.Note(Sfx.CHIME, 1.6f, 0.45f, 3)));
        plugin.anim().start(spectator, Channel.TITLE, new Animation() {
            @Override
            public boolean frame(Player p, int tick) {
                if (tick == 0) {
                    p.sendTitlePart(TitlePart.TIMES, times(0, 2200, 400));
                    p.sendTitlePart(TitlePart.SUBTITLE, subtitle);
                }
                if (tick <= BANNER_TYPE) {
                    p.sendTitlePart(TitlePart.TITLE, TextFx.typewriter(plainTitle, Ease.progress(tick + 2, BANNER_TYPE + 2)));
                    return true;
                }
                if (winner == null) return false;
                int t = tick - BANNER_TYPE;
                boolean last = t >= SHIMMER_TICKS;
                p.sendTitlePart(TitlePart.TITLE, last ? plainTitle : msg().get("match.fx.spectator-winner", Messages.comp("winner",
                    TextFx.shimmer(winner, base, shine, Ease.easeInOutSine(Ease.progress(t, SHIMMER_TICKS)), 1.8))));
                return !last;
            }
        });
        return true;
    }

    /** Party FFA: someone was eliminated, "3 players left" pops in the subtitle. */
    public boolean playersLeft(Player viewer, int left) {
        if (!cfg().animPlayersLeft) return false;
        sfx(viewer, new Sfx.Note(Sfx.HAT, 1.4f, 0.4f, 0));
        plugin.anim().start(viewer, Channel.TITLE, new Animation() {
            @Override
            public boolean frame(Player p, int tick) {
                if (tick == 0) p.sendTitlePart(TitlePart.TIMES, times(0, 1000, 300));
                p.sendTitlePart(TitlePart.SUBTITLE, msg().get("match.fx.players-left",
                    Messages.comp("count", pop(left, tick / 2, null)), Messages.num("players", left)));
                if (tick == 0) p.sendTitlePart(TitlePart.TITLE, Component.empty()); // the subtitle shows with a title
                return tick < 4;
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ helpers

    /** A popping number: stage 0 pop colour and bold, 1 bold in {@code color}, 2 plain in {@code color}. */
    private Component pop(int value, int stage, @Nullable TextColor color) {
        Component c = Component.text(value, stage <= 0 ? style().pop() : color);
        return stage <= 1 ? c.decorate(TextDecoration.BOLD) : c;
    }

    private static Title.Times times(int fadeInMs, int stayMs, int fadeOutMs) {
        return Title.Times.times(Duration.ofMillis(fadeInMs), Duration.ofMillis(stayMs), Duration.ofMillis(fadeOutMs));
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static TextColor color(Component c) {
        TextColor first = TextFx.firstColor(c);
        return first == null ? TextFx.WHITE : first;
    }

    // ------------------------------------------------------------------ tester previews

    /**
     * {@code /animtest play <name>} for each of these, on the tester alone (switched-off ones show nothing), plus
     * {@code match}: the whole sequence one after another (countdown, fight, combo, heartbeat, kill, round won, match
     * point, victory).
     */
    public void registerPreviews(TesterMode tester) {
        Map<String, Consumer<Player>> previews = new LinkedHashMap<>();
        previews.put("countdown", p -> {
            Component sub = msg().get("match.countdown-sub", Messages.num("round", 1), Messages.num("first_to", 3));
            for (int secs = 3; secs >= 1; secs--) {
                int s = secs;
                later(p, (3 - secs) * 20, pl -> {
                    if (!countdown(pl, s, 3, sub, false)) pl.showTitle(Title.title(
                        msg().get("match.countdown", Messages.num("seconds", s)), sub, times(0, 1100, 0)));
                });
            }
            later(p, 60, this::fight);
        });
        previews.put("fight", this::fight);
        previews.put("match-point", p -> {
            Component sub = msg().get("match.fx.match-point", Messages.text("team", p.getName()), Messages.num("round", 5));
            for (int secs = 2; secs >= 1; secs--) {
                int s = secs;
                later(p, (2 - secs) * 20, pl -> countdown(pl, s, 2, sub, true));
            }
            later(p, 40, this::fight);
        });
        previews.put("round-won", p -> roundBanner(p, Banner.WON, new int[] {1, 1}, new int[] {2, 1}, 3, p.getName()));
        previews.put("round-lost", p -> roundBanner(p, Banner.LOST, new int[] {1, 1}, new int[] {1, 2}, 3, p.getName()));
        previews.put("round-spectator", p -> roundBanner(p, Banner.SPECTATOR, new int[] {1, 1}, new int[] {2, 1}, 3, p.getName()));
        previews.put("combo", p -> {
            for (int i = 0; i < 4; i++) {
                int combo = i + 2;
                later(p, i * 8, pl -> combo(pl, combo));
            }
        });
        previews.put("kill", p -> kill(p, 3));
        previews.put("heartbeat", p -> {
            double threshold = cfg().animHeartbeatHearts * 2;
            int at = 0;
            for (double f : new double[] {0.9, 0.6, 0.35, 0.15}) {
                double hp = threshold * f;
                later(p, at, pl -> heartbeat(pl, hp));
                at += MatchFxMath.heartbeatInterval(hp, threshold);
            }
        });
        previews.put("victory", p -> {
            p.showTitle(Title.title(msg().get("results.title-victory"), msg().get("results.subtitle",
                Messages.num("you", 3), Messages.num("opp", 1), Messages.text("opponent", p.getName())), times(100, 2200, 400)));
            victory(p);
            plugin.animations().matchWin(p, List.of(p));
            plugin.animations().confetti(p, List.of(p));
        });
        previews.put("defeat", p -> {
            p.showTitle(Title.title(msg().get("results.title-defeat"), msg().get("results.subtitle",
                Messages.num("you", 1), Messages.num("opp", 3), Messages.text("opponent", p.getName())), times(100, 2200, 400)));
            defeat(p);
        });
        previews.put("spectator-result", p -> spectatorResult(p, p.getName(), 3, 1, false));
        previews.put("players-left", p -> playersLeft(p, 3));
        previews.forEach(tester::preview);
        // the whole match, start to end (ticks from the start)
        int[] at = {0, 80, 125, 200, 245, 300, 370};
        String[] parts = {"countdown", "combo", "heartbeat", "kill", "round-won", "match-point", "victory"};
        tester.preview("match", p -> {
            for (int i = 0; i < parts.length; i++) later(p, at[i], previews.get(parts[i]));
        });
    }

    /** Runs {@code action} after {@code ticks} (0 = now) if the player is still online. */
    private void later(Player player, int ticks, Consumer<Player> action) {
        if (ticks <= 0) {
            action.accept(player);
            return;
        }
        player.getScheduler().runDelayed(plugin, task -> {
            if (player.isOnline()) action.accept(player);
        }, null, ticks);
    }
}
