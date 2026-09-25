package top.cheesesmp.duelcore.ui;

import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.config.Messages;
import top.cheesesmp.duelcore.ui.anim.Animation;
import top.cheesesmp.duelcore.ui.anim.Channel;
import top.cheesesmp.duelcore.ui.anim.Ease;
import top.cheesesmp.duelcore.ui.anim.Sfx;
import top.cheesesmp.duelcore.ui.anim.TextFx;

/**
 * How friend and party alerts show up besides their chat line ({@code plugin.alerts()}): a small sound and a short
 * action bar pop (typed in, held, faded out, cleared; about 9 packets). Only in the lobby: players in a match or
 * spectating one just get the chat line. A running action bar animation (the post-match progress) is never cut off;
 * then only the sound plays. {@code animations.alert-pops} and {@code animations.hub-sounds} switch the two parts.
 */
public final class AlertPop {

    /** What happened; picks the sound. */
    public enum Kind { FRIEND_ONLINE, FOLLOWED, NOW_FRIENDS, PARTY_INVITE, PARTY_JOINED }

    private static final int TYPE_TICKS = 6;
    private static final int FADE_AT = 36;
    private static final int FADE_TICKS = 10;

    private final DuelCorePlugin plugin;

    public AlertPop(DuelCorePlugin plugin) {
        this.plugin = plugin;
        plugin.tester().preview("alert-friend", p -> force(p, Kind.FRIEND_ONLINE, "hub.alerts.friend-online",
            Messages.text("player", p.getName())));
        plugin.tester().preview("alert-party", p -> force(p, Kind.PARTY_INVITE, "hub.alerts.party-invite",
            Messages.text("player", p.getName())));
    }

    /** Pops {@code key} (messages.yml, "" = no pop) with the sound of {@code kind} for a player in the lobby. */
    public void pop(Player player, Kind kind, String key, TagResolver... resolvers) {
        UUID uuid = player.getUniqueId();
        if (plugin.matches().match(uuid) != null || plugin.spectate().spectating(uuid) != null) return;
        force(player, kind, key, resolvers);
    }

    private void force(Player player, Kind kind, String key, TagResolver... resolvers) {
        if (plugin.settings().animHubSounds) {
            List<Sfx.Note> notes = notes(kind);
            // a phrase would cut off one already playing (a tier-up flourish): then only the first note
            if (plugin.anim().busy(player, Channel.SOUND)) Sfx.play(plugin, player, notes.getFirst());
            else Sfx.play(plugin, player, notes);
        }
        String template = plugin.messages().raw(key);
        if (!plugin.settings().animAlertPops || template.isBlank() || plugin.anim().busy(player, Channel.ACTION_BAR)) return;
        plugin.anim().start(player, Channel.ACTION_BAR, popAnimation(plugin.messages().parse(template, resolvers)));
    }

    private Animation popAnimation(Component text) {
        var fadeTo = plugin.gui().revealFade;
        return new Animation() {
            boolean shown;

            @Override
            public boolean frame(Player p, int tick) {
                if (tick <= TYPE_TICKS) {
                    p.sendActionBar(TextFx.typewriter(text, Ease.easeOutQuad(Ease.progress(tick + 2, TYPE_TICKS + 2))));
                } else if (tick >= FADE_AT && tick < FADE_AT + FADE_TICKS) {
                    p.sendActionBar(TextFx.fade(text, fadeTo, Ease.progress(tick - FADE_AT + 2, FADE_TICKS), TextFx.WHITE));
                } else if (tick >= FADE_AT + FADE_TICKS) {
                    return false;
                } else {
                    return true; // holding: the client keeps showing the bar on its own
                }
                shown = true;
                return true;
            }

            @Override
            public void end(Player p, End reason) {
                if (shown && reason != End.REPLACED) p.sendActionBar(Component.empty());
            }
        };
    }

    /** The sound of an alert: soft note block phrases, at most three notes. Pure. */
    static List<Sfx.Note> notes(Kind kind) {
        return switch (kind) {
            case FRIEND_ONLINE -> List.of(new Sfx.Note(Sfx.CHIME, 1.4f, 0.4f, 0), new Sfx.Note(Sfx.CHIME, Sfx.pitch(1.4f, 5), 0.4f, 3));
            case FOLLOWED -> List.of(new Sfx.Note(Sfx.PLING, 1.6f, 0.35f, 0));
            case NOW_FRIENDS -> Sfx.arpeggio(Sfx.CHIME, 3, 1.2f, 4, 2, 0.4f);
            case PARTY_INVITE -> List.of(new Sfx.Note(Sfx.BELL, 1.2f, 0.45f, 0), new Sfx.Note(Sfx.BELL, Sfx.pitch(1.2f, 7), 0.45f, 4));
            case PARTY_JOINED -> List.of(new Sfx.Note(Sfx.AMETHYST, 1.4f, 0.7f, 0));
        };
    }
}
