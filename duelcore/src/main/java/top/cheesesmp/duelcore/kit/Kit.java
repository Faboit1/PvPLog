package top.cheesesmp.duelcore.kit;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.jspecify.annotations.Nullable;

/**
 * An immutable kit definition. A running match keeps its own Kit instance, so /duelcore reload never changes a
 * fight in progress.
 */
public final class Kit {

    /** Queue menu tab of a kit. Old kit files used "main" (now weapons) and "extra" (now vanilla). */
    public enum Category {
        WEAPONS, VANILLA, SKILLS;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /** Parses a category id (also the old "main"/"extra"); null when unknown. */
        public static @Nullable Category parse(@Nullable String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "weapons", "main" -> WEAPONS;
                case "vanilla", "extra" -> VANILLA;
                case "skills" -> SKILLS;
                default -> null;
            };
        }
    }

    private final String id;
    private final String displayNameRaw;
    private final Component displayName;
    private final String description;
    private final Material icon;
    private final Component sprite;
    private final String spriteSpec;
    private final Category category;
    private final int order;
    private final boolean enabled;
    private final boolean ranked;
    private final int firstTo;
    private final int roundTimeLimitSeconds;
    private final List<String> arenaTags;
    private final KitRules rules;
    private final @Nullable ItemStack[] contents;
    private final @Nullable ItemStack helmet;
    private final @Nullable ItemStack chestplate;
    private final @Nullable ItemStack leggings;
    private final @Nullable ItemStack boots;
    private final @Nullable ItemStack offhand;
    private final List<PotionEffect> effects;

    public Kit(String id, String displayNameRaw, Component displayName, String description, Material icon,
               Component sprite, String spriteSpec, Category category, int order, boolean enabled, boolean ranked,
               int firstTo, int roundTimeLimitSeconds, List<String> arenaTags, KitRules rules,
               @Nullable ItemStack[] contents, @Nullable ItemStack helmet, @Nullable ItemStack chestplate,
               @Nullable ItemStack leggings, @Nullable ItemStack boots, @Nullable ItemStack offhand,
               List<PotionEffect> effects) {
        this.id = id;
        this.displayNameRaw = displayNameRaw;
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
        this.sprite = sprite;
        this.spriteSpec = spriteSpec;
        this.category = category;
        this.order = order;
        this.enabled = enabled;
        this.ranked = ranked;
        this.firstTo = firstTo;
        this.roundTimeLimitSeconds = roundTimeLimitSeconds;
        this.arenaTags = List.copyOf(arenaTags);
        this.rules = rules;
        this.contents = contents;
        this.helmet = helmet;
        this.chestplate = chestplate;
        this.leggings = leggings;
        this.boots = boots;
        this.offhand = offhand;
        this.effects = List.copyOf(effects);
    }

    public String id() {
        return id;
    }

    /** Raw MiniMessage display name as written in the kit file. */
    public String displayNameRaw() {
        return displayNameRaw;
    }

    public Component displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public Material icon() {
        return icon;
    }

    /** Inline atlas sprite for this kit. */
    public Component sprite() {
        return sprite;
    }

    public String spriteSpec() {
        return spriteSpec;
    }

    public Category category() {
        return category;
    }

    public int order() {
        return order;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean ranked() {
        return ranked;
    }

    public int firstTo() {
        return firstTo;
    }

    public int roundTimeLimitSeconds() {
        return roundTimeLimitSeconds;
    }

    public List<String> arenaTags() {
        return arenaTags;
    }

    public KitRules rules() {
        return rules;
    }

    /** Defensive copies: callers may freely modify the returned stacks. */
    public @Nullable ItemStack[] contents() {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) copy[i] = contents[i] == null ? null : contents[i].clone();
        return copy;
    }

    public @Nullable ItemStack helmet() {
        return helmet == null ? null : helmet.clone();
    }

    public @Nullable ItemStack chestplate() {
        return chestplate == null ? null : chestplate.clone();
    }

    public @Nullable ItemStack leggings() {
        return leggings == null ? null : leggings.clone();
    }

    public @Nullable ItemStack boots() {
        return boots == null ? null : boots.clone();
    }

    public @Nullable ItemStack offhand() {
        return offhand == null ? null : offhand.clone();
    }

    public List<PotionEffect> effects() {
        return effects;
    }
}
