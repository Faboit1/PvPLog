package com.faboit.pvplog;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class PvPLogPlugin extends JavaPlugin {

    private Settings settings;
    private CombatManager combatManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(this);
        combatManager = new CombatManager(this);
        combatManager.start();

        getServer().getPluginManager().registerEvents(new CombatListener(this), this);

        CombatCommand command = new CombatCommand(this);
        register("combat", command);
        register("pvplog", command);

        getLogger().info("PvPLog enabled - combat duration " + settings.combatDurationMillis() / 1000 + "s");
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

    public CombatManager combatManager() {
        return combatManager;
    }
}
