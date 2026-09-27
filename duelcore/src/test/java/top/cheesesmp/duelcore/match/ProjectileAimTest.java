package top.cheesesmp.duelcore.match;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class ProjectileAimTest {

    @Test
    void spreadIsRemovedSpeedAndMomentumKept() {
        Vector aim = new Vector(1, 0.2, 0).normalize();
        Vector moving = new Vector(0.1, 0.3, 0);
        Vector spread = new Vector(0.02, -0.015, 0.03);
        Vector launched = aim.clone().multiply(1.5).add(spread).add(moving); // airborne thrower
        Vector out = ProjectileAim.straighten(launched, moving, false, aim);
        Vector own = out.clone().subtract(moving);
        assertEquals(launched.clone().subtract(moving).length(), own.length(), 1e-9);
        assertEquals(0, own.clone().normalize().subtract(aim).length(), 1e-9);
    }

    @Test
    void onTheGroundOnlyHorizontalMomentumCounts() {
        Vector aim = new Vector(0, 0, 1);
        Vector moving = new Vector(0.2, -0.08, 0);
        Vector launched = new Vector(0.2 + 0.01, 0.005, 1.5);
        Vector out = ProjectileAim.straighten(launched, moving, true, aim);
        assertEquals(0.2, out.getX(), 1e-9);
        assertEquals(0, out.getY(), 1e-9);
    }
}
