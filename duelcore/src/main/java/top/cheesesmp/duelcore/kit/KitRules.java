package top.cheesesmp.duelcore.kit;

import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;

/**
 * What a kit allows during a match. Enforced by {@link top.cheesesmp.duelcore.match.MatchListener}.
 *
 * @param breakMode         which blocks may be broken when building is on
 * @param allowedBlocks     placeable blocks (empty = anything the kit carries)
 * @param pearlCooldownTicks ender pearl cooldown applied after each throw (0 = vanilla)
 */
public record KitRules(
    boolean naturalRegen,
    boolean hunger,
    boolean fallDamage,
    boolean build,
    BreakMode breakMode,
    Set<Material> allowedBlocks,
    boolean enderPearls,
    int pearlCooldownTicks,
    boolean totems,
    boolean crystals,
    boolean anchors,
    boolean cobwebs,
    boolean buckets,
    boolean spawnEggs,
    boolean minecarts,
    boolean itemDrops,
    double maxHealth
) {

    public enum BreakMode {
        /** Nothing can be broken. */
        NONE,
        /** Only blocks placed during this match. */
        PLACED,
        /** Any block inside the arena. */
        ALL;

        public static BreakMode parse(String raw) {
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return PLACED;
            }
        }
    }

    public boolean canPlace(Material material) {
        if (!build) return false;
        if (material == Material.COBWEB && !cobwebs) return false;
        if (material == Material.RESPAWN_ANCHOR && !anchors) return false;
        return allowedBlocks.isEmpty() || allowedBlocks.contains(material);
    }
}
