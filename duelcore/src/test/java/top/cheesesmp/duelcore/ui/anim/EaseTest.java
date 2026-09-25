package top.cheesesmp.duelcore.ui.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

class EaseTest {

    @Test
    void curvesStartAtZeroEndAtOneAndClamp() {
        for (DoubleUnaryOperator f : List.of(Ease.LINEAR, Ease.OUT_QUAD, Ease.OUT_CUBIC, Ease.IN_CUBIC, Ease.IN_OUT_CUBIC,
            Ease.OUT_BACK, Ease.IN_OUT_SINE, Ease.OUT_EXPO)) {
            assertEquals(0, f.applyAsDouble(0), 1e-9);
            assertEquals(1, f.applyAsDouble(1), 1e-9);
            assertEquals(0, f.applyAsDouble(-3), 1e-9);
            assertEquals(1, f.applyAsDouble(7), 1e-9);
        }
    }

    @Test
    void shapes() {
        assertTrue(Ease.easeOutCubic(0.5) > 0.8, "out-cubic is fast first");
        assertTrue(Ease.easeInCubic(0.5) < 0.2, "in-cubic is slow first");
        assertEquals(0.5, Ease.easeInOutSine(0.5), 1e-9);
        assertEquals(0.5, Ease.easeInOutCubic(0.5), 1e-9);
        double max = 0;
        for (int i = 0; i <= 100; i++) max = Math.max(max, Ease.easeOutBack(i / 100.0));
        assertTrue(max > 1.05 && max < 1.15, "out-back overshoots a little: " + max);
        for (int i = 1; i <= 100; i++) {
            assertTrue(Ease.easeOutCubic(i / 100.0) >= Ease.easeOutCubic((i - 1) / 100.0), "monotonic");
        }
    }

    @Test
    void progressAndCounting() {
        assertEquals(0, Ease.progress(0, 20));
        assertEquals(0.5, Ease.progress(10, 20));
        assertEquals(1, Ease.progress(40, 20));
        assertEquals(1, Ease.progress(0, 0));
        assertEquals(0, Ease.count(0, 18, 0, 30, Ease.OUT_CUBIC));
        assertEquals(18, Ease.count(0, 18, 30, 30, Ease.OUT_CUBIC));
        assertEquals(1480, Ease.count(1480, 1498, 0, 30, Ease.LINEAR));
        assertEquals(1489, Ease.count(1480, 1498, 15, 30, Ease.LINEAR));
        // counting down
        assertEquals(1510, Ease.count(1510, 1498, 0, 30, Ease.OUT_CUBIC));
        assertEquals(1498, Ease.count(1510, 1498, 30, 30, Ease.OUT_CUBIC));
        // an overshooting curve never counts past the target
        for (int t = 0; t <= 30; t++) {
            assertTrue(Ease.count(0, 20, t, 30, Ease.OUT_BACK) <= 20);
            assertTrue(Ease.count(20, 0, t, 30, Ease.OUT_BACK) >= 0);
        }
        assertEquals(15, Ease.lerp(10, 20, 0.5));
    }
}
