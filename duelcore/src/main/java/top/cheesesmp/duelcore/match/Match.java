package top.cheesesmp.duelcore.match;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.arena.ArenaInstance;
import top.cheesesmp.duelcore.kit.Kit;

/**
 * State of one running match. Main thread only. Teams are numbered from 0: a 1v1 has teams 0 and 1 with one player
 * each, team games have two teams of several players, and a free-for-all ({@link #ffa()}) one team per fighter.
 */
public final class Match {

    public enum State { STARTING, PREPARING, COUNTDOWN, FIGHTING, ROUND_END, ENDING, ENDED }

    public enum Origin { QUEUE, DUEL, TOURNAMENT, PARTY }

    public enum EndReason {
        SCORE(0), FORFEIT_QUIT(1), FORFEIT_COMMAND(2), CANCELLED(3), DRAW(4), ADMIN(5), NO_ARENA(6),
        /** A ranked player's connection dropped and a disconnect save voided the match (no Elo change). */
        CONNECTION_LOST(7),
        /** A player used /leave before the first fight started: no result, nothing is saved. */
        LEFT_BEFORE_START(8);

        private final int id;

        EndReason(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public boolean countsForRating() {
            return this == SCORE || this == FORFEIT_QUIT || this == FORFEIT_COMMAND || this == DRAW;
        }
    }

    private final int id;
    private final Kit kit;
    private final boolean ranked;
    private final Origin origin;
    private final int firstTo;
    private final boolean ffa;
    private final List<Participant> participants;
    private final Map<UUID, Participant> byUuid = new HashMap<>();
    private final Set<UUID> spectators = new LinkedHashSet<>();
    private final List<Integer> roundWinners = new ArrayList<>();
    private final List<Consumer<Match>> endListeners = new ArrayList<>();
    private final long createdAt = System.currentTimeMillis();

    @Nullable ArenaInstance arena;
    State state = State.STARTING;
    int stateTicks;
    int round;
    /** Round wins per team (at least two teams). */
    final int[] score;
    long firstFightAt;
    int roundTicks;
    int winnerTeam = -1;
    @Nullable EndReason endReason;
    boolean arenaResetting;
    /** Fighters still being carried back to their spawn (respawn animation). */
    int pulling;
    boolean persisted;
    @Nullable String arenaName;

    public Match(int id, Kit kit, boolean ranked, Origin origin, List<Participant> participants) {
        this(id, kit, ranked, origin, participants, false);
    }

    /** {@code ffa}: a free-for-all, every fighter on their own team, played as one round (first to 1). */
    public Match(int id, Kit kit, boolean ranked, Origin origin, List<Participant> participants, boolean ffa) {
        this.id = id;
        this.kit = kit;
        this.ranked = ranked;
        this.origin = origin;
        this.ffa = ffa;
        this.firstTo = ffa ? 1 : kit.firstTo();
        this.participants = List.copyOf(participants);
        int teams = 2;
        for (Participant p : participants) {
            byUuid.put(p.uuid(), p);
            teams = Math.max(teams, p.team() + 1);
        }
        this.score = new int[teams];
    }

    public int id() {
        return id;
    }

    public Kit kit() {
        return kit;
    }

    public boolean ranked() {
        return ranked;
    }

    public Origin origin() {
        return origin;
    }

    public int firstTo() {
        return firstTo;
    }

    /** Free-for-all: last one standing wins (spawns: {@link Teams#ring}). */
    public boolean ffa() {
        return ffa;
    }

    /** Number of teams: 2 for duels and team games, one per fighter in a free-for-all. */
    public int teamCount() {
        return score.length;
    }

    /** Fighters still standing this round. */
    public int alive() {
        int n = 0;
        for (Participant p : participants) if (p.alive && !p.left) n++;
        return n;
    }

    public List<Participant> participants() {
        return participants;
    }

    public @Nullable Participant participant(UUID uuid) {
        return byUuid.get(uuid);
    }

    public List<Participant> team(int team) {
        List<Participant> list = new ArrayList<>(2);
        for (Participant p : participants) if (p.team() == team) list.add(p);
        return list;
    }

    /** First opponent of a participant (1v1 convenience). */
    public @Nullable Participant opponentOf(Participant p) {
        for (Participant other : participants) if (other.team() != p.team()) return other;
        return null;
    }

    public Set<UUID> spectators() {
        return spectators;
    }

    public @Nullable ArenaInstance arena() {
        return arena;
    }

    public State state() {
        return state;
    }

    public int round() {
        return round;
    }

    /** Round wins of a team (0 for a team that doesn't exist). */
    public int score(int team) {
        return team >= 0 && team < score.length ? score[team] : 0;
    }

    public List<Integer> roundWinners() {
        return Collections.unmodifiableList(roundWinners);
    }

    void addRoundWinner(int team) {
        roundWinners.add(team);
    }

    public int roundTicks() {
        return roundTicks;
    }

    public long createdAt() {
        return createdAt;
    }

    public long firstFightAt() {
        return firstFightAt;
    }

    public int winnerTeam() {
        return winnerTeam;
    }

    public @Nullable EndReason endReason() {
        return endReason;
    }

    public boolean isOver() {
        return state == State.ENDING || state == State.ENDED;
    }

    public boolean isFighting() {
        return state == State.FIGHTING;
    }

    /** "Name", "A & B", "A & B & C", or "A & B +3" for bigger (party) teams. */
    public String teamName(int team) {
        List<Participant> t = team(team);
        if (t.isEmpty()) return "?";
        if (t.size() == 1) return t.get(0).name();
        if (t.size() > 3) return t.get(0).name() + " & " + t.get(1).name() + " +" + (t.size() - 2);
        return String.join(" & ", t.stream().map(Participant::name).toList());
    }

    /** Called once when the match has fully ended (after results). Used by tournaments. */
    public void onEnd(Consumer<Match> listener) {
        endListeners.add(listener);
    }

    List<Consumer<Match>> endListeners() {
        return endListeners;
    }

    /** Compact round history, e.g. "0110" = team 0, 1, 1, 0 won rounds 1–4 ("d" for draws, "A"… for teams 10+). */
    public String roundString() {
        StringBuilder sb = new StringBuilder();
        for (int w : roundWinners) sb.append(w < 0 ? 'd' : w < 10 ? (char) ('0' + w) : (char) ('A' + Math.min(w - 10, 25)));
        return sb.toString();
    }
}
