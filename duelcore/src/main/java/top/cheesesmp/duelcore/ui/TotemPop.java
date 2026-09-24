package top.cheesesmp.duelcore.ui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.EntityEffect;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * "Match found" animation: the client plays the totem pop with whatever held item carries the
 * {@code death_protection} component. We briefly put a totem re-skinned with the kit's item model into the
 * off-hand (tick 0), send {@link EntityEffect#PROTECTED_FROM_DEATH} to that player only (tick 1) and restore the
 * off-hand afterwards (tick 3). No death happens, no item is consumed, other players see nothing.
 */
public final class TotemPop {

    private TotemPop() {
    }

    public static void play(Plugin plugin, Player player, Material icon) {
        ItemStack previous = player.getInventory().getItemInOffHand();
        ItemStack pop = ItemStack.of(Material.TOTEM_OF_UNDYING);
        pop.setData(DataComponentTypes.ITEM_MODEL, icon.getKey());
        // setItem(40) sends the slot packet immediately (setItemInOffHand only syncs at the end of the tick)
        player.getInventory().setItem(40, pop);
        player.getScheduler().runDelayed(plugin, task -> {
            if (player.isOnline()) player.sendEntityEffect(EntityEffect.PROTECTED_FROM_DEATH, player);
        }, null, 1L);
        player.getScheduler().runDelayed(plugin, task -> {
            if (!player.isOnline()) return;
            ItemStack now = player.getInventory().getItemInOffHand();
            if (now.getType() == Material.TOTEM_OF_UNDYING && now.hasData(DataComponentTypes.ITEM_MODEL)) {
                player.getInventory().setItemInOffHand(previous.getType().isAir() ? null : previous);
            }
        }, null, 3L);
    }
}
