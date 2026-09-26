package top.cheesesmp.duelcore.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import top.cheesesmp.duelcore.ui.RespawnPaths.Flight;

class RespawnPathsTest {

    /** Targets relative to the start: long, short, uphill, downhill, straight up the z axis. */
    private static final double[][] TARGETS = {{30, 0, -12}, {4, 0, 3}, {-20, 5, 18}, {12, -6, -25}, {0, 0, 40}};

    private static void endsOn(double[] d, Flight f) {
        assertArrayEquals(d, f.path()[f.ticks() - 1], 1e-9);
    }

    /** Speed (blocks per tick) of step {@code i} (from the point before, the origin for step 0). */
    private static double step(double[][] path, int i) {
        return RespawnPaths.distance(i == 0 ? new double[3] : path[i - 1], path[i]);
    }

    /** Starts and stops gently: the first and last steps are small, and much smaller than the fastest one. */
    private static void softEnds(double[][] path) {
        double max = RespawnPaths.maxStep(path);
        double first = step(path, 0);
        double last = step(path, path.length - 1);
        assertTrue(first < max * 0.15 && first < 0.3, "first step " + first + " of max " + max);
        assertTrue(last < max * 0.15 && last < 0.3, "last step " + last + " of max " + max);
    }

    /** No sudden change of speed between two ticks (a smooth curve, no jumps along the way). */
    private static void noJolts(double[][] path, double maxChange) {
        for (int i = 1; i < path.length; i++) {
            double change = Math.abs(step(path, i) - step(path, i - 1));
            assertTrue(change < maxChange, "speed change " + change + " at tick " + i);
        }
    }

    @Test
    void floatRisesAboveBothEndsAndLandsSoftlyOnTheTarget() {
        for (double[] d : TARGETS) {
            int ticks = RespawnPaths.floatTicks(Math.hypot(d[0], d[2]), 10);
            assertTrue(ticks >= 30 && ticks <= 70);
            Flight f = RespawnPaths.floatUp(d, 10, ticks);
            assertEquals(ticks, f.ticks());
            assertNull(f.facing()); // they look around freely
            endsOn(d, f);
            softEnds(f.path());
            noJolts(f.path(), 0.4);
            double top = Double.NEGATIVE_INFINITY;
            for (double[] p : f.path()) top = Math.max(top, p[1]);
            assertTrue(top >= Math.max(0, d[1]) + 9, "top " + top);
            // lifts off straight up: the first ticks barely move sideways
            double[] early = f.path()[2];
            assertTrue(Math.hypot(early[0], early[2]) < early[1] + 1e-9, "sideways " + Math.hypot(early[0], early[2]));
            assertTrue(RespawnPaths.maxStep(f.path()) < RespawnPull.MAX_SPEED);
        }
    }

    @Test
    void orbitTurnsFarEnoughRoundTheCentre() {
        assertEquals(170, RespawnPaths.orbitTurn(0, 170), 1e-4);
        assertEquals(-160, RespawnPaths.orbitTurn(0, -160), 1e-4);
        assertEquals(-270, RespawnPaths.orbitTurn(0, 90), 1e-4); // too short: the long way round
        assertEquals(330, RespawnPaths.orbitTurn(10, -20), 1e-4);
        assertEquals(-360, RespawnPaths.orbitTurn(45, 45), 1e-4);
        for (double turn : new double[] {150, 200, 360}) {
            int ticks = RespawnPaths.orbitTicks(turn);
            assertTrue(ticks >= 55 && ticks <= 80);
        }
    }

    @Test
    void orbitCirclesTheCentreAndEndsOnTheSpawnFacingItsWay() {
        double[] c = {15, 0, 0};
        for (double[] d : new double[][] {{30, 0, 0}, {15, 0, 12}, {28, 2, -5}, {1, 0, 1}}) {
            Flight f = RespawnPaths.orbit(d, c, 8, 40, 10, -90, 0);
            endsOn(d, f);
            softEnds(f.path());
            noJolts(f.path(), 0.5);
            assertTrue(RespawnPaths.maxStep(f.path()) < RespawnPull.MAX_SPEED);
            // half way it is up in the air and away from the centre
            double[] mid = f.path()[f.ticks() / 2];
            assertTrue(mid[1] > 6, "height " + mid[1]);
            assertTrue(Math.hypot(mid[0] - c[0], mid[2] - c[2]) >= RespawnPaths.ORBIT_MIN_RADIUS - 1e-6);
            // the view: starts near the player's own, looks at the centre in the middle, ends on the spawn's facing
            float[][] view = f.facing();
            assertNotNull(view);
            assertEquals(f.ticks(), view.length);
            assertTrue(Math.abs(RespawnMotion.wrap(view[0][0] - 40)) < 5, "first yaw " + view[0][0]);
            float[] look = RespawnPaths.lookAt(mid, new double[] {c[0], c[1] + 1, c[2]});
            assertEquals(0, RespawnMotion.wrap(view[f.ticks() / 2][0] - look[0]), 1e-3);
            assertEquals(-90, view[f.ticks() - 1][0], 1e-3);
            assertEquals(0, view[f.ticks() - 1][1], 1e-3);
            smoothView(view, 16);
        }
    }

    /** The view never turns more than {@code max} degrees (yaw or pitch) in one tick. */
    private static void smoothView(float[][] view, double max) {
        for (int i = 1; i < view.length; i++) {
            double yaw = Math.abs(RespawnMotion.wrap(view[i][0] - view[i - 1][0]));
            double pitch = Math.abs(view[i][1] - view[i - 1][1]);
            assertTrue(yaw < max && pitch < max, "tick " + i + ": yaw step " + yaw + ", pitch step " + pitch);
        }
    }

    @Test
    void swoopTopIsBehindTheSpawnAwayFromTheCentre() {
        double[] d = {20, 0, 0};
        double[] c = {10, 0, 0};
        assertArrayEquals(new double[] {28, 10, 0}, RespawnPaths.swoopTop(d, c, 90, 8, 10), 1e-9);
        assertArrayEquals(new double[] {20, 6, 0}, RespawnPaths.swoopTop(d, c, 90, 0, 6), 1e-9);
        // spawn on the centre: behind the way it faces (yaw 0 faces +z, so behind is -z)
        assertArrayEquals(new double[] {20, 10, -8}, RespawnPaths.swoopTop(d, new double[] {20, 0, 0}, 0, 8, 10), 1e-9);
    }

    @Test
    void swoopRisesPausesAndDivesOntoTheSpawn() {
        double[] c = {15, 0, 5};
        for (double[] d : TARGETS) {
            double[] top = RespawnPaths.swoopTop(d, c, 30, 8, 10);
            Flight f = RespawnPaths.swoop(d, c, top, -120, 20, 30, 0);
            int[] legs = RespawnPaths.swoopTicks(d, top);
            assertEquals(legs[0] + RespawnPaths.SWOOP_HOVER_TICKS + legs[1], f.ticks());
            assertTrue(f.ticks() <= 80, "ticks " + f.ticks());
            endsOn(d, f);
            softEnds(f.path());
            noJolts(f.path(), 0.5);
            assertTrue(RespawnPaths.maxStep(f.path()) < RespawnPull.MAX_SPEED);
            // at the top for the pause, standing still
            for (int k = legs[0] - 1; k < legs[0] + RespawnPaths.SWOOP_HOVER_TICKS; k++) {
                assertArrayEquals(top, f.path()[k], 1e-9);
            }
            // the dive stays above the spawn height until the very end (no dipping under it)
            for (int k = legs[0]; k < f.ticks(); k++) assertTrue(f.path()[k][1] >= d[1] - 1e-9);
            float[][] view = f.facing();
            assertNotNull(view);
            assertTrue(Math.abs(RespawnMotion.wrap(view[0][0] + 120)) < 5, "first yaw " + view[0][0]);
            assertEquals(30, view[f.ticks() - 1][0], 1e-3);
            assertEquals(0, view[f.ticks() - 1][1], 1e-3);
            smoothView(view, 20);
        }
    }

    @Test
    void lookAtUsesMinecraftAngles() {
        double[] feet = {0, 0, 0};
        assertEquals(0, RespawnPaths.lookAt(feet, new double[] {0, RespawnPaths.EYE, 5})[0], 1e-4); // south
        assertEquals(90, RespawnPaths.lookAt(feet, new double[] {-5, RespawnPaths.EYE, 0})[0], 1e-4); // west
        assertEquals(-90, RespawnPaths.lookAt(feet, new double[] {5, RespawnPaths.EYE, 0})[0], 1e-4); // east
        assertEquals(0, RespawnPaths.lookAt(feet, new double[] {0, RespawnPaths.EYE, 5})[1], 1e-4); // level
        assertEquals(45, RespawnPaths.lookAt(feet, new double[] {0, RespawnPaths.EYE - 5, 5})[1], 1e-4); // down
        assertEquals(90, RespawnPaths.lookAt(feet, new double[] {0, -3, 0})[1], 1e-4); // straight down
    }

    @Test
    void blendTurnsTheShortWay() {
        float[] half = RespawnPaths.blend(170, 0, new float[] {-170, 40}, 0.5);
        assertEquals(-180, half[0], 1e-3);
        assertEquals(20, half[1], 1e-4);
        assertArrayEquals(new float[] {-170, 40}, RespawnPaths.blend(170, 0, new float[] {-170, 40}, 1), 1e-3f);
    }

    @Test
    void bezierHitsItsEnds() {
        double[] a = {0, 0, 0};
        double[] b = {1, 5, 2};
        double[] c = {4, 5, -2};
        double[] e = {6, 1, 0};
        assertArrayEquals(a, RespawnPaths.bezier(a, b, c, e, 0), 1e-12);
        assertArrayEquals(e, RespawnPaths.bezier(a, b, c, e, 1), 1e-12);
    }
}
