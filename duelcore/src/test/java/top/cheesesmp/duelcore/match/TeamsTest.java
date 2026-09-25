package top.cheesesmp.duelcore.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TeamsTest {

    @Test
    void twoTeamRoundOverIsUnchanged() {
        assertEquals(Teams.ONGOING, Teams.outcome(new boolean[] {true, true}));
        assertEquals(0, Teams.outcome(new boolean[] {true, false}));
        assertEquals(1, Teams.outcome(new boolean[] {false, true}));
        assertEquals(-1, Teams.outcome(new boolean[] {false, false}));
    }

    @Test
    void freeForAllEndsWithTheLastOneStanding() {
        assertEquals(Teams.ONGOING, Teams.outcome(new boolean[] {true, false, true, false}));
        assertEquals(Teams.ONGOING, Teams.outcome(new boolean[] {true, true, true}));
        assertEquals(2, Teams.outcome(new boolean[] {false, false, true, false}));
        assertEquals(-1, Teams.outcome(new boolean[] {false, false, false, false}));
        assertEquals(-1, Teams.outcome(new boolean[0]));
    }

    @Test
    void timeoutWinnerNeedsAClearLead() {
        // same rule as the old two-team check: |h0 - h1| > 0.001
        assertEquals(0, Teams.best(new double[] {0.8, 0.5}, 0.001));
        assertEquals(1, Teams.best(new double[] {0.5, 0.8}, 0.001));
        assertEquals(-1, Teams.best(new double[] {0.5, 0.5005}, 0.001));
        assertEquals(-1, Teams.best(new double[] {0, 0}, 0.001));
        assertEquals(2, Teams.best(new double[] {0.2, 0.4, 0.9, 0}, 0.001));
        assertEquals(-1, Teams.best(new double[] {0.9, 0.4, 0.9}, 0.001));
    }

    @Test
    void roundCapLeader() {
        assertEquals(-1, Teams.leader(new int[] {3, 3}));
        assertEquals(0, Teams.leader(new int[] {4, 3}));
        assertEquals(1, Teams.leader(new int[] {2, 5}));
        assertEquals(2, Teams.leader(new int[] {0, 0, 1}));
        assertEquals(-1, Teams.leader(new int[] {1, 0, 1}));
    }

    @Test
    void bestOtherScore() {
        assertEquals(1, Teams.bestOther(new int[] {2, 1}, 0));
        assertEquals(2, Teams.bestOther(new int[] {2, 1}, 1));
        assertEquals(3, Teams.bestOther(new int[] {0, 3, 1}, 0));
        assertEquals(1, Teams.bestOther(new int[] {0, 3, 1}, 1));
    }

    @Test
    void ringStartsAtSpawnAAndFacesTheMiddle() {
        // spawns 60 apart on the x axis; the middle is (30, 0)
        double[] a = Teams.ring(0, 0, 60, 0, 0, 2, 100);
        assertEquals(0, a[0], 1e-9);
        assertEquals(0, a[1], 1e-9);
        assertEquals(-90, a[2], 1e-9); // facing +x (east)
        double[] b = Teams.ring(0, 0, 60, 0, 1, 2, 100);
        assertEquals(60, b[0], 1e-9);
        assertEquals(0, b[1], 1e-9);
        assertEquals(90, Math.abs(b[2]), 1e-9); // facing -x (west)
    }

    @Test
    void ringSpreadsEveryoneEvenly() {
        int n = 7;
        double[][] pos = new double[n][];
        for (int i = 0; i < n; i++) pos[i] = Teams.ring(10, 20, 10, 80, i, n, 100);
        for (int i = 0; i < n; i++) {
            // all on the circle around (10, 50) with radius 30, facing the middle
            assertEquals(30, Math.hypot(pos[i][0] - 10, pos[i][1] - 50), 1e-9);
            double yaw = Math.toRadians(pos[i][2]);
            double lookX = -Math.sin(yaw);
            double lookZ = Math.cos(yaw);
            assertEquals(10, pos[i][0] + lookX * 30, 1e-6);
            assertEquals(50, pos[i][1] + lookZ * 30, 1e-6);
            // neighbours are equally far apart
            double[] next = pos[(i + 1) % n];
            assertEquals(2 * 30 * Math.sin(Math.PI / n), Math.hypot(next[0] - pos[i][0], next[1] - pos[i][1]), 1e-9);
        }
    }

    @Test
    void ringRadiusIsClamped() {
        double[] p = Teams.ring(0, 0, 200, 0, 0, 4, 40);
        assertEquals(40, Math.hypot(p[0] - 100, p[1]), 1e-9);
        double[] same = Teams.ring(5, 5, 5, 5, 1, 4, 40); // both spawns in one place: still a small ring
        assertTrue(Math.hypot(same[0] - 5, same[1] - 5) >= 2 - 1e-9);
    }
}
