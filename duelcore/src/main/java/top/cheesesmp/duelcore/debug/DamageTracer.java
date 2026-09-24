package top.cheesesmp.duelcore.debug;

import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.EventExecutor;
import top.cheesesmp.duelcore.DuelCorePlugin;

/**
 * /duelcore debug trace: for a few seconds, logs at which listener priority player-vs-player damage gets
 * cancelled. Helps find conflicts with protection plugins (WorldGuard regions, PvP toggles, ...).
 */
public final class DamageTracer implements Listener {

    private final DuelCorePlugin plugin;

    public DamageTracer(DuelCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void start(int seconds) {
        for (EventPriority priority : EventPriority.values()) {
            EventExecutor executor = (listener, event) -> {
                if (!(event instanceof EntityDamageByEntityEvent e) || !(e.getEntity() instanceof Player victim)) return;
                plugin.getLogger().info("[trace] " + priority + " damage " + e.getDamager().getName() + " -> " + victim.getName()
                    + " cause=" + e.getCause() + " cancelled=" + e.isCancelled() + " final=" + String.format("%.2f", e.getFinalDamage()));
            };
            plugin.getServer().getPluginManager().registerEvent(EntityDamageByEntityEvent.class, this, priority, executor, plugin, false);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> HandlerList.unregisterAll(this), seconds * 20L);
    }
}
