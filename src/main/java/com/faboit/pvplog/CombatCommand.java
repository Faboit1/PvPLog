package com.faboit.pvplog;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CombatCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("reload", "tag", "untag", "status");

    private final PvPLogPlugin plugin;

    CombatCommand(PvPLogPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Settings s = plugin.settings();
        CombatManager combat = plugin.combatManager();

        if (command.getName().equalsIgnoreCase("combat")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use this. Try /pvplog status <player>.");
                return true;
            }
            if (combat.isTagged(player)) {
                send(sender, s.message("status-in-combat", "time", CombatManager.seconds(combat.remainingMillis(player))));
            } else {
                send(sender, s.message("status-not-in-combat"));
            }
            return true;
        }

        if (args.length == 0) {
            send(sender, s.message("usage"));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            plugin.reload();
            send(sender, plugin.settings().message("reloaded"));
            return true;
        }
        if (!SUBCOMMANDS.contains(sub)) {
            send(sender, s.message("usage"));
            return true;
        }

        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            send(sender, s.message("usage"));
            return true;
        }
        if (target == null) {
            send(sender, s.message("player-not-found"));
            return true;
        }

        switch (sub) {
            case "tag" -> {
                combat.tag(target, null, true);
                send(sender, s.message("admin-tagged", "player", target.getName()));
            }
            case "untag" -> {
                combat.untag(target, true);
                send(sender, s.message("admin-untagged", "player", target.getName()));
            }
            default -> {
                if (combat.isTagged(target)) {
                    send(sender, s.message("admin-status-in-combat", "player", target.getName(),
                            "time", CombatManager.seconds(combat.remainingMillis(target))));
                } else {
                    send(sender, s.message("admin-status-not-in-combat", "player", target.getName()));
                }
            }
        }
        return true;
    }

    private static void send(CommandSender sender, net.kyori.adventure.text.Component message) {
        if (message != null) sender.sendMessage(message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("combat")) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(sub);
            }
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
        }
        return out;
    }
}
