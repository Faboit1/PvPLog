package com.faboit.pvplog;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class PvPLogPlugin extends JavaPlugin {

    private Settings settings;
    private CombatManager combatManager;
    private FriendHook friendHook;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(this);
        combatManager = new CombatManager(this);
        friendHook = new FriendHook(this);

        CombatListener listener = new CombatListener(this);
        getServer().getPluginManager().registerEvents(listener, this);
        hookDialogClicks(listener);
        combatManager.restoreTags(); // tags of players still online after a plugin reload

        CombatCommand command = new CombatCommand(this);
        register("combat", command);
        register("pvplog", command);
        register("showcombatbar", command);

        getLogger().info("PvPLog enabled - combat duration " + settings.combatDurationMillis() / 1000 + "s"
                + (isFolia() ? " (Folia mode)" : ""));
        // FriendSystem may enable after us (softdepend only orders when both exist), so check once the server is up.
        getServer().getGlobalRegionScheduler().run(this, task -> getLogger().info(friendHook.isAvailable()
                ? "Hooked into FriendSystem - friends will never be combat tagged."
                : "FriendSystem not found - friend protection disabled."));
    }

    @Override
    public void onDisable() {
        if (combatManager != null) {
            combatManager.saveTags(); // so a /plugman reload doesn't clear everyone's tag
            combatManager.shutdown();
        }
    }

    /**
     * Dialog button presses (Paper 1.21.6+ PlayerCustomClickEvent) count as menu actions for the combat teleport
     * check. Hooked by reflection so the plugin still loads on servers without dialogs.
     */
    @SuppressWarnings("unchecked")
    private void hookDialogClicks(CombatListener listener) {
        Class<? extends org.bukkit.event.Event> type;
        try {
            type = (Class<? extends org.bukkit.event.Event>) Class.forName("io.papermc.paper.event.player.PlayerCustomClickEvent");
        } catch (ClassNotFoundException | ClassCastException e) {
            return;
        }
        getServer().getPluginManager().registerEvent(type, new org.bukkit.event.Listener() { }, org.bukkit.event.EventPriority.MONITOR,
                (l, event) -> listener.menuAction(clicker(event)), this, false);
    }

    /** The player behind a custom click event (its connection's player), or null. */
    private static org.bukkit.entity.Player clicker(Object event) {
        try {
            Object connection = event.getClass().getMethod("getCommonConnection").invoke(event);
            Object player = connection.getClass().getMethod("getPlayer").invoke(connection);
            return player instanceof org.bukkit.entity.Player p ? p : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null; // a configuration-phase connection has no player
        }
    }

    public void reload() {
        reloadConfig();
        settings = new Settings(this);
    }

    private void register(String name, CombatCommand command) {
        PluginCommand pluginCommand = getCommand(name);
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }
    }

    public Settings settings() {
        return settings;
    }

    FriendHook friendHook() {
        return friendHook;
    }

    static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public CombatManager combatManager() {
        return combatManager;
    }
}
