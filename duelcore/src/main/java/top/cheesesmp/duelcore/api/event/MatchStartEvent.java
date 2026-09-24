package top.cheesesmp.duelcore.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import top.cheesesmp.duelcore.match.Match;

/** Fired when a match has been created (players paired, arena being prepared). */
public final class MatchStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Match match;

    public MatchStartEvent(Match match) {
        this.match = match;
    }

    public Match getMatch() {
        return match;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
