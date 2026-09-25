package top.cheesesmp.duelcore.ui;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.kit.Kit;
import top.cheesesmp.duelcore.match.Match;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.ui.anim.Animation;
import top.cheesesmp.duelcore.ui.anim.Channel;
import top.cheesesmp.duelcore.ui.anim.Ease;
import top.cheesesmp.duelcore.ui.anim.Sfx;
import top.cheesesmp.duelcore.ui.anim.TextFx;

/**
 * The animated "match found" title ({@code animations.match-found-reveal}): "MATCH FOUND" (messages.yml
 * {@code match.found-reveal}) brightens out of the dark while a light band sweeps over it, then the subtitle (the
 * opponent with their tier, the kit and the mode: {@code match.found-subtitle}) is typed out. A whoosh plays at the
 * start and a chime once the name is there ({@code animations.queue-sounds}, the player's sound setting). The totem
 * pop and the {@code match-found-sounds} pool are played by MatchService as before.
 *
 * <p>Runs on the {@link Channel#TITLE} channel and survives the teleport into the arena. It is over after about
 * 1.4 s, before the first countdown title, and stops early if the countdown has begun anyway.
 */
public final class MatchFoundReveal {

    /** Ticks the brightening takes, and the band's sweep over "MATCH FOUND". */
    private static final int BRIGHTEN = 6;
    private static final int SWEEP = 16;
    /** The subtitle is typed from TYPE_START for TYPE ticks; the last frame (long stay, fade out) comes after it. */
    private static final int TYPE_START = 8;
    private static final int TYPE = 14;
    private static final int LAST = TYPE_START + TYPE + 4;
    private static final String WHOOSH = "minecraft:entity.player.attack.sweep";
    private static final TextColor DARK = TextColor.color(0x303236);

    private final DuelCorePlugin plugin;

    public MatchFoundReveal(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** Plays the reveal; {@code match} (null for previews) stops it once the countdown or fight has begun. */
    public void play(Player player, @Nullable Match match, Component subtitle) {
        Component source = plugin.messages().get("match.found-reveal");
        String text = PlainTextComponentSerializer.plainText().serialize(source);
        TextColor found = TextFx.firstColor(source);
        TextColor base = found == null ? TextFx.WHITE : found;
        TextColor shine = plugin.gui().revealShimmer;
        boolean sounds = plugin.settings().animQueueSounds;
        plugin.anim().start(player, Channel.TITLE, new Animation() {
            boolean shown;

            @Override
            public boolean frame(Player p, int tick) {
                if (match != null && (match.isOver() || match.state() == Match.State.COUNTDOWN || match.state() == Match.State.FIGHTING)) {
                    return false;
                }
                if (tick == 0 && sounds) Sfx.play(plugin, p, notes());
                TextColor color = TextFx.lerp(DARK, base, Ease.easeOutCubic(Ease.progress(tick, BRIGHTEN)));
                Component title = TextFx.shimmer(text, color, shine, Ease.easeInOutSine(Ease.progress(tick, SWEEP)), 2.0);
                Component sub = TextFx.typewriter(subtitle, Ease.easeOutQuad(Ease.progress(tick - TYPE_START, TYPE)));
                boolean last = tick >= LAST;
                p.showTitle(Title.title(title, sub, Title.Times.times(Duration.ZERO,
                    Duration.ofMillis(last ? 1400 : 400), Duration.ofMillis(last ? 300 : 0))));
                shown = true;
                return !last;
            }

            @Override
            public void end(Player p, End reason) {
                if (shown && reason == End.CANCELLED) p.clearTitle();
            }

            @Override
            public boolean survivesWorldChange() {
                return true;
            }
        });
    }

    /** A soft whoosh as the title comes in, a chime when the opponent's name is typed out. */
    private static List<Sfx.Note> notes() {
        return List.of(new Sfx.Note(WHOOSH, 0.7f, 0.45f, 0),
            new Sfx.Note(Sfx.CHIME, 1.5f, 0.5f, TYPE_START + TYPE - 2),
            new Sfx.Note(Sfx.AMETHYST, 1.3f, 0.7f, TYPE_START + TYPE));
    }

    /**
     * {@code /animtest play match-found}: the whole moment on yourself, with yourself as the opponent in the first
     * kit: the totem pop, the sound pool and the reveal (or the plain title when the reveal is switched off).
     */
    public void preview(Player player) {
        List<Kit> kits = plugin.kits().enabled();
        if (kits.isEmpty()) return;
        Kit kit = kits.getFirst();
        PlayerProfile profile = plugin.profiles().get(player);
        Component subtitle = plugin.messages().get("match.found-subtitle",
            Messages.text("opponent", player.getName()),
            Messages.comp("tier", plugin.tiers().format(profile == null ? null
                : plugin.tiers().kitTier(kit.id(), plugin.profiles().stats(profile, kit.id())))),
            Messages.comp("kit", kit.displayName()),
            Messages.comp("kit_icon", kit.sprite()),
            Messages.text("mode", plugin.messages().raw("mode.ranked")),
            Messages.text("region", profile == null || profile.region() == null ? "" : profile.region()),
            Messages.num("players", 2));
        if (plugin.settings().totemPop) TotemPop.play(plugin, player, kit.icon());
        MatchSounds.play(plugin, player, plugin.settings().matchFoundSounds.pick(ThreadLocalRandom.current()), 1);
        if (plugin.settings().animMatchFound) {
            play(player, null, subtitle);
        } else {
            player.showTitle(Title.title(plugin.messages().get("match.found-title"), subtitle,
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1600), Duration.ofMillis(300))));
        }
    }
}
