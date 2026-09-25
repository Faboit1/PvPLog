package top.cheesesmp.duelcore.kit;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.jspecify.annotations.Nullable;

/**
 * Parses kit items. Accepted forms:
 * <ul>
 *   <li>{@code "diamond_sword"}: a plain material</li>
 *   <li>{@code "diamond_sword[enchantments={sharpness:3}]"}: vanilla /give syntax, every component supported</li>
 *   <li>a map: {@code item, amount, enchants {name: level}, potion, name, lore, unbreakable}</li>
 * </ul>
 */
public final class ItemParser {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private ItemParser() {
    }

    public static ItemStack parse(Object spec, List<String> errors, String where) {
        try {
            if (spec instanceof String s) return fromString(s, 1);
            Map<?, ?> map = asMap(spec);
            if (map != null) return fromMap(map, errors, where);
            errors.add(where + ": unsupported item value " + spec);
        } catch (IllegalArgumentException e) {
            errors.add(where + ": " + e.getMessage());
        }
        return null;
    }

    private static ItemStack fromString(String raw, int amount) {
        String s = raw.trim();
        ItemStack stack;
        if (s.contains("[")) {
            stack = Bukkit.getItemFactory().createItemStack(s.contains(":") && s.indexOf(':') < s.indexOf('[') ? s : "minecraft:" + s);
        } else {
            Material material = Material.matchMaterial(s);
            if (material == null || !material.isItem()) throw new IllegalArgumentException("unknown item '" + s + "'");
            stack = ItemStack.of(material);
        }
        stack.setAmount(Math.clamp(amount, 1, Math.max(1, stack.getMaxStackSize())));
        return stack;
    }

    private static ItemStack fromMap(Map<?, ?> map, List<String> errors, String where) {
        Object item = map.get("item");
        if (!(item instanceof String itemName)) throw new IllegalArgumentException("missing 'item'");
        int amount = map.get("amount") instanceof Number n ? n.intValue() : 1;
        ItemStack stack = fromString(itemName, amount);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        Map<?, ?> enchants = asMap(map.get("enchants"));
        if (enchants != null) {
            for (Map.Entry<?, ?> e : enchants.entrySet()) {
                Enchantment enchantment = enchantment(String.valueOf(e.getKey()));
                if (enchantment == null) {
                    errors.add(where + ": unknown enchantment '" + e.getKey() + "'");
                    continue;
                }
                int level = e.getValue() instanceof Number n ? n.intValue() : 1;
                meta.addEnchant(enchantment, level, true);
            }
        }
        if (map.get("potion") instanceof String potion) {
            if (meta instanceof PotionMeta potionMeta) {
                PotionType type = Registry.POTION.get(NamespacedKey.minecraft(potion.toLowerCase(Locale.ROOT)));
                if (type == null) errors.add(where + ": unknown potion '" + potion + "'");
                else potionMeta.setBasePotionType(type);
            } else {
                errors.add(where + ": 'potion' needs a potion, splash_potion, lingering_potion or tipped_arrow");
            }
        }
        if (map.get("name") instanceof String name) {
            meta.itemName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
        }
        if (map.get("lore") instanceof List<?> lore) {
            List<Component> lines = new ArrayList<>();
            for (Object line : lore) lines.add(MM.deserialize(String.valueOf(line)).decoration(TextDecoration.ITALIC, false));
            meta.lore(lines);
        }
        if (Boolean.TRUE.equals(map.get("unbreakable"))) meta.setUnbreakable(true);
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * A map for a YAML value: Bukkit hands nested maps over as {@link ConfigurationSection}s (also inside the values of
     * {@code getValues(false)}), so both forms are accepted. Null for anything else.
     */
    static @Nullable Map<?, ?> asMap(@Nullable Object value) {
        if (value instanceof Map<?, ?> m) return m;
        if (value instanceof ConfigurationSection section) return section.getValues(false);
        return null;
    }

    public static @Nullable Enchantment enchantment(String name) {
        Key key = key(name);
        return key == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key);
    }

    public static @Nullable PotionEffectType effect(String name) {
        Key key = key(name);
        return key == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT).get(key);
    }

    /** {type, amplifier (0-based), duration seconds (-1 = infinite)}. */
    public static @Nullable PotionEffect effect(Map<?, ?> map, List<String> errors, String where) {
        if (!(map.get("type") instanceof String typeName)) {
            errors.add(where + ": effect without 'type'");
            return null;
        }
        PotionEffectType type = effect(typeName);
        if (type == null) {
            errors.add(where + ": unknown effect '" + typeName + "'");
            return null;
        }
        int amplifier = map.get("amplifier") instanceof Number n ? n.intValue() : 0;
        int seconds = map.get("duration") instanceof Number n ? n.intValue() : -1;
        int ticks = seconds < 0 ? PotionEffect.INFINITE_DURATION : seconds * 20;
        return new PotionEffect(type, ticks, amplifier, false, false, true);
    }

    /** Vanilla-syntax string for an item (used by /duelcore kit save). */
    public static String toSpec(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        String components = meta == null ? "[]" : meta.getAsComponentString();
        String type = stack.getType().getKey().getKey();
        return "[]".equals(components) ? type : type + components;
    }

    private static @Nullable Key key(String name) {
        try {
            String n = name.trim().toLowerCase(Locale.ROOT);
            return n.contains(":") ? Key.key(n) : Key.key(Key.MINECRAFT_NAMESPACE, n);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
