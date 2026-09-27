package top.cheesesmp.duelcore.party;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The Party Duel teams of a party. Auto (the default): two random, balanced teams are drawn for every match. Picked:
 * the leader chose them ({@code /party teams}), every member has a team (0 or 1) that is kept until the leader changes
 * it or goes back to auto; members who join later go to the smaller team, members who leave drop out. Memory only
 * (back to auto after a restart). Main thread only; the rules are pure (unit tested), {@link PartyService} checks who
 * may change them.
 */
public final class PartyTeams {

    private final Map<UUID, Integer> teams = new LinkedHashMap<>();
    private boolean picked;

    /** True when the leader picked the teams; false = random teams every match. */
    public boolean picked() {
        return picked;
    }

    /** The member's team (0 or 1); -1 in auto mode or for someone who isn't a member. */
    public int team(UUID uuid) {
        Integer team = picked ? teams.get(uuid) : null;
        return team == null ? -1 : team;
    }

    /** Members on that team (0 in auto mode). */
    public int size(int team) {
        int n = 0;
        for (int t : teams.values()) if (t == team) n++;
        return n;
    }

    /** Back to random teams every match. */
    void auto() {
        picked = false;
        teams.clear();
    }

    /**
     * Picks teams by taking turns in {@code members}' order (the leader first: team 0), the available members first so
     * that the ones who can play now are balanced; the others then join the smaller team.
     */
    void alternate(List<UUID> members, Predicate<UUID> available) {
        List<UUID> now = new ArrayList<>();
        List<UUID> later = new ArrayList<>();
        for (UUID uuid : members) (available.test(uuid) ? now : later).add(uuid);
        fill(now, later);
    }

    /** Picks two random, balanced teams of the available members; the others then join the smaller team. */
    void randomize(List<UUID> members, Predicate<UUID> available, Random random) {
        List<UUID> now = new ArrayList<>();
        List<UUID> later = new ArrayList<>();
        for (UUID uuid : members) (available.test(uuid) ? now : later).add(uuid);
        Collections.shuffle(now, random);
        fill(now, later);
    }

    private void fill(List<UUID> alternating, List<UUID> rest) {
        teams.clear();
        picked = true;
        for (int i = 0; i < alternating.size(); i++) teams.put(alternating.get(i), i % 2);
        for (UUID uuid : rest) join(uuid);
    }

    /** Moves a member to team 0 or 1. False (nothing changes) in auto mode, for another team or a non-member. */
    boolean set(UUID uuid, int team) {
        if (!picked || team < 0 || team > 1 || !teams.containsKey(uuid)) return false;
        teams.put(uuid, team);
        return true;
    }

    /** A new member joins the smaller team (team 0 on a tie); nothing in auto mode. */
    void join(UUID uuid) {
        if (!picked || teams.containsKey(uuid)) return;
        teams.put(uuid, size(1) < size(0) ? 1 : 0);
    }

    /** A member left the party. */
    void leave(UUID uuid) {
        teams.remove(uuid);
    }

    /**
     * The picked teams of the players who play now, in their order: players who sit out are simply not in
     * {@code players}; one without a team (not expected) joins the smaller side. Only for picked teams.
     */
    public <T> List<List<T>> apply(List<T> players, Function<T, UUID> id) {
        List<T> a = new ArrayList<>();
        List<T> b = new ArrayList<>();
        List<T> loose = new ArrayList<>();
        for (T p : players) {
            switch (team(id.apply(p))) {
                case 0 -> a.add(p);
                case 1 -> b.add(p);
                default -> loose.add(p);
            }
        }
        for (T p : loose) (b.size() < a.size() ? b : a).add(p);
        return List.of(a, b);
    }

    /** Two teams with at least one player each. */
    public static boolean playable(List<? extends List<?>> teams) {
        return teams.size() == 2 && !teams.get(0).isEmpty() && !teams.get(1).isEmpty();
    }
}
