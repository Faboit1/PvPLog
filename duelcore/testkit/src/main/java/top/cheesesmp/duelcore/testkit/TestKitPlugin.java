package top.cheesesmp.duelcore.testkit;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Test-only plugin. Spawns node bot scripts from {serverRoot}/duelcore-test/bots and guards logins
 * while the server runs in offline mode: only loopback connections with a "dcbot" name prefix may join.
 */
public final class TestKitPlugin extends JavaPlugin implements Listener {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private File root;

    @Override
    public void onEnable() {
        root = new File(Bukkit.getWorldContainer().getAbsoluteFile(), "duelcore-test");
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("TestKit ready, root=" + root + " onlineMode=" + Bukkit.getOnlineMode());
    }

    @Override
    public void onDisable() {
        processes.values().forEach(Process::destroyForcibly);
        processes.clear();
    }

    /*
     * Testers allowed in while the server runs in offline test mode: {root}/allow.txt, one "name [ip]" per line,
     * re-read on every login. Offline mode can't verify names, so each name is locked to the IP it first joins
     * from (trust on first use); /tester add clears the lock. Names on the vanilla whitelist are admitted the same way.
     */
    private File allowFile() {
        return new File(root, "allow.txt");
    }

    private synchronized Map<String, String> readAllow() {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        File file = allowFile();
        if (!file.isFile()) return map;
        try {
            for (String line : java.nio.file.Files.readAllLines(file.toPath())) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 0 || parts[0].isEmpty() || parts[0].startsWith("#")) continue;
                map.put(parts[0], parts.length > 1 ? parts[1] : "");
            }
        } catch (IOException e) {
            getLogger().warning("[testkit] cannot read allow.txt: " + e.getMessage());
        }
        return map;
    }

    private synchronized void writeAllow(Map<String, String> map) {
        List<String> lines = new ArrayList<>();
        map.forEach((name, ip) -> lines.add(ip.isEmpty() ? name : name + " " + ip));
        try {
            root.mkdirs();
            java.nio.file.Files.write(allowFile().toPath(), lines);
        } catch (IOException e) {
            getLogger().warning("[testkit] cannot write allow.txt: " + e.getMessage());
        }
    }

    private static @org.jetbrains.annotations.Nullable String key(Map<String, String> map, String name) {
        for (String k : map.keySet()) if (k.equalsIgnoreCase(name)) return k;
        return null;
    }

    /** null = allowed; otherwise the kick reason. Pins the IP on first use. */
    private synchronized @org.jetbrains.annotations.Nullable String checkTester(String name, String ip) {
        Map<String, String> map = readAllow();
        String k = key(map, name);
        if (k == null) {
            boolean whitelisted = Bukkit.getWhitelistedPlayers().stream().anyMatch(o -> name.equalsIgnoreCase(o.getName()));
            if (!whitelisted) return "Server is in maintenance for testing. Ask an op to /tester add you.";
            k = name;
            map.put(k, "");
        }
        String pinned = map.get(k);
        if (pinned.isEmpty()) {
            map.put(k, ip);
            writeAllow(map);
            getLogger().info("[testkit] tester " + k + " locked to " + ip);
            return null;
        }
        if (!pinned.equals(ip)) {
            getLogger().warning("[testkit] refused " + name + " from " + ip + " (locked to another IP)");
            return "This name is locked to another IP while the server is in testing mode. Ask an op to /tester add you again.";
        }
        return null;
    }

    /**
     * The test-mode login guard (offline mode only): loopback "dcbot*" connections are the test bots; everyone else
     * must be a tester (allow.txt or the vanilla whitelist) joining from the IP their name is locked to.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (Bukkit.getOnlineMode()) return;
        boolean loopback = event.getAddress().isLoopbackAddress();
        boolean botName = event.getName().toLowerCase().startsWith("dcbot");
        if (loopback && botName) return;
        String reason;
        try {
            reason = checkTester(event.getName(), event.getAddress().getHostAddress());
        } catch (RuntimeException e) {
            getLogger().warning("[testkit] login check failed for " + event.getName() + ": " + e);
            reason = "Server is in maintenance for testing. Try again later."; // fail closed
        }
        if (reason == null) {
            getLogger().info("[testkit] tester login: " + event.getName() + " from " + event.getAddress().getHostAddress());
        } else {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Component.text(reason));
        }
    }

    /**
     * Test bots (loopback + dcbot name, see onPreLogin) are exempt from the anticheats: mineflayer movement is not
     * vanilla-exact, and the bots are there to test DuelCore, not to be flagged. Real players are never exempted.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        org.bukkit.entity.Player p = event.getPlayer();
        java.net.InetSocketAddress address = p.getAddress();
        if (Bukkit.getOnlineMode() || address == null || !address.getAddress().isLoopbackAddress()
            || !p.getName().toLowerCase().startsWith("dcbot")) return;
        org.bukkit.permissions.PermissionAttachment a = p.addAttachment(this);
        for (String perm : EXEMPT) a.setPermission(perm, true);
    }

    /** /tester add|remove|list (ops): manage who may join while the server is in testing mode. */
    private boolean tester(CommandSender sender, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        Map<String, String> map = readAllow();
        switch (sub) {
            case "add" -> {
                if (args.length < 2 || !args[1].matches("[A-Za-z0-9_]{1,16}")) {
                    sender.sendMessage("Usage: /tester add <player>");
                    return true;
                }
                String k = key(map, args[1]);
                map.remove(k == null ? args[1] : k);
                map.put(args[1], ""); // (re)lock to the IP they join from next
                writeAllow(map);
                sender.sendMessage(args[1] + " can join now (locked to the IP they join from next).");
                getLogger().info("[testkit] " + sender.getName() + " added tester " + args[1]);
            }
            case "remove" -> {
                String k = args.length < 2 ? null : key(map, args[1]);
                if (k == null) {
                    sender.sendMessage("Not a tester: " + (args.length < 2 ? "?" : args[1]));
                    return true;
                }
                map.remove(k);
                writeAllow(map);
                sender.sendMessage("Removed tester " + k + ".");
                getLogger().info("[testkit] " + sender.getName() + " removed tester " + k);
            }
            case "list" -> sender.sendMessage("Testers (" + map.size() + "): " + String.join(", ",
                map.entrySet().stream().map(e -> e.getKey() + (e.getValue().isEmpty() ? " (not joined yet)" : "")).toList()));
            default -> sender.sendMessage("/tester add <player> | remove <player> | list");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("tester")) return List.of();
        if (args.length == 1) return List.of("add", "remove", "list").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) return new ArrayList<>(readAllow().keySet());
        if (args.length == 2) return Bukkit.getOnlinePlayers().stream().map(org.bukkit.entity.Player::getName).toList();
        return List.of();
    }

    private static final List<String> EXEMPT = List.of("grim.exempt", "TotemGuard.Bypass", "sentry.bypass");

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("tester")) return tester(sender, args);
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("console only");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("/dctest run <id> <script> [args..] | stop <id|all> | ps");
            return true;
        }
        switch (args[0]) {
            case "run" -> run(sender, args);
            case "stop" -> {
                String id = args.length > 1 ? args[1] : "all";
                processes.entrySet().removeIf(e -> {
                    if (id.equals("all") || e.getKey().equals(id)) {
                        e.getValue().descendants().forEach(ProcessHandle::destroyForcibly);
                        e.getValue().destroyForcibly();
                        getLogger().info("[testkit] stopped " + e.getKey());
                        return true;
                    }
                    return false;
                });
            }
            case "ps" -> processes.forEach((id, p) ->
                getLogger().info("[testkit] " + id + " alive=" + p.isAlive() + (p.isAlive() ? "" : " exit=" + p.exitValue())));
            default -> sender.sendMessage("unknown");
        }
        return true;
    }

    private void run(CommandSender sender, String[] args) {
        if (args.length < 3 || !SAFE.matcher(args[1]).matches() || !SAFE.matcher(args[2]).matches()) {
            sender.sendMessage("usage: /dctest run <id> <script.js> [args..]");
            return;
        }
        String id = args[1];
        Process old = processes.remove(id);
        if (old != null) old.destroyForcibly();
        File bots = new File(root, "bots");
        File node = new File(root, "node-v22.22.2-linux-x64/bin/node");
        File logs = new File(root, "logs");
        logs.mkdirs();
        List<String> cmd = new ArrayList<>();
        cmd.add(node.getAbsolutePath());
        cmd.add(args[2]);
        for (String a : Arrays.copyOfRange(args, 3, args.length)) {
            if (!SAFE.matcher(a).matches() && !a.matches("[A-Za-z0-9_.,:=\\-]{1,200}")) {
                sender.sendMessage("unsafe arg " + a);
                return;
            }
            cmd.add(a);
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
        File log = new File(logs, id + "-" + stamp + ".log");
        try {
            Process p = new ProcessBuilder(cmd).directory(bots).redirectErrorStream(true)
                .redirectOutput(log).start();
            processes.put(id, p);
            getLogger().info("[testkit] started " + id + " pid=" + p.pid() + " log=" + log.getName());
            p.onExit().thenAccept(done -> getLogger().info("[testkit] " + id + " exited code=" + done.exitValue()));
        } catch (IOException e) {
            getLogger().warning("[testkit] failed to start " + id + ": " + e.getMessage());
        }
    }
}
