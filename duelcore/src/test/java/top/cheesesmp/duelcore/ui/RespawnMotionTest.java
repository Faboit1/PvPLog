package top.cheesesmp.duelcore.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import top.cheesesmp.duelcore.ui.RespawnMotion.Pool;
import top.cheesesmp.duelcore.ui.RespawnMotion.Style;

class RespawnMotionTest {

    @Test
    void stylesParseByName() {
        assertEquals(Style.THROW, Style.parse("throw"));
        assertEquals(Style.LOOK_DOWN, Style.parse(" Look_Down "));
        assertEquals(Style.SPIN, Style.parse("SPIN"));
        assertEquals(Style.FLOAT, Style.parse("float"));
        assertEquals(Style.ORBIT, Style.parse("Orbit"));
        assertEquals(Style.SWOOP, Style.parse("swoop"));
        assertEquals("throw, look-down, spin, float, orbit, swoop", Style.names());
        assertNull(Style.parse("cartwheel"));
    }

    @Test
    void poolReadsWeightsAndSkipsBadEntries() {
        Pool pool = Pool.parse(List.of("throw", "spin 3", "look-down 0", "cartwheel", "spin x", "throw 1"));
        assertEquals(List.of(Style.THROW, Style.SPIN), pool.styles());
        assertEquals(2, pool.weight(Style.THROW));
        assertEquals(3, pool.weight(Style.SPIN));
        assertEquals(0, pool.weight(Style.LOOK_DOWN));
        assertEquals(2, pool.problems().size());
    }

    @Test
    void poolPicksByWeight() {
        Pool pool = Pool.parse(List.of("throw 1", "look-down 2", "spin 1"));
        assertEquals(Style.THROW, pool.pick(0));
        assertEquals(Style.THROW, pool.pick(0.24));
        assertEquals(Style.LOOK_DOWN, pool.pick(0.25));
        assertEquals(Style.LOOK_DOWN, pool.pick(0.74));
        assertEquals(Style.SPIN, pool.pick(0.75));
        assertEquals(Style.SPIN, pool.pick(0.999999));
        assertEquals(Style.SPIN, pool.pick(1));
    }

    @Test
    void emptyPoolThrows() {
        assertEquals(List.of(Style.THROW), Pool.parse(List.of()).styles());
        assertEquals(List.of(Style.THROW), Pool.parse(List.of("spin 0", "nope")).styles());
        assertEquals(Style.THROW, Pool.parse(List.of()).pick(0.5));
    }

    @Test
    void wrapKeepsAnglesInOneTurn() {
        assertEquals(0, RespawnMotion.wrap(360), 1e-4);
        assertEquals(-90, RespawnMotion.wrap(270), 1e-4);
        assertEquals(170, RespawnMotion.wrap(-190), 1e-4);
        assertEquals(-180, RespawnMotion.wrap(180), 1e-4);
        assertEquals(10, RespawnMotion.wrap(730), 1e-4);
    }

    @Test
    void lookDownGoesSmoothlyToStraightDownAndBack() {
        int n = RespawnMotion.LOOK_TICKS;
        assertEquals(10, RespawnMotion.lookDown(10, 0, n), 1e-4);
        assertEquals(RespawnMotion.DOWN, RespawnMotion.lookDown(10, n, n), 1e-4);
        assertEquals(RespawnMotion.DOWN, RespawnMotion.lookUp(0, 0, n), 1e-4);
        assertEquals(0, RespawnMotion.lookUp(0, n, n), 1e-4);
        // monotonic, and eased: the first and last steps are smaller than the middle one
        float prev = RespawnMotion.lookDown(0, 0, n);
        for (int k = 1; k <= n; k++) {
            float p = RespawnMotion.lookDown(0, k, n);
            assertTrue(p > prev, "tick " + k);
            prev = p;
        }
        float first = RespawnMotion.lookDown(0, 1, n) - RespawnMotion.lookDown(0, 0, n);
        float middle = RespawnMotion.lookDown(0, n / 2 + 1, n) - RespawnMotion.lookDown(0, n / 2, n);
        float last = RespawnMotion.lookDown(0, n, n) - RespawnMotion.lookDown(0, n - 1, n);
        assertTrue(first < middle && last < middle);
    }

    @Test
    void spinTurnsOnceAndEndsFacingTheSpawn() {
        int n = RespawnMotion.SPIN_TICKS;
        for (float[] c : new float[][] {{0, 0}, {30, -120}, {-170, 170}, {179, -179}, {90, 90}}) {
            float from = c[0];
            float to = c[1];
            float total = RespawnMotion.spinTotal(from, to);
            assertTrue(total >= 180 && total <= 540, "total " + total);
            assertEquals(0, RespawnMotion.wrap(from + total - to), 1e-3);
            assertEquals(from, RespawnMotion.spinYaw(from, to, 0, n), 1e-3);
            assertEquals(0, RespawnMotion.wrap(RespawnMotion.spinYaw(from, to, n, n) - to), 1e-3);
            // every step turns the same way and by less than a quarter turn
            float prev = RespawnMotion.spinYaw(from, to, 0, n);
            for (int k = 1; k <= n; k++) {
                float yaw = RespawnMotion.spinYaw(from, to, k, n);
                float step = RespawnMotion.wrap(yaw - prev);
                assertTrue(step > 0 && step < 90, "tick " + k + " step " + step);
                prev = yaw;
            }
        }
        assertEquals(360, RespawnMotion.spinTotal(45, 45), 1e-4);
    }

    @Test
    void spinTeleportsHalfWayThroughTheTurn() {
        int n = RespawnMotion.SPIN_TICKS;
        int at = RespawnMotion.spinTeleportTick(n);
        assertEquals(n / 2, at);
        // half the turn is done at the teleport
        assertEquals(RespawnMotion.wrap(180), RespawnMotion.wrap(RespawnMotion.spinYaw(0, 0, at, n)), 1e-3);
        assertEquals(5, RespawnMotion.spinPitch(10, 0, at, n), 1e-4);
    }

    @Test
    void landTurnEasesIntoTheSpawnFacingTheShortWay() {
        int n = RespawnMotion.LAND_TURN_TICKS;
        assertArrayEquals(new float[] {170, -20}, RespawnMotion.landTurn(170, -20, -150, 0, 0, n), 1e-4f);
        assertArrayEquals(new float[] {-150, 0}, RespawnMotion.landTurn(170, -20, -150, 0, n, n), 1e-4f);
        // through 180, not back round through 0
        float[] prev = RespawnMotion.landTurn(170, -20, -150, 0, 0, n);
        for (int k = 1; k <= n; k++) {
            float[] v = RespawnMotion.landTurn(170, -20, -150, 0, k, n);
            float step = RespawnMotion.wrap(v[0] - prev[0]);
            assertTrue(step > 0 && step < 10, "tick " + k + " step " + step);
            prev = v;
        }
    }
}
