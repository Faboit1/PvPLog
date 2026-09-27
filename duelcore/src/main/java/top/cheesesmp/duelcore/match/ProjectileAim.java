package top.cheesesmp.duelcore.match;

import org.bukkit.util.Vector;

/** Takes the random spread out of a thrown projectile's velocity (pure math, see MatchListener#onAccurateThrow). */
final class ProjectileAim {

    private ProjectileAim() {
    }

    /**
     * Vanilla launch velocity = aim direction × speed + random spread + the thrower's movement (its vertical part
     * only while airborne). Returns the same speed straight along {@code aim}, plus the same movement.
     */
    static Vector straighten(Vector launched, Vector throwerVelocity, boolean onGround, Vector aim) {
        Vector carried = new Vector(throwerVelocity.getX(), onGround ? 0 : throwerVelocity.getY(), throwerVelocity.getZ());
        Vector own = launched.clone().subtract(carried);
        double speed = own.length();
        if (speed < 1.0E-6 || aim.lengthSquared() < 1.0E-9) return launched;
        return aim.clone().normalize().multiply(speed).add(carried);
    }
}
