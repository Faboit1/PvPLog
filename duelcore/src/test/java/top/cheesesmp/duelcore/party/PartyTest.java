package top.cheesesmp.duelcore.party;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PartyTest {

    private static Party.Member member(int id, long joinedAt) {
        return new Party.Member(id, new UUID(0, id), "p" + id, joinedAt, false);
    }

    @Test
    void successorIsTheLongestStandingOnlineMember() {
        Party.Member leader = member(1, 100);
        Party.Member old = member(2, 200);
        Party.Member mid = member(3, 300);
        Party.Member young = member(4, 400);
        List<Party.Member> members = List.of(leader, old, mid, young);
        // everyone online: the oldest member after the leader
        assertEquals(old, Party.successor(members, leader.uuid(), u -> true));
        // online members win over older offline ones
        assertEquals(mid, Party.successor(members, leader.uuid(), u -> !u.equals(old.uuid())));
        // nobody online: still the longest-standing
        assertEquals(old, Party.successor(members, leader.uuid(), u -> false));
        // same join time: lowest player id
        Party.Member twin = member(0, 200);
        assertEquals(twin, Party.successor(List.of(leader, old, twin), leader.uuid(), u -> true));
        // nobody left
        assertNull(Party.successor(List.of(leader), leader.uuid(), u -> true));
        // repairing a party without a (known) leader
        assertEquals(leader, Party.successor(members, null, u -> true));
    }

    @Test
    void partyKeepsJoinOrderAndPutsTheLeaderFirst() {
        Party.Member a = member(1, 100);
        Party.Member b = member(2, 200);
        Party.Member c = member(3, 300);
        Party party = new Party("x", c.uuid(), false, null, 1);
        party.add(a);
        party.add(b);
        party.add(c);
        assertEquals(List.of(c, a, b), party.leaderFirst());
        assertEquals(List.of(a, b, c), new ArrayList<>(party.members()));
        assertEquals(b, party.member("P2"));
        party.remove(b.uuid());
        assertEquals(2, party.size());
        assertFalse(party.contains(b.uuid()));
    }

    @Test
    void splitMakesTwoBalancedRandomTeams() {
        for (int n = 2; n <= 20; n++) {
            List<Integer> players = IntStream.range(0, n).boxed().toList();
            List<List<Integer>> teams = Party.split(players, new Random(n));
            assertEquals(2, teams.size());
            assertTrue(Math.abs(teams.get(0).size() - teams.get(1).size()) <= 1, "balanced for " + n);
            Set<Integer> all = new HashSet<>(teams.get(0));
            all.addAll(teams.get(1));
            assertEquals(new HashSet<>(players), all, "everyone plays exactly once for " + n);
            assertEquals(n, teams.get(0).size() + teams.get(1).size());
        }
        // random: different seeds give different teams (for 10 players)
        List<Integer> ten = IntStream.range(0, 10).boxed().toList();
        Set<Set<Integer>> seen = new HashSet<>();
        for (int seed = 0; seed < 20; seed++) seen.add(new HashSet<>(Party.split(ten, new Random(seed)).get(0)));
        assertTrue(seen.size() > 5);
    }

    @Test
    void passwordsAreSaltedHashes() {
        String hash = PartyPasswords.hash("s3cret!");
        assertTrue(hash.startsWith("p1$"));
        assertTrue(hash.length() <= 64, "fits dc_parties.password: " + hash.length());
        assertFalse(hash.contains("s3cret"));
        assertTrue(PartyPasswords.verify("s3cret!", hash));
        assertFalse(PartyPasswords.verify("s3cret", hash));
        assertFalse(PartyPasswords.verify("S3CRET!", hash));
        // salted: the same password hashes differently every time
        String again = PartyPasswords.hash("s3cret!");
        assertNotEquals(hash, again);
        assertTrue(PartyPasswords.verify("s3cret!", again));
        // malformed or missing hashes never match
        assertFalse(PartyPasswords.verify("x", null));
        assertFalse(PartyPasswords.verify("x", "plain"));
        assertFalse(PartyPasswords.verify("x", "p1$not base64$!!"));
        assertFalse(PartyPasswords.verify("x", "p1$abc"));
    }

    @Test
    void passwordRules() {
        assertTrue(PartyPasswords.valid("a"));
        assertTrue(PartyPasswords.valid("x".repeat(PartyPasswords.MAX_LENGTH)));
        assertTrue(PartyPasswords.valid("p@ss_wörd!"));
        assertFalse(PartyPasswords.valid(null));
        assertFalse(PartyPasswords.valid(""));
        assertFalse(PartyPasswords.valid("x".repeat(PartyPasswords.MAX_LENGTH + 1)));
        assertFalse(PartyPasswords.valid("two words"));
        assertFalse(PartyPasswords.valid("tab\there"));
    }

    @Test
    void modesRoundTrip() {
        for (PartyService.Mode m : PartyService.Mode.values()) assertEquals(m, PartyService.Mode.parse(m.id()));
        assertNull(PartyService.Mode.parse("nope"));
        assertNull(PartyService.Mode.parse(null));
    }
}
