package top.cheesesmp.duelcore.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RiseGeometryTest {

    @Test
    void centresTheTwoByTwoOnTheNearestCorner() {
        RiseGeometry g = RiseGeometry.around(10.5, 64.0, -3.5, 3);
        assertEquals(11, g.cornerX());
        assertEquals(-3, g.cornerZ());
        Set<String> cols = new HashSet<>();
        for (int[] c : g.columns()) cols.add(c[0] + "," + c[1]);
        assertEquals(Set.of("10,-4", "11,-4", "10,-3", "11,-3"), cols);
        // the player's hitbox (0.6 wide) around the corner fits inside the 2×2
        for (double dx : new double[] {-0.3, 0.3}) {
            for (double dz : new double[] {-0.3, 0.3}) {
                assertTrue(g.contains((int) Math.floor(g.cornerX() + dx), (int) Math.floor(g.cornerZ() + dz)));
            }
        }
        assertFalse(g.contains(12, -3));
        assertFalse(g.contains(10, -2));
    }

    @Test
    void layersAndStartHeight() {
        RiseGeometry g = RiseGeometry.around(0.5, 64.0, 0.5, 3);
        assertEquals(63, g.topY());
        assertEquals(61, g.bottomY());
        assertEquals(60, g.floorY());
        assertEquals(61.0, g.startY(64.0), 1e-9); // standing on the floor block, three blocks down
        // a spawn on a slab: the slab is the top block; the start never ends inside the floor block
        RiseGeometry slab = RiseGeometry.around(0.5, 64.5, 0.5, 2);
        assertEquals(64, slab.topY());
        assertEquals(63.0, slab.startY(64.5), 1e-9); // not 62.5: that is inside the floor block (62)
        assertTrue(slab.startY(64.5) >= slab.floorY() + 1);
    }

    @Test
    void outerFacesAreInsetAndTheStackSinksByTheLift() {
        RiseGeometry g = RiseGeometry.around(0.5, 64.0, 0.5, 3);
        float e = RiseGeometry.INSET;
        // min/min column: pulled in on the min x and min z faces
        assertArrayEquals(new float[] {e, -3 - e, e}, g.translation(0, 0, 3), 1e-6f);
        // max/max column: the max faces come in through the scale only
        assertArrayEquals(new float[] {0f, -e, 0f}, g.translation(1, 1, 0), 1e-6f);
        assertArrayEquals(new float[] {1 - e, 1f, 1 - e}, g.scale(), 1e-6f);
        // every outer face ends strictly inside the column, the inner faces meet exactly
        float[] s = g.scale();
        float minX = g.translation(0, 0, 0)[0];
        float maxX = 1 + g.translation(1, 1, 0)[0] + s[0];
        assertTrue(minX > 0 && maxX < 2);
        assertEquals(1f, g.translation(0, 0, 0)[0] + s[0], 1e-6f);
    }

    @Test
    void usableDepthStopsAtUncarvableLayersAndNeedsAFloor() {
        // all carvable, all solid: full depth
        assertEquals(3, RiseGeometry.usableDepth(63, 3, y -> true, y -> true));
        // bedrock at 61: only layers 63 and 62 can go, and 61 is solid under them
        assertEquals(2, RiseGeometry.usableDepth(63, 3, y -> y != 61, y -> true));
        // a cave at 60: the 3-deep hole has no floor, 2 deep has one
        assertEquals(2, RiseGeometry.usableDepth(63, 3, y -> true, y -> y != 60));
        // nothing works
        assertEquals(0, RiseGeometry.usableDepth(63, 3, y -> y != 63, y -> true));
        assertEquals(0, RiseGeometry.usableDepth(63, 3, y -> true, y -> false));
    }

    @Test
    void heightIsLinearAndEndsExactly() {
        assertEquals(61.0, RiseGeometry.heightAt(61, 64, 0, 50), 1e-9);
        assertEquals(62.5, RiseGeometry.heightAt(61, 64, 25, 50), 1e-9);
        assertEquals(64.0, RiseGeometry.heightAt(61, 64, 50, 50), 1e-9);
        assertEquals(64.0, RiseGeometry.heightAt(61, 64, 70, 50), 1e-9);
        double total = 0;
        for (int k = 0; k < 50; k++) total += RiseGeometry.heightAt(61, 64, k + 1, 50) - RiseGeometry.heightAt(61, 64, k, 50);
        assertEquals(3.0, total, 1e-9);
    }
}
