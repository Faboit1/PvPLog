package top.cheesesmp.duelcore.hub;

import java.time.Duration;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.profile.PlayerProfile;
import top.cheesesmp.duelcore.ui.anim.Animation;
import top.cheesesmp.duelcore.ui.anim.Channel;
import top.cheesesmp.duelcore.ui.anim.Ease;
import top.cheesesmp.duelcore.ui.anim.Sfx;
import top.cheesesmp.duelcore.ui.anim.TextFx;

/**
 * The join title ({@code animations.join-welcome}): "Welcome back, name" typed out on the TITLE channel, then the
 * subtitle (their best kit's icon and tier with the overall Elo, or a hint while unranked), with a soft chime. A
 * first join gets "Welcome to Cheese PvP". Texts: messages.yml {@code hub.welcome-*}.
 */
final class JoinWelcome {

    /** Ticks before typing starts (the client is still on the loading screen right after joining). */
    private static final int DELAY = 16;
    private static final int TYPE_TICKS = 16;
    private static final int SUBTITLE_TICKS = 10;

    private final DuelCorePlugin plugin;

    JoinWelcome(DuelCorePlugin plugin) {
        this.plugin = plugin;
        plugin.tester().preview("welcome", p -> play(p, false));
        plugin.tester().preview("welcome-first", p -> play(p, true));
    }

    void play(Player player) {
        play(player, !player.hasPlayedBefore());
    }

    private void play(Player player, boolean first) {
        plugin.anim().start(player, Channel.TITLE, new Animation() {
            @Nullable Component title;
            @Nullable Component subtitle;
            boolean shown;

            @Override
            public boolean frame(Player p, int tick) {
                if (tick < DELAY) return true;
                int t = tick - DELAY;
                if (title == null || subtitle == null) {
                    // rendered once typing starts: the profile and the tag are there by then
                    title = plugin.messages().get(first ? "hub.welcome-first-title" : "hub.welcome-back-title",
                        Messages.text("player", p.getName()));
                    subtitle = subtitle(p, first);
                    if (plugin.settings().animHubSounds) Sfx.play(plugin, p, chime());
                }
                boolean last = t >= TYPE_TICKS + SUBTITLE_TICKS;
                p.showTitle(Title.title(TextFx.typewriter(title, Ease.progress(t + 2, TYPE_TICKS)),
                    TextFx.typewriter(subtitle, Ease.progress(t - TYPE_TICKS, SUBTITLE_TICKS)),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(last ? 2200 : 600), Duration.ofMillis(last ? 700 : 0))));
                shown = true;
                return !last;
            }

            @Override
            public void end(Player p, End reason) {
                if (shown && reason == End.CANCELLED) p.clearTitle();
            }
        });
    }

    private Component subtitle(Player p, boolean first) {
        PlayerProfile profile = plugin.profiles().get(p);
        if (first || profile == null || profile.overall() == null) return plugin.messages().get("hub.welcome-subtitle-unranked");
        return plugin.messages().get("hub.welcome-subtitle", Messages.comp("tier", plugin.tags().tag(p.getUniqueId())),
            Messages.comp("overall", plugin.tiers().format(profile.overall())), Messages.num("elo", profile.elo()));
    }

    /** Two soft chimes as typing starts and a high amethyst note when the name is complete. */
    private static List<Sfx.Note> chime() {
        return List.of(new Sfx.Note(Sfx.CHIME, 1.2f, 0.35f, 0), new Sfx.Note(Sfx.CHIME, Sfx.pitch(1.2f, 4), 0.35f, 3),
            new Sfx.Note(Sfx.AMETHYST, 1.6f, 0.7f, TYPE_TICKS));
    }
}
