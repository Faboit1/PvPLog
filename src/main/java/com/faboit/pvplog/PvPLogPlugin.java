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

        getServer().getPluginManager().registerEvents(new CombatListener(this), this);

        CombatCommand command = new CombatCommand(this);
        register("combat", command);
        register("pvplog", command);

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
            combatManager.shutdown();
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
