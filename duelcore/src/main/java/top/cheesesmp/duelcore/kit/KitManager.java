package top.cheesesmp.duelcore.kit;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.ui.Icons;

/** Loads kits/*.yml, hot-reloadable, and applies kits to players. */
public final class KitManager {

    public static final List<String> DEFAULT_KITS = List.of(
        "sword", "spear", "mace", "shield", "pot", "endgame", "earlygame", "lategame",
        "nethpot", "diasmp", "smp", "creeper", "cart", "bow", "crystal");

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final Logger logger;
    private volatile Map<String, Kit> kits = Map.of();

    public KitManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /** (Re)loads all kits. Returns problems found (also logged). */
    public List<String> load() {
        File dir = new File(plugin.getDataFolder(), "kits");
        if (!dir.isDirectory() || isEmpty(dir)) {
            for (String id : DEFAULT_KITS) {
                if (!new File(dir, id + ".yml").exists()) plugin.saveResource("kits/" + id + ".yml", false);
            }
        }
        List<String> errors = new ArrayList<>();
        Map<String, Kit> loaded = new LinkedHashMap<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml") && !name.startsWith("_"));
        if (files != null) {
            for (File file : files) {
                String id = file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT);
                if (!id.matches("[a-z0-9_]{1,32}")) {
                    errors.add(file.getName() + ": kit ids may only use a-z, 0-9 and _ (max 32)");
                    continue;
                }
                YamlConfiguration yml = new YamlConfiguration();
                try {
                    yml.load(file);
                } catch (Exception e) {
                    errors.add(file.getName() + ": " + e.getMessage());
                    continue;
                }
                Kit kit = parse(id, yml, errors);
                if (kit != null) loaded.put(id, kit);
            }
        }
        List<Kit> sorted = new ArrayList<>(loaded.values());
        sorted.sort(Comparator.comparing(Kit::category).thenComparingInt(Kit::order).thenComparing(Kit::id));
        Map<String, Kit> ordered = new LinkedHashMap<>();
        for (Kit k : sorted) ordered.put(k.id(), k);
        this.kits = java.util.Collections.unmodifiableMap(ordered);
        for (String e : errors) logger.warning("Kit problem: " + e);
        logger.info("Loaded " + ordered.size() + " kits");
        return errors;
    }

    private static boolean isEmpty(File dir) {
        String[] list = dir.list((d, n) -> n.endsWith(".yml"));
        return list == null || list.length == 0;
    }

    private @Nullable Kit parse(String id, ConfigurationSection y, List<String> errors) {
        String where = "kits/" + id + ".yml";
        String nameRaw = y.getString("display-name", id);
        Component name = MM.deserialize(nameRaw).decoration(TextDecoration.ITALIC, false);
        String iconName = y.getString("icon", "iron_sword");
        Material icon = Material.matchMaterial(iconName);
        if (icon == null || !icon.isItem()) {
            errors.add(where + ": unknown icon '" + iconName + "'");
            icon = Material.IRON_SWORD;
        }
        String spriteSpec = y.getString("sprite", "items:item/" + icon.getKey().getKey());
        Kit.Category category = "extra".equalsIgnoreCase(y.getString("category")) ? Kit.Category.EXTRA : Kit.Category.MAIN;

        ConfigurationSection r = y.getConfigurationSection("rules");
        if (r == null) r = new YamlConfiguration();
        Set<Material> allowed = EnumSet.noneOf(Material.class);
        for (String b : r.getStringList("allowed-blocks")) {
            Material m = Material.matchMaterial(b);
            if (m == null) errors.add(where + ": unknown block '" + b + "' in allowed-blocks");
            else if (m == Material.WATER) allowed.add(Material.WATER);
            else if (m == Material.LAVA) allowed.add(Material.LAVA);
            else allowed.add(m);
        }
        KitRules rules = new KitRules(
            r.getBoolean("natural-regen", true),
            r.getBoolean("hunger", false),
            r.getBoolean("fall-damage", true),
            r.getBoolean("build", false),
            KitRules.BreakMode.parse(r.getString("break", "all")),
            allowed,
            r.getBoolean("ender-pearls", false),
            Math.max(0, (int) Math.round(r.getDouble("pearl-cooldown", 1) * 20)),
            r.getBoolean("totems", false),
            r.getBoolean("crystals", false),
            r.getBoolean("anchors", false),
            r.getBoolean("cobwebs", false),
            r.getBoolean("buckets", false),
            r.getBoolean("spawn-eggs", false),
            r.getBoolean("minecarts", false),
            r.getBoolean("item-drops", false),
            Math.clamp(r.getDouble("max-health", 20), 1, 1024));

        ItemStack[] contents = new ItemStack[36];
        ConfigurationSection loadout = y.getConfigurationSection("loadout");
        ItemStack helmet = null;
        ItemStack chest = null;
        ItemStack legs = null;
        ItemStack boots = null;
        ItemStack offhand = null;
        if (loadout != null) {
            ConfigurationSection armor = loadout.getConfigurationSection("armor");
            if (armor != null) {
                helmet = item(armor.get("helmet"), errors, where + " armor.helmet");
                chest = item(armor.get("chestplate"), errors, where + " armor.chestplate");
                legs = item(armor.get("leggings"), errors, where + " armor.leggings");
                boots = item(armor.get("boots"), errors, where + " armor.boots");
            }
            ConfigurationSection items = loadout.getConfigurationSection("items");
            if (items != null) {
                for (String slotKey : items.getKeys(false)) {
                    int slot;
                    try {
                        slot = Integer.parseInt(slotKey);
                    } catch (NumberFormatException e) {
                        errors.add(where + ": slot '" + slotKey + "' is not a number (0-35)");
                        continue;
                    }
                    if (slot < 0 || slot > 35) {
                        errors.add(where + ": slot " + slot + " out of range (0-35)");
                        continue;
                    }
                    Object raw = items.get(slotKey);
                    if (raw instanceof ConfigurationSection section) raw = section.getValues(false);
                    contents[slot] = ItemParser.parse(raw, errors, where + " slot " + slot);
                }
            }
            offhand = item(loadout.get("offhand"), errors, where + " offhand");
            Object fill = loadout.get("fill");
            if (fill instanceof ConfigurationSection f) fill = f.getValues(false);
            if (fill instanceof Map<?, ?> fillMap && fillMap.get("item") != null) {
                Object fillItem = fillMap.get("item");
                if (fillItem instanceof ConfigurationSection fs) fillItem = fs.getValues(false);
                ItemStack filler = ItemParser.parse(fillItem, errors, where + " fill");
                if (filler != null) {
                    for (int i = 0; i < contents.length; i++) if (contents[i] == null) contents[i] = filler.clone();
                }
            }
        }
        List<PotionEffect> effects = new ArrayList<>();
        for (Map<?, ?> e : y.getMapList("effects")) {
            PotionEffect effect = ItemParser.effect(e, errors, where + " effects");
            if (effect != null) effects.add(effect);
        }
        List<String> tags = y.getStringList("arena-tags").stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        return new Kit(id, nameRaw, name, y.getString("description", ""), icon, Icons.parse(spriteSpec), spriteSpec,
            category, y.getInt("order", 100), y.getBoolean("enabled", true), y.getBoolean("ranked", true),
            Math.clamp(y.getInt("first-to", 3), 1, 15), Math.max(0, y.getInt("round-time-limit", 180)),
            tags.isEmpty() ? List.of("open", "boxed") : tags, rules, contents, helmet, chest, legs, boots, offhand, effects);
    }

    private static @Nullable ItemStack item(@Nullable Object raw, List<String> errors, String where) {
        if (raw == null) return null;
        if (raw instanceof ConfigurationSection section) raw = section.getValues(false);
        return ItemParser.parse(raw, errors, where);
    }

    public @Nullable Kit get(String id) {
        return kits.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<Kit> all() {
        return kits.values();
    }

    public List<Kit> enabled() {
        return kits.values().stream().filter(Kit::enabled).toList();
    }

    public List<Kit> enabled(Kit.Category category) {
        return kits.values().stream().filter(k -> k.enabled() && k.category() == category).toList();
    }

    public Set<String> ids() {
        return new HashSet<>(kits.keySet());
    }

    public int size() {
        return kits.size();
    }

    /** Resets the player to a clean survival state and gives the kit. */
    public static void apply(Player player, Kit kit) {
        resetState(player, kit.rules().maxHealth());
        PlayerInventory inv = player.getInventory();
        inv.setContents(kit.contents());
        inv.setHelmet(kit.helmet());
        inv.setChestplate(kit.chestplate());
        inv.setLeggings(kit.leggings());
        inv.setBoots(kit.boots());
        inv.setItemInOffHand(kit.offhand());
        inv.setHeldItemSlot(0);
        for (PotionEffect effect : kit.effects()) player.addPotionEffect(effect);
        player.updateInventory();
    }

    /** Clears everything that could carry over between rounds or back to the hub. */
    public static void resetState(Player player, double maxHealth) {
        player.closeInventory();
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.setItemOnCursor(null);
        player.clearActivePotionEffects();
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.1f);
        player.setGliding(false);
        player.setInvulnerable(false);
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) max.setBaseValue(maxHealth);
        player.setHealth(Math.min(maxHealth, max == null ? 20 : max.getValue()));
        player.setAbsorptionAmount(0);
        player.setFoodLevel(20);
        player.setSaturation(5f);
        player.setExhaustion(0f);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0f);
        player.setRemainingAir(player.getMaximumAir());
        player.setArrowsInBody(0);
        player.setLevel(0);
        player.setExp(0f);
        player.setVelocity(new org.bukkit.util.Vector());
        player.setCooldown(Material.ENDER_PEARL, 0);
        player.setCooldown(Material.CHORUS_FRUIT, 0);
        player.setCooldown(Material.WIND_CHARGE, 0);
        player.setCooldown(Material.SHIELD, 0);
    }

    /** Writes the player's current inventory into kits/&lt;id&gt;.yml loadout (keeps the other settings). */
    public void saveFromInventory(String id, Player player) throws IOException, org.bukkit.configuration.InvalidConfigurationException {
        File file = new File(new File(plugin.getDataFolder(), "kits"), id + ".yml");
        YamlConfiguration yml = new YamlConfiguration();
        if (file.exists()) yml.load(file);
        else {
            yml.set("display-name", id);
            yml.set("icon", "iron_sword");
            yml.set("category", "extra");
            yml.set("first-to", 3);
        }
        PlayerInventory inv = player.getInventory();
        yml.set("loadout", null);
        yml.set("loadout.armor.helmet", spec(inv.getHelmet()));
        yml.set("loadout.armor.chestplate", spec(inv.getChestplate()));
        yml.set("loadout.armor.leggings", spec(inv.getLeggings()));
        yml.set("loadout.armor.boots", spec(inv.getBoots()));
        for (int slot = 0; slot < 36; slot++) {
            Object s = spec(inv.getItem(slot));
            if (s != null) yml.set("loadout.items." + slot, s);
        }
        yml.set("loadout.offhand", spec(inv.getItemInOffHand()));
        yml.save(file);
    }

    private static @Nullable Object spec(@Nullable ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        String item = ItemParser.toSpec(stack);
        if (stack.getAmount() == 1) return item;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("item", item);
        map.put("amount", stack.getAmount());
        return map;
    }
}
