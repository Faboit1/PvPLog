package top.cheesesmp.duelcore.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import top.cheesesmp.duelcore.match.Match;

/** Fired once a match has a result (before players return to the hub). Ratings are already updated. */
public final class MatchEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Match match;

    public MatchEndEvent(Match match) {
        this.match = match;
    }

    public Match getMatch() {
        return match;
    }

    /** Winning team (0 or 1), or -1 for a draw / cancelled match. */
    public int getWinnerTeam() {
        return match.winnerTeam();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
