package top.cheesesmp.duelcore.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThrowMathTest {

    @Test
    void settleWaitGrowsWithPing() {
        assertEquals(ThrowMath.BASE_SETTLE_TICKS, ThrowMath.settleTicks(0));
        assertEquals(ThrowMath.BASE_SETTLE_TICKS + 1, ThrowMath.settleTicks(30));
        assertEquals(ThrowMath.BASE_SETTLE_TICKS + 12, ThrowMath.settleTicks(600));
        assertEquals(ThrowMath.MAX_SETTLE_TICKS, ThrowMath.settleTicks(5000));
        assertEquals(ThrowMath.BASE_SETTLE_TICKS, ThrowMath.settleTicks(-1));
    }

    @Test
    void stallWaitCoversTheRoundTrip() {
        assertEquals(10, ThrowMath.stallTicks(10, 0));
        assertEquals(14, ThrowMath.stallTicks(10, 190));
        assertEquals(22, ThrowMath.stallTicks(10, 600));
    }

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
}
