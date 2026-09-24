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

/** State of one running match. Main thread only. Teams are 0 and 1; a 1v1 has one player per team. */
public final class Match {

    public enum State { STARTING, PREPARING, COUNTDOWN, FIGHTING, ROUND_END, ENDING, ENDED }

    public enum Origin { QUEUE, DUEL, TOURNAMENT, PARTY }

    public enum EndReason {
        SCORE(0), FORFEIT_QUIT(1), FORFEIT_COMMAND(2), CANCELLED(3), DRAW(4), ADMIN(5), NO_ARENA(6);

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
    final int[] score = new int[2];
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
        this.id = id;
        this.kit = kit;
        this.ranked = ranked;
        this.origin = origin;
        this.firstTo = kit.firstTo();
        this.participants = List.copyOf(participants);
        for (Participant p : participants) byUuid.put(p.uuid(), p);
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

    public int score(int team) {
        return score[team];
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

    public String teamName(int team) {
        List<Participant> t = team(team);
        if (t.isEmpty()) return "?";
        if (t.size() == 1) return t.get(0).name();
        return String.join(" & ", t.stream().map(Participant::name).toList());
    }

    /** Called once when the match has fully ended (after results). Used by tournaments. */
    public void onEnd(Consumer<Match> listener) {
        endListeners.add(listener);
    }

    List<Consumer<Match>> endListeners() {
        return endListeners;
    }

    /** Compact round history, e.g. "0110" = team 0, 1, 1, 0 won rounds 1–4 ("d" for draws). */
    public String roundString() {
        StringBuilder sb = new StringBuilder();
        for (int w : roundWinners) sb.append(w < 0 ? 'd' : (char) ('0' + w));
        return sb.toString();
    }
}
