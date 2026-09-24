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

    /** Names allowed from anywhere while in offline test mode: {root}/allow.txt, one per line. Re-read on each login. */
    private boolean allowListed(String name) {
        File file = new File(root, "allow.txt");
        if (!file.isFile()) return false;
        try {
            for (String line : java.nio.file.Files.readAllLines(file.toPath())) {
                if (line.trim().equalsIgnoreCase(name)) return true;
            }
        } catch (IOException e) {
            getLogger().warning("[testkit] cannot read allow.txt: " + e.getMessage());
        }
        return false;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (Bukkit.getOnlineMode()) return;
        boolean loopback = event.getAddress().isLoopbackAddress();
        boolean botName = event.getName().toLowerCase().startsWith("dcbot");
        if (allowListed(event.getName())) {
            getLogger().info("[testkit] allow-listed login: " + event.getName() + " from " + event.getAddress().getHostAddress());
            return;
        }
        if (!loopback || !botName) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text("Server is in maintenance for testing. Try again later."));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
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
