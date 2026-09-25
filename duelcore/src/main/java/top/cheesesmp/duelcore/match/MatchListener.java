package top.cheesesmp.duelcore.match;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import top.cheesesmp.duelcore.DuelCorePlugin;
import top.cheesesmp.duelcore.arena.ArenaInstance;
import top.cheesesmp.duelcore.kit.KitRules;

/** Enforces kit rules and match flow; feeds combat stats. */
public final class MatchListener implements Listener {

    public static final String BYPASS_COMMANDS = "duelcore.bypass.commands";

    private static final Set<EntityDamageEvent.DamageCause> HIT_CAUSES = EnumSet.of(
        EntityDamageEvent.DamageCause.ENTITY_ATTACK, EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK,
        EntityDamageEvent.DamageCause.PROJECTILE);

    private final DuelCorePlugin plugin;
    /** Who placed/ignited explosive entities, for kill credit and stats. Bounded. */
    private final Map<UUID, UUID> owners = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, UUID> eldest) {
            return size() > 4096;
        }
    };

    public MatchListener(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    private @Nullable Match match(Player player) {
        return plugin.matches().match(player.getUniqueId());
    }

    private @Nullable Participant participant(Player player) {
        Match m = match(player);
        return m == null ? null : m.participant(player.getUniqueId());
    }

    // ------------------------------------------------------------------ death & damage

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Match m = match(victim);
        if (m == null) return;
        event.setCancelled(true);
        AttributeInstance max = victim.getAttribute(Attribute.MAX_HEALTH);
        event.setReviveHealth(max == null ? 20 : max.getValue());
        event.deathMessage(null);
        event.getDrops().clear();
        plugin.matches().onDeath(victim, victim.getKiller());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Match m = match(victim);
        if (m == null) return;
        Participant p = m.participant(victim.getUniqueId());
        if (p == null || !p.alive || m.state() != Match.State.FIGHTING) {
            event.setCancelled(true);
            return;
        }
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.FALL && !m.kit().rules().fallDamage()) event.setCancelled(true);
        if (cause == EntityDamageEvent.DamageCause.VOID) event.setCancelled(true); // handled by the bounds check
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = attacker(event.getDamager());
        Player victim = event.getEntity() instanceof Player v ? v : null;
        if (attacker == null) return;
        Match am = match(attacker);
        if (victim == null) {
            // participants can't hurt spectators or entities of other matches; dead players can't hit anything
            Participant ap = am == null ? null : am.participant(attacker.getUniqueId());
            if (am != null && (ap == null || !ap.alive || am.state() != Match.State.FIGHTING)) event.setCancelled(true);
            return;
        }
        Match vm = match(victim);
        if (am == null && vm == null) return;
        if (am != vm) {
            event.setCancelled(true);
            return;
        }
        Participant ap = am.participant(attacker.getUniqueId());
        Participant vp = am.participant(victim.getUniqueId());
        if (ap == null || vp == null || !ap.alive || (ap != vp && ap.team() == vp.team())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageStats(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attacker(event.getDamager());
        if (attacker == null || attacker == victim) return;
        Match m = match(victim);
        if (m == null || m.state() != Match.State.FIGHTING) return;
        Participant vp = m.participant(victim.getUniqueId());
        Participant ap = m.participant(attacker.getUniqueId());
        if (vp == null || ap == null || ap.team() == vp.team()) return;
        double dealt = Math.min(event.getFinalDamage(), victim.getHealth() + victim.getAbsorptionAmount());
        ap.damageDealt += dealt;
        vp.damageTaken += dealt;
        if (HIT_CAUSES.contains(event.getCause())) {
            ap.hits++;
            vp.hitsReceived++;
            ap.combo++;
            ap.bestCombo = Math.max(ap.bestCombo, ap.combo);
            vp.combo = 0;
        }
        vp.lastDamager = ap.uuid();
        vp.lastDamagedAt = System.currentTimeMillis();
    }

    /** Resolves the player responsible for damage (melee, projectile, crystal, cart, creeper, TNT). */
    private @Nullable Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) return p;
        UUID owner = owners.get(damager.getUniqueId());
        return owner == null ? null : plugin.getServer().getPlayer(owner);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Match m = match(player);
        if (m == null || m.kit().rules().naturalRegen()) return;
        EntityRegainHealthEvent.RegainReason r = event.getRegainReason();
        if (r == EntityRegainHealthEvent.RegainReason.SATIATED || r == EntityRegainHealthEvent.RegainReason.REGEN) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Match m = match(player);
        if (m == null) return;
        if (!m.kit().rules().hunger() || m.state() != Match.State.FIGHTING) {
            event.setCancelled(true);
            player.setFoodLevel(20);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player player) {
            Match m = match(player);
            if (m != null && !m.kit().rules().totems()) event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ movement & teleports

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;
        Player player = event.getPlayer();
        Match m = match(player);
        if (m == null || !plugin.matches().isFrozen(m) || m.arena() == null) return;
        if (plugin.respawnPull().pulling(player.getUniqueId())) return; // being thrown back to the spawn
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() != m.arena().world()) return;
        if (from.getX() != to.getX() || from.getZ() != to.getZ() || to.getY() > from.getY()) {
            Location fixed = from.clone();
            fixed.setYaw(to.getYaw());
            fixed.setPitch(to.getPitch());
            event.setTo(fixed);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Match m = match(player);
        if (m == null) return;
        ArenaInstance arena = m.arena();
        switch (event.getCause()) {
            case ENDER_PEARL, CONSUMABLE_EFFECT -> {
                if (arena == null || !arena.containsXZ(event.getTo().getX(), event.getTo().getZ(), 0.5)
                    || event.getTo().getWorld() != arena.world()) {
                    event.setCancelled(true);
                }
            }
            case SPECTATE -> event.setCancelled(true);
            default -> {
                if (arena != null && event.getTo().getWorld() != arena.world() && !m.isOver()) event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        plugin.matches().forfeit(event.getPlayer(), true);
    }

    // ------------------------------------------------------------------ items & projectiles

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        Match m = match(player);
        if (m == null) return;
        Participant p = m.participant(player.getUniqueId());
        if (p == null || !p.alive || m.state() != Match.State.FIGHTING) {
            event.setCancelled(true);
            return;
        }
        if (event.getEntity() instanceof EnderPearl) {
            KitRules rules = m.kit().rules();
            if (!rules.enderPearls()) {
                event.setCancelled(true);
                return;
            }
            int cooldown = rules.pearlCooldownTicks();
            if (cooldown > 0) {
                player.getScheduler().run(plugin, t -> player.setCooldown(Material.ENDER_PEARL, cooldown), null);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getHitEntity() instanceof Player hit)) return;
        if (plugin.spectate().spectating(hit.getUniqueId()) != null) {
            event.setCancelled(true);
            return;
        }
        Match m = match(hit);
        Participant p = m == null ? null : m.participant(hit.getUniqueId());
        if (p != null && !p.alive) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Match m = match(player);
        if (m == null) return;
        Participant p = m.participant(player.getUniqueId());
        if (p == null || !p.alive || m.state() != Match.State.FIGHTING) {
            if (event.getAction() != Action.PHYSICAL) event.setCancelled(true);
            return;
        }
        KitRules rules = m.kit().rules();
        ItemStack item = event.getItem();
        if (item != null && item.getType().name().endsWith("_SPAWN_EGG") && !rules.spawnEggs()) {
            event.setCancelled(true);
            return;
        }
        Block block = event.getClickedBlock();
        if (block != null && block.getType() == Material.RESPAWN_ANCHOR && event.getAction() == Action.RIGHT_CLICK_BLOCK
            && !rules.anchors()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Creeper creeper) {
            Match m = match(event.getPlayer());
            if (m != null) owners.put(creeper.getUniqueId(), event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        Match m = match(player);
        if (m == null) return;
        KitRules rules = m.kit().rules();
        Entity entity = event.getEntity();
        boolean allowed;
        if (entity instanceof EnderCrystal) allowed = rules.crystals();
        else if (entity instanceof Minecart) allowed = rules.minecarts();
        else allowed = false;
        if (!allowed || m.state() != Match.State.FIGHTING || m.arena() == null
            || !m.arena().contains(entity.getLocation())) {
            event.setCancelled(true);
            return;
        }
        owners.put(entity.getUniqueId(), player.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Match m = match(event.getPlayer());
        if (m != null && (!m.kit().rules().itemDrops() || m.state() != Match.State.FIGHTING)) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ blocks

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Match m = match(player);
        if (m == null) return;
        Block block = event.getBlockPlaced();
        ArenaInstance arena = m.arena();
        Participant p = m.participant(player.getUniqueId());
        if (arena == null || p == null || !p.alive || m.state() != Match.State.FIGHTING
            || !m.kit().rules().canPlace(block.getType())
            || !arena.containsBlock(block.getX(), block.getY(), block.getZ()) || block.getY() >= arena.buildLimitY()) {
            event.setCancelled(true);
            return;
        }
        arena.markPlaced(block.getX(), block.getY(), block.getZ());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Match m = match(player);
        if (m == null) return;
        Block block = event.getBlock();
        ArenaInstance arena = m.arena();
        Participant p = m.participant(player.getUniqueId());
        KitRules rules = m.kit().rules();
        boolean ok = arena != null && p != null && p.alive && m.state() == Match.State.FIGHTING
            && arena.containsBlock(block.getX(), block.getY(), block.getZ())
            && switch (rules.breakMode()) {
                case NONE -> false;
                case PLACED -> arena.wasPlaced(block.getX(), block.getY(), block.getZ());
                case ALL -> true;
            };
        if (!ok) {
            event.setCancelled(true);
            return;
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
        if (rules.build() && player.getGameMode() == org.bukkit.GameMode.SURVIVAL) {
            // building kits keep what they mine (straight into the inventory, no item entities to clean up)
            for (ItemStack drop : block.getDrops(player.getInventory().getItemInMainHand(), player)) {
                player.getInventory().addItem(drop);
            }
        }
        arena.forgetPlaced(block.getX(), block.getY(), block.getZ());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        Match m = match(player);
        if (m == null) return;
        Block block = event.getBlock();
        ArenaInstance arena = m.arena();
        Material fluid = event.getBucket() == Material.LAVA_BUCKET ? Material.LAVA : Material.WATER;
        KitRules rules = m.kit().rules();
        if (arena == null || !rules.buckets() || m.state() != Match.State.FIGHTING
            || (!rules.allowedBlocks().isEmpty() && !rules.allowedBlocks().contains(fluid))
            || !arena.containsBlock(block.getX(), block.getY(), block.getZ()) || block.getY() >= arena.buildLimitY()) {
            event.setCancelled(true);
            return;
        }
        arena.markPlaced(block.getX(), block.getY(), block.getZ());
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (event.getBlock().getWorld() != plugin.arenas().world()) return;
        Block to = event.getToBlock();
        ArenaInstance arena = plugin.arenas().instanceAt(to.getX(), to.getZ());
        if (arena == null || !arena.containsBlock(to.getX(), to.getY(), to.getZ())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.getEntity().getWorld() != plugin.arenas().world()) return;
        event.setYield(0);
        event.blockList().removeIf(b -> {
            ArenaInstance arena = plugin.arenas().instanceAt(b.getX(), b.getZ());
            return arena == null || !arena.containsBlock(b.getX(), b.getY(), b.getZ());
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (event.getBlock().getWorld() != plugin.arenas().world()) return;
        event.setYield(0);
        event.blockList().removeIf(b -> {
            ArenaInstance arena = plugin.arenas().instanceAt(b.getX(), b.getZ());
            return arena == null || !arena.containsBlock(b.getX(), b.getY(), b.getZ());
        });
    }

    // ------------------------------------------------------------------ commands

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (match(player) == null && plugin.spectate().spectating(player.getUniqueId()) == null) return;
        if (player.hasPermission(BYPASS_COMMANDS)) return;
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (label.equals("leave") || label.equals("spectate") || plugin.settings().allowedCommands.contains(label)) return;
        if (label.equals("pc") || label.equals("party") || label.equals("p")) return; // party chat and menu work anywhere
        event.setCancelled(true);
        plugin.messages().send(player, "match.command-blocked");
    }
}
