package top.cheesesmp.duelcore.ui;

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
}
