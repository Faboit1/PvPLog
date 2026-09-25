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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
        open = readOpen();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("TestKit ready, root=" + root + " onlineMode=" + Bukkit.getOnlineMode()
            + " joinMode=" + (open ? "open" : "testers"));
        if (open && !Bukkit.getOnlineMode()) getLogger().warning("[testkit] " + OPEN_NOTICE + " (/tester on for testers only)");
        // DuelCore's tester mode is also /tester: name the way to this one if that one took the label
        getServer().getScheduler().runTask(this, () -> {
            org.bukkit.command.PluginCommand owner = Bukkit.getPluginCommand("tester");
            if (owner == null || owner.getPlugin() != this) {
                getLogger().warning("[testkit] /tester is handled by another plugin; the join guard is /testers "
                    + "(or /duelcoretestkit:tester) on|off|status|add|remove|list");
            }
        });
    }

    @Override
    public void onDisable() {
        processes.values().forEach(Process::destroyForcibly);
        processes.clear();
    }

    /*
     * Who may join while the server runs in offline test mode. Two modes, set with /tester on|off and saved in
     * {root}/mode.txt (missing or unreadable = testers only):
     * - testers only (/tester on, the default): the names in {root}/allow.txt and on the vanilla whitelist.
     * - open testing (/tester off): anyone, so more people can test.
     * Offline mode can't verify names, so in both modes each name is locked to the IP it first joins from (trust on
     * first use). allow.txt has one "name [ip] [guest]" per line and is re-read on every login; "guest" marks a name
     * that first joined during open testing (not a tester: refused again once the server is back to testers only).
     * /tester add clears a name's lock. Operator names are never trusted on first use in open testing: they must
     * already be locked (join once while testers only), so nobody can take an op's name while the server is open.
     */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final String OPEN_NOTICE =
        "Open testing: anyone can join; names are not verified in offline mode, ops stay IP-locked.";

    /** An allow.txt line: the locked IP ("" = locks on the next join) and whether it is an open-testing guest. */
    private record Entry(String ip, boolean guest) {}

    private volatile boolean open;

    private File allowFile() {
        return new File(root, "allow.txt");
    }

    private File modeFile() {
        return new File(root, "mode.txt");
    }

    private synchronized Map<String, Entry> readAllow() {
        Map<String, Entry> map = new LinkedHashMap<>();
        File file = allowFile();
        if (!file.isFile()) return map;
        try {
            for (String line : Files.readAllLines(file.toPath())) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 0 || parts[0].isEmpty() || parts[0].startsWith("#")) continue;
                String ip = parts.length > 1 && !parts[1].equals("guest") ? parts[1] : "";
                boolean guest = parts.length > 1 && parts[parts.length - 1].equals("guest");
                map.put(parts[0], new Entry(ip, guest));
            }
        } catch (IOException e) {
            getLogger().warning("[testkit] cannot read allow.txt: " + e.getMessage());
        }
        return map;
    }

    /** Throws when the file can't be written: the login guard then refuses (a lock that isn't saved is no lock). */
    private synchronized void writeAllow(Map<String, Entry> map) {
        List<String> lines = new ArrayList<>();
        map.forEach((name, e) -> lines.add(name + (e.ip().isEmpty() ? "" : " " + e.ip()) + (e.guest() ? " guest" : "")));
        try {
            root.mkdirs();
            Files.write(allowFile().toPath(), lines);
        } catch (IOException e) {
            getLogger().warning("[testkit] cannot write allow.txt: " + e.getMessage());
            throw new UncheckedIOException(e);
        }
    }

    /** mode.txt: "open" or "testers". Anything else, a missing file or a read error means testers only. */
    private boolean readOpen() {
        File file = modeFile();
        if (!file.isFile()) return false;
        try {
            for (String line : Files.readAllLines(file.toPath())) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                if (s.equalsIgnoreCase("open")) return true;
                if (!s.equalsIgnoreCase("testers")) getLogger().warning("[testkit] mode.txt: unknown mode '" + s + "', testers only");
                return false;
            }
        } catch (IOException | RuntimeException e) {
            getLogger().warning("[testkit] cannot read mode.txt, testers only: " + e);
        }
        return false;
    }

    /** Switches the mode now and saves it; false when it could not be saved (it then only lasts until a restart). */
    private boolean setOpen(boolean value) {
        open = value;
        try {
            root.mkdirs();
            Files.write(modeFile().toPath(), List.of(
                "# open = anyone can join (IP-locked on first use), testers = allow.txt only. Set with /tester off|on.",
                value ? "open" : "testers"));
            return true;
        } catch (IOException e) {
            getLogger().warning("[testkit] cannot write mode.txt: " + e.getMessage());
            if (value) return false;
            try {
                Files.deleteIfExists(modeFile().toPath()); // no file = testers only
                return true;
            } catch (IOException ignored) {
                return false;
            }
        }
    }

    private static @org.jetbrains.annotations.Nullable String key(Map<String, ?> map, String name) {
        for (String k : map.keySet()) if (k.equalsIgnoreCase(name)) return k;
        return null;
    }

    /** Operators by name or (offline) UUID. Called off the main thread; an error fails the login closed. */
    private static boolean isOperator(String name, UUID uuid) {
        for (org.bukkit.OfflinePlayer op : Bukkit.getOperators()) {
            if (uuid.equals(op.getUniqueId()) || name.equalsIgnoreCase(op.getName())) return true;
        }
        return false;
    }

    /** null = allowed; otherwise the kick reason. Pins the IP on first use. */
    private synchronized @org.jetbrains.annotations.Nullable String checkLogin(String name, UUID uuid, String ip) {
        Map<String, Entry> map = readAllow();
        String k = key(map, name);
        Entry entry = k == null ? null : map.get(k);
        if (!open) {
            if (entry == null || entry.guest()) {
                boolean whitelisted = Bukkit.getWhitelistedPlayers().stream().anyMatch(o -> name.equalsIgnoreCase(o.getName()));
                if (!whitelisted) return "Server is in maintenance for testing. Ask an op to /tester add you.";
                if (entry == null) {
                    k = name;
                    entry = new Entry("", false);
                }
            }
            return lock(map, k, entry, ip, "tester");
        }
        // open testing: a locked name only from its IP, operators only once locked, anyone else locked on first use
        if (entry != null && !entry.ip().isEmpty()) return lock(map, k, entry, ip, entry.guest() ? "guest" : "tester");
        if (isOperator(name, uuid)) {
            getLogger().warning("[testkit] refused operator name " + name + " from " + ip + " (not IP-locked, open testing)");
            return "This is an operator name. It can only join open testing once it is IP-locked: join while the server is testers only.";
        }
        if (entry != null) return lock(map, k, entry, ip, "tester");
        if (!NAME.matcher(name).matches() || name.toLowerCase().startsWith("dcbot")) return "This name can't join open testing.";
        return lock(map, name, new Entry("", true), ip, "guest");
    }

    /** Admits a name from its locked IP, or locks an unlocked name to this IP (saved before admitting). */
    private @org.jetbrains.annotations.Nullable String lock(Map<String, Entry> map, String k, Entry entry, String ip, String kind) {
        if (entry.ip().isEmpty()) {
            map.put(k, new Entry(ip, entry.guest()));
            writeAllow(map);
            getLogger().info("[testkit] " + kind + " " + k + " locked to " + ip);
            return null;
        }
        if (!entry.ip().equals(ip)) {
            getLogger().warning("[testkit] refused " + k + " from " + ip + " (locked to another IP)");
            return "This name is locked to another IP while the server is in testing mode. Ask an op to /tester add you again.";
        }
        return null;
    }

    /**
     * The test-mode login guard (offline mode only): loopback "dcbot*" connections are the test bots; everyone else
     * must pass {@link #checkLogin} (testers only, or open testing), always from the IP their name is locked to.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (Bukkit.getOnlineMode()) return;
        boolean loopback = event.getAddress().isLoopbackAddress();
        boolean botName = event.getName().toLowerCase().startsWith("dcbot");
        if (loopback && botName) return;
        String reason;
        try {
            reason = checkLogin(event.getName(), event.getUniqueId(), event.getAddress().getHostAddress());
        } catch (RuntimeException e) {
            getLogger().warning("[testkit] login check failed for " + event.getName() + ": " + e);
            reason = "Server is in maintenance for testing. Try again later."; // fail closed
        }
        if (reason == null) {
            getLogger().info("[testkit] " + (open ? "open-testing" : "tester") + " login: " + event.getName()
                + " from " + event.getAddress().getHostAddress());
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

    private static final String USAGE = "/tester on | off | status | add <player> | remove <player> | list";

    /** /tester (ops): the join mode (on = testers only, off = open testing) and who counts as a tester. */
    private boolean tester(CommandSender sender, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        try {
            synchronized (this) {
                switch (sub) {
                    case "on", "off" -> mode(sender, sub.equals("off"));
                    case "status" -> status(sender);
                    case "add" -> add(sender, args);
                    case "remove" -> remove(sender, args);
                    case "list" -> list(sender);
                    default -> {
                        status(sender);
                        sender.sendMessage(USAGE);
                    }
                }
            }
        } catch (UncheckedIOException e) {
            sender.sendMessage("Could not save allow.txt: " + e.getCause().getMessage());
        }
        return true;
    }

    private void mode(CommandSender sender, boolean value) {
        if (open == value) {
            sender.sendMessage("Already " + (value ? "open testing" : "testers only") + ".");
            return;
        }
        boolean saved = setOpen(value);
        String text = value
            ? OPEN_NOTICE + " New names are locked to the IP they first join from. /tester on for testers only again."
            : "Testers only: only the names in /tester list (and the test bots) can join. Players online now stay.";
        if (!saved) text += " Could not save mode.txt: this only lasts until a restart.";
        if (Bukkit.getOnlineMode()) text += " (The guard is inactive while the server is in online mode.)";
        if (!(sender instanceof ConsoleCommandSender)) sender.sendMessage(text);
        if (value) getLogger().warning("[testkit] " + sender.getName() + ": " + text);
        else getLogger().info("[testkit] " + sender.getName() + ": " + text);
    }

    private void status(CommandSender sender) {
        Map<String, Entry> map = readAllow();
        long guests = map.values().stream().filter(Entry::guest).count();
        sender.sendMessage("Join mode: " + (open ? OPEN_NOTICE : "testers only (/tester off opens it to anyone).")
            + " Testers: " + (map.size() - guests) + ", open-testing guests: " + guests + "."
            + (Bukkit.getOnlineMode() ? " (The guard is inactive while the server is in online mode.)" : ""));
    }

    private void add(CommandSender sender, String[] args) {
        if (args.length < 2 || !NAME.matcher(args[1]).matches()) {
            sender.sendMessage("Usage: /tester add <player>");
            return;
        }
        Map<String, Entry> map = readAllow();
        String k = key(map, args[1]);
        map.remove(k == null ? args[1] : k);
        map.put(args[1], new Entry("", false)); // (re)lock to the IP they join from next
        writeAllow(map);
        sender.sendMessage(args[1] + " can join now (locked to the IP they join from next).");
        getLogger().info("[testkit] " + sender.getName() + " added tester " + args[1]);
    }

    private void remove(CommandSender sender, String[] args) {
        Map<String, Entry> map = readAllow();
        String k = args.length < 2 ? null : key(map, args[1]);
        if (k == null) {
            sender.sendMessage("Not a tester: " + (args.length < 2 ? "?" : args[1]));
            return;
        }
        map.remove(k);
        writeAllow(map);
        sender.sendMessage("Removed " + k + ".");
        getLogger().info("[testkit] " + sender.getName() + " removed " + k);
    }

    private void list(CommandSender sender) {
        List<String> testers = new ArrayList<>();
        List<String> guests = new ArrayList<>();
        readAllow().forEach((name, e) -> {
            if (e.guest()) guests.add(name);
            else testers.add(name + (e.ip().isEmpty() ? " (not joined yet)" : ""));
        });
        sender.sendMessage("Testers (" + testers.size() + "): " + String.join(", ", testers));
        if (!guests.isEmpty()) sender.sendMessage("Open-testing guests (" + guests.size() + "): " + String.join(", ", guests));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("tester")) return List.of();
        if (args.length == 1) return List.of("on", "off", "status", "add", "remove", "list").stream()
            .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) return new ArrayList<>(readAllow().keySet());
        if (args.length == 2 && args[0].equalsIgnoreCase("add")) return Bukkit.getOnlinePlayers().stream().map(org.bukkit.entity.Player::getName).toList();
        return List.of();
    }

    /**
     * Bot-only "!unstick": mineflayer stops simulating physics after a (server-cancelled) death until it receives a
     * position packet. Re-sending the bot its own position restores it. Only loopback dcbot players can use this.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onBotChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        org.bukkit.entity.Player p = event.getPlayer();
        java.net.InetSocketAddress address = p.getAddress();
        if (address == null || !address.getAddress().isLoopbackAddress() || !p.getName().toLowerCase().startsWith("dcbot")) return;
        String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message());
        if (!text.equals("!unstick")) return;
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> {
            if (p.isOnline()) p.teleport(p.getLocation());
        });
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
