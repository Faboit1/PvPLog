package top.cheesesmp.duelcore.party;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PartyTeamsTest {

    private static UUID id(int n) {
        return new UUID(0, n);
    }

    private static List<UUID> ids(int n) {
        return IntStream.range(0, n).mapToObj(PartyTeamsTest::id).toList();
    }

    @Test
    void autoUntilPicked() {
        PartyTeams teams = new PartyTeams();
        assertFalse(teams.picked());
        assertEquals(-1, teams.team(id(0)));
        teams.join(id(0)); // auto: nothing is remembered
        assertEquals(0, teams.size(0));
        assertFalse(teams.set(id(0), 1));
    }

    @Test
    void alternatePutsTheAvailableMembersFirstAndBalanced() {
        PartyTeams teams = new PartyTeams();
        List<UUID> members = ids(6);
        Set<UUID> away = Set.of(id(1), id(2)); // e.g. in a match
        teams.alternate(members, u -> !away.contains(u));
        assertTrue(teams.picked());
        // available 0,3,4,5 alternate: 0->0, 3->1, 4->0, 5->1; then 1 and 2 join the smaller team
        assertEquals(0, teams.team(id(0)));
        assertEquals(1, teams.team(id(3)));
        assertEquals(0, teams.team(id(4)));
        assertEquals(1, teams.team(id(5)));
        assertEquals(3, teams.size(0));
        assertEquals(3, teams.size(1));
    }

    @Test
    void randomizeIsBalancedAmongAvailableAndVaries() {
        Set<Set<UUID>> seen = new HashSet<>();
        for (int seed = 0; seed < 30; seed++) {
            PartyTeams teams = new PartyTeams();
            List<UUID> members = ids(9);
            teams.randomize(members, u -> !u.equals(id(8)), new Random(seed));
            int[] now = new int[2];
            Set<UUID> first = new HashSet<>();
            for (UUID u : members) {
                int t = teams.team(u);
                assertTrue(t == 0 || t == 1, "everyone has a team");
                if (!u.equals(id(8))) now[t]++;
                if (t == 0) first.add(u);
            }
            assertEquals(4, now[0], "available members balanced");
            assertEquals(4, now[1]);
            seen.add(first);
        }
        assertTrue(seen.size() > 5, "random");
    }

    @Test
    void setJoinAndLeave() {
        PartyTeams teams = new PartyTeams();
        teams.alternate(ids(2), u -> true);
        assertTrue(teams.set(id(1), 0));
        assertEquals(2, teams.size(0));
        assertFalse(teams.set(id(1), 2), "only teams 0 and 1");
        assertFalse(teams.set(id(1), -1));
        assertFalse(teams.set(id(7), 1), "not a member");
        assertEquals(-1, teams.team(id(7)));
        teams.join(id(2)); // the smaller team (2 against 0)
        assertEquals(1, teams.team(id(2)));
        teams.join(id(3)); // still smaller (2 against 1)
        assertEquals(1, teams.team(id(3)));
        teams.join(id(4)); // tie: team 0
        assertEquals(0, teams.team(id(4)));
        teams.join(id(4)); // already in: stays
        assertEquals(0, teams.team(id(4)));
        teams.leave(id(2));
        assertEquals(-1, teams.team(id(2)));
        assertEquals(1, teams.size(1));
        teams.auto();
        assertFalse(teams.picked());
        assertEquals(-1, teams.team(id(0)));
    }

    @Test
    void partyKeepsTeamsInStepWithMembers() {
        Party party = new Party("x", id(0), false, null, 1);
        for (int i = 0; i < 3; i++) party.add(new Party.Member(i, id(i), "p" + i, i, false));
        party.teams().alternate(party.leaderFirstIds(), u -> true);
        assertEquals(List.of(0, 1, 0), List.of(party.teams().team(id(0)), party.teams().team(id(1)), party.teams().team(id(2))));
        party.add(new Party.Member(3, id(3), "p3", 3, false)); // joins the smaller team
        assertEquals(1, party.teams().team(id(3)));
        party.remove(id(1));
        assertEquals(-1, party.teams().team(id(1)));
        assertEquals(1, party.teams().size(1));
    }

    @Test
    void applyLeavesOutWhoSitsOutAndValidates() {
        PartyTeams teams = new PartyTeams();
        teams.alternate(ids(4), u -> true); // 0,2 -> team 0; 1,3 -> team 1
        Function<UUID, UUID> same = u -> u;
        List<List<UUID>> all = teams.apply(ids(4), same);
        assertEquals(List.of(id(0), id(2)), all.get(0));
        assertEquals(List.of(id(1), id(3)), all.get(1));
        assertTrue(PartyTeams.playable(all));
        // 1 and 3 sit out: team 2 has nobody -> not playable (the start is refused)
        List<List<UUID>> partial = teams.apply(List.of(id(0), id(2)), same);
        assertEquals(List.of(id(0), id(2)), partial.get(0));
        assertTrue(partial.get(1).isEmpty());
        assertFalse(PartyTeams.playable(partial));
        // someone without a team (not expected) joins the smaller side
        List<UUID> players = new ArrayList<>(List.of(id(0), id(2), id(1)));
        players.add(id(9));
        List<List<UUID>> loose = teams.apply(players, same);
        assertEquals(List.of(id(1), id(9)), loose.get(1));
        assertFalse(PartyTeams.playable(List.of(List.of(1))));
        assertFalse(PartyTeams.playable(List.of(List.of(), List.of(1))));
    }
}
