package top.cheesesmp.duelcore.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThrowMathTest {

    @Test
    void flightTimeFollowsGravity() {
        // 22.6 blocks of bulge (a 63-block throw) takes about 48 ticks, like a real fall from that height and back
        assertEquals(48, ThrowMath.ticks(22.6));
        assertEquals(14, ThrowMath.ticks(0.5));
        assertEquals(80, ThrowMath.ticks(500));
    }

    @Test
    void arcStartsAndEndsOnTheEndsAndPeaksInTheMiddle() {
        assertEquals(10, ThrowMath.y(10, 14, 20, 0), 1e-9);
        assertEquals(14, ThrowMath.y(10, 14, 20, 1), 1e-9);
        assertEquals(32, ThrowMath.y(10, 14, 20, 0.5), 1e-9);
        assertTrue(ThrowMath.y(10, 14, 20, 0.25) < ThrowMath.y(10, 14, 20, 0.5));
    }

    @Test
    void launchLandsExactlyOnTheTarget() {
        for (boolean ground : new boolean[] {true, false}) {
            for (double[] d : new double[][] {{30, 2, -12}, {-4, -3, 51}, {0, 0, 20}, {45, 6, 30}}) {
                for (int ticks : new int[] {14, 32, 60}) {
                    double[] v = ThrowMath.launch(d[0], d[1], d[2], ticks, ground);
                    double[][] path = ThrowMath.path(v, ticks, ground);
                    double[] end = path[ticks - 1];
                    for (int k = 0; k < 3; k++) org.junit.jupiter.api.Assertions.assertEquals(d[k], end[k], 1e-9);
                }
            }
        }
    }

    @Test
    void launchArcsAboveBothEnds() {
        double[] v = ThrowMath.launch(40, 0, 0, ThrowMath.ticks(12), true);
        double top = 0;
        for (double[] p : ThrowMath.path(v, ThrowMath.ticks(12), true)) top = Math.max(top, p[1]);
        org.junit.jupiter.api.Assertions.assertTrue(top > 8, "apex " + top);
        org.junit.jupiter.api.Assertions.assertTrue(v[1] > 0);
    }

    @Test
    void softenKeepsTheArcButEasesTheLaunchAndLanding() {
        int ticks = ThrowMath.ticks(15);
        double[] v = ThrowMath.launch(35, 3, -10, ticks, false);
        double[][] path = ThrowMath.path(v, ticks, false);
        double[][] soft = ThrowMath.soften(path, 6, 8);
        assertEquals(ticks + 7, soft.length);
        assertArrayEquals(path[ticks - 1], soft[soft.length - 1], 1e-9); // same landing spot
        // every softened point lies on the arc: between two consecutive ticks of the original
        double realFirst = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        double first = Math.sqrt(soft[0][0] * soft[0][0] + soft[0][1] * soft[0][1] + soft[0][2] * soft[0][2]);
        assertTrue(first < realFirst * 0.2, "launch step " + first + " vs " + realFirst);
        double realLast = dist(path[ticks - 2], path[ticks - 1]);
        double last = dist(soft[soft.length - 2], soft[soft.length - 1]);
        assertTrue(last < realLast * 0.1, "landing step " + last + " vs " + realLast);
        // the speed never jumps: no step differs from the one before by more than 30% of the real flight's fastest step
        double max = 0;
        for (int i = 1; i < ticks; i++) max = Math.max(max, dist(path[i - 1], path[i]));
        for (int i = 1; i < soft.length; i++) {
            double a = i == 1 ? first : dist(soft[i - 2], soft[i - 1]);
            double b = dist(soft[i - 1], soft[i]);
            assertTrue(Math.abs(b - a) < Math.max(max, realFirst) * 0.3, "tick " + i + ": " + a + " -> " + b);
        }
    }

    @Test
    void softenTimeWarpIsMonotonicAndCoversTheWholeFlight() {
        int n = 30;
        int total = n + 6;
        double prev = 0;
        for (int t = 1; t <= total; t++) {
            double p = ThrowMath.warp(t, total, 4, 8);
            assertTrue(p > prev, "t " + t);
            assertTrue(p - prev <= 1 + 1e-9, "never faster than the real flight");
            prev = p;
        }
        assertEquals(n, ThrowMath.warp(total, total, 4, 8), 1e-9);
        assertEquals(0, ThrowMath.warp(0, total, 4, 8), 1e-9);
        // no ramps: the real flight
        assertEquals(7, ThrowMath.warp(7, n, 0, 0), 1e-9);
        // a very short flight still works (ramps shortened)
        double[][] tiny = ThrowMath.soften(new double[][] {{1, 0, 0}, {2, 0, 0}, {3, 0, 0}}, 4, 8);
        assertArrayEquals(new double[] {3, 0, 0}, tiny[tiny.length - 1], 1e-9);
    }

    private static double dist(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }
}
